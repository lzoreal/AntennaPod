package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.asr.AsrModelStore;
import de.danoeh.antennapod.asr.ModelDownloader;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import java.io.IOException;

/** Owns the generated-transcript state machine: start, model download, events, cancel. */
final class TranscriptAsrCoordinator {
    private static final String TAG = "TranscriptAsr";
    private static final long RELOAD_INTERVAL_MS = 1000;

    interface Host {
        @Nullable
        Context getContext();

        @Nullable
        FeedMedia getCurrentMedia();

        @Nullable
        TranscriptAdapter getAdapter();

        boolean isCasting();

        void reloadTranscript();

        void showToast(int resId);

        void onGeneratedSegmentsAppended();
    }

    @Nullable
    private TranscriptPageFragmentBinding binding;
    @Nullable
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;
    private boolean modelDownloading;
    @Nullable
    private ModelDownloader modelDownloader;
    private int lastModelPercent = -1;
    private long lastReloadMs;
    private volatile int sessionToken;
    private boolean completed;
    private boolean pausedState;
    private boolean playbackPaused;
    private CharSequence lastStatusText;
    private CharSequence lastGenerateText;

    TranscriptAsrCoordinator(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
        completed = false;
        pausedState = false;
        playbackPaused = false;
        lastStatusText = null;
        lastGenerateText = null;
    }

    void detach() {
        if (modelDownloader != null) {
            modelDownloader.cancel();
            modelDownloader = null;
        }
        sessionToken++;
        active = false;
        modelDownloading = false;
        completed = false;
        pausedState = false;
        handler.removeCallbacksAndMessages(null);
        binding = null;
        lastStatusText = null;
        lastGenerateText = null;
    }

    // Any requestLayout inside the player page makes the bottom sheet re-measure its content at
    // full height for one pass, which visibly shakes the transcript while ASR streams progress.
    // Only touch the texts when they actually change and when they are on screen.
    private void setStatusText(int resId, Object... args) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        CharSequence text = context.getString(resId, args);
        if (binding == null || TextUtils.equals(text, lastStatusText)) {
            return;
        }
        lastStatusText = text;
        binding.txtAsrStatus.setText(text);
    }

    private void setGenerateText(int resId, Object... args) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        CharSequence text = context.getString(resId, args);
        if (binding == null || binding.emptyState.getVisibility() != View.VISIBLE
                || TextUtils.equals(text, lastGenerateText)) {
            return;
        }
        lastGenerateText = text;
        binding.btnGenerateTranscript.setText(text);
    }

    boolean isRunningHere() {
        FeedMedia media = host.getCurrentMedia();
        return media != null && (AsrGenerationManager.isRunning(media.getId()) || modelDownloading);
    }

    /** Called from the page's playback ticker, which polls the controller every 100–250 ms. */
    void setPlaybackPaused(boolean paused) {
        if (playbackPaused == paused) {
            return;
        }
        playbackPaused = paused;
        updateVisibility();
    }

    void syncRunningState() {
        FeedMedia media = host.getCurrentMedia();
        active = media != null && AsrGenerationManager.isRunning(media.getId());
        updateVisibility();
    }

    /** Stops the previous episode's generation when the page switches media. */
    void onMediaChanged(@Nullable FeedMedia previous, @Nullable FeedMedia next) {
        boolean mediaChanged = previous == null
                ? next != null
                : next == null || previous.getId() != next.getId();
        if (mediaChanged) {
            // Completion belongs to one episode; carrying it over hides the continue action.
            completed = false;
            if (previous != null
                    && (AsrGenerationManager.isRunning(previous.getId()) || modelDownloading)) {
                sessionToken++;
                AsrGenerationManager.stopIfRunningFor(previous.getId());
                if (modelDownloader != null) {
                    modelDownloader.cancel();
                    modelDownloader = null;
                }
                active = false;
                modelDownloading = false;
            }
        }
        syncRunningState();
    }

    public void onEvent(AsrGenerationEvent event) {
        FeedMedia media = host.getCurrentMedia();
        if (media == null || event.mediaId != media.getId()) {
            return;
        }
        Log.d("AsrJitter", "asr event " + event.type + " media=" + event.mediaId
                + " decoded=" + event.decodedEndMs + " segment="
                + (event.segment == null ? "null"
                    : "len=" + event.segment.getText().length()
                    + " words=" + event.segment.getWords().size()));
        switch (event.type) {
            case STARTED:
                active = true;
                setStatusText(R.string.transcript_generate_running);
                updateVisibility();
                break;
            case SEGMENT:
                // A stop flushes the last partial segment after STOPPED; appending it is fine,
                // but it must not flip the session back to "running".
                active = AsrGenerationManager.isRunning(event.mediaId);
                if (event.segment != null) {
                    appendSegment(event.segment.toTranscriptSegment(null));
                }
                break;
            case PROGRESS:
                if (binding != null && active) {
                    String progress = DateUtils.formatElapsedTime(event.decodedEndMs / 1000);
                    if (!pausedState) {
                        setStatusText(R.string.transcript_generating_progress_format, progress);
                    }
                    setGenerateText(R.string.transcript_generate_progress_format, progress);
                }
                break;
            case COMPLETED:
                active = false;
                completed = true;
                lastReloadMs = 0;
                updateVisibility();
                host.reloadTranscript();
                host.showToast(R.string.transcript_generate_done);
                break;
            case ERROR:
                active = false;
                completed = false;
                updateVisibility();
                host.showToast(R.string.transcript_generate_failed);
                if (event.error != null) {
                    Log.e(TAG, "ASR generation failed", event.error);
                }
                break;
            case STOPPED:
                active = false;
                completed = false;
                updateVisibility();
                if (event.stopReason != 0) {
                    host.showToast(event.stopReason);
                }
                break;
            default:
                break;
        }
    }

    void updateVisibility() {
        if (binding == null) {
            return;
        }
        FeedMedia media = host.getCurrentMedia();
        boolean hasSource = media != null
                && (media.getLocalFileUrl() != null
                || (media.getStreamUrl() != null && isCurrentPlaybackMedia(media)));
        final boolean support = hasSource
                && (media.getItem() == null || media.getItem().getTranscriptUrl() == null)
                && AsrGenerationController.isSupported()
                && !host.isCasting();
        // Keep the entry point visible even when generation is unavailable: the click explains
        // whether the episode needs downloading, another device, or cannot generate while casting.
        boolean canOfferGeneration = media != null
                && (media.getItem() == null || media.getItem().getTranscriptUrl() == null);
        boolean available = binding.emptyState.getVisibility() == View.VISIBLE
                && canOfferGeneration;
        binding.btnGenerateTranscript.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.emptyHint.setVisibility(available ? View.VISIBLE : View.GONE);
        if (available) {
            binding.btnGenerateTranscript.setEnabled(!active && !modelDownloading);
            if (!active && !modelDownloading) {
                setGenerateText(R.string.transcript_generate);
            } else if (!modelDownloading) {
                setGenerateText(R.string.transcript_generate_running);
            }
        }

        boolean continueVisible = false;
        long lastEnd = -1;
        long duration = -1;
        if (support && binding.emptyState.getVisibility() != View.VISIBLE
                && media.getTranscript() != null
                && media.getTranscript().isGenerated()
                && !AsrGenerationManager.isRunning(media.getId())) {
            int segmentCount = media.getTranscript().getSegmentCount();
            lastEnd = segmentCount == 0 ? 0
                    : media.getTranscript().getSegmentAt(segmentCount - 1).getEndTime();
            duration = media.getTranscript().getAudioDurationMs();
            continueVisible = duration <= 0 || lastEnd < duration - 5000;
        }
        Log.d("AsrSync", "continue support=" + support
                + " hasSource=" + hasSource
                + " empty=" + (binding.emptyState.getVisibility() == View.VISIBLE)
                + " gen=" + (media != null && media.getTranscript() != null
                && media.getTranscript().isGenerated())
                + " running=" + (media != null
                && AsrGenerationManager.isRunning(media.getId()))
                + " lastEnd=" + lastEnd + " duration=" + duration
                + " visible=" + continueVisible);
        boolean runningHere = media != null && AsrGenerationManager.isRunning(media.getId());
        boolean busy = runningHere || modelDownloading;
        // Generation follows the playhead, so a paused player stalls it. Say so instead of
        // leaving a spinner that never advances. The page polls the controller (PlayerStatusEvent
        // arrives before the MediaController has applied the new state, which flipped the label).
        boolean paused = runningHere && playbackPaused;
        boolean showCompleted = completed && !busy;
        Log.d("AsrJitter", "asr visibility busy=" + busy + " download=" + modelDownloading
                + " paused=" + paused + " complete=" + completed
                + " continue=" + continueVisible + " empty="
                + (binding.emptyState.getVisibility() == View.VISIBLE));
        boolean showStatus = busy || continueVisible || showCompleted;
        final boolean statusWasVisible = binding.statusChip.getVisibility() == View.VISIBLE;
        boolean showContinue = !busy && !showCompleted && continueVisible;
        binding.statusChip.setSelected(showContinue);
        binding.statusChip.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        binding.statusProgress.setVisibility(busy && !paused ? View.VISIBLE : View.GONE);
        binding.statusIcon.setVisibility(paused || showCompleted ? View.VISIBLE : View.GONE);
        if (paused || showCompleted) {
            binding.statusIcon.setImageResource(paused
                    ? R.drawable.ic_pause : R.drawable.ic_check);
        }
        binding.txtAsrStatus.setVisibility(busy || showCompleted ? View.VISIBLE : View.GONE);
        binding.btnContinueTranscript.setVisibility(showContinue ? View.VISIBLE : View.GONE);
        binding.btnCancelTranscript.setVisibility(runningHere ? View.VISIBLE : View.GONE);
        if (paused) {
            setStatusText(R.string.transcript_generate_paused);
        } else if (showCompleted) {
            setStatusText(R.string.transcript_generate_complete);
        } else if (busy && (pausedState || !statusWasVisible)) {
            // Leaving the paused state shows a label again until the next progress event.
            setStatusText(modelDownloading
                    ? R.string.transcript_model_installing
                    : R.string.transcript_generate_running);
        }
        pausedState = paused;
    }

    private static boolean isCurrentPlaybackMedia(FeedMedia media) {
        return media != null && (PlaybackServiceInterface.currentMediaId == media.getId()
                || PlaybackPreferences.getCurrentlyPlayingFeedMediaId() == media.getId());
    }

    void startGeneration() {
        FeedMedia media = host.getCurrentMedia();
        Context context = host.getContext();
        if (active || modelDownloading || media == null || context == null) {
            return;
        }
        if (!AsrGenerationController.isSupported()) {
            host.showToast(R.string.transcript_generate_not_supported);
            return;
        }
        if (host.isCasting()) {
            host.showToast(R.string.transcript_generate_casting);
            return;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && powerManager.isPowerSaveMode()) {
            host.showToast(R.string.transcript_generate_power_saver);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null
                && powerManager.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            host.showToast(R.string.transcript_generate_thermal);
            return;
        }
        boolean hasLocalFile = media.getLocalFileUrl() != null;
        boolean streamingHere = media.getStreamUrl() != null && isCurrentPlaybackMedia(media);
        if (media.getGeneratedTranscriptFileUrl() == null
                || (!hasLocalFile && !streamingHere)) {
            host.showToast(R.string.transcript_generate_needs_download);
            return;
        }
        if (media.getItem() != null && media.getItem().getTranscriptUrl() != null) {
            return;
        }
        AsrModelStore store = AsrGenerationController.modelStore(context);
        if (store.isInstalled()) {
            startSession();
        } else {
            promptModelDownload(store);
        }
    }

    private void promptModelDownload(AsrModelStore store) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.transcript_model_download_title)
                .setMessage(context.getString(R.string.transcript_model_download_message,
                        modelSizeLabel(context.getResources(), store)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> confirmMobileDownload(store))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmMobileDownload(AsrModelStore store) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = manager == null ? null
                : manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean metered = capabilities != null
                && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        if (!metered) {
            downloadModel(store);
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.transcript_model_download_title)
                .setMessage(context.getString(R.string.transcript_model_download_mobile_message,
                        modelSizeLabel(context.getResources(), store)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> downloadModel(store))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String modelSizeLabel(Resources resources, AsrModelStore store) {
        long bytes = store.descriptor().getDownloadSizeBytes();
        return resources.getString(R.string.transcript_model_size_mb,
                Math.max(1, Math.round(bytes / 1_000_000.0)));
    }

    private void downloadModel(AsrModelStore store) {
        if (active || modelDownloading || binding == null) {
            return;
        }
        active = true;
        modelDownloading = true;
        lastModelPercent = -1;
        final int downloadToken = sessionToken;
        modelDownloader = new ModelDownloader(store);
        final ModelDownloader downloader = modelDownloader;
        binding.btnGenerateTranscript.setEnabled(false);
        setGenerateText(R.string.transcript_generate_running);
        setStatusText(R.string.transcript_model_installing);
        updateVisibility();
        new Thread(() -> {
            try {
                downloader.download((read, total) -> {
                    if (total <= 0) {
                        return;
                    }
                    int percent = (int) (read * 100 / total);
                    if (percent != lastModelPercent) {
                        lastModelPercent = percent;
                        handler.post(() -> updateModelDownloadProgress(percent));
                    }
                });
                handler.post(() -> {
                    if (downloadToken != sessionToken) {
                        return;
                    }
                    modelDownloader = null;
                    modelDownloading = false;
                    active = false;
                    if (host.getContext() != null) {
                        // Re-run the full checks: a published transcript may have arrived
                        // while the model was downloading.
                        startGeneration();
                    }
                });
            } catch (IOException e) {
                handler.post(() -> {
                    if (downloadToken != sessionToken) {
                        return;
                    }
                    modelDownloader = null;
                    modelDownloading = false;
                    active = false;
                    updateVisibility();
                    host.showToast(R.string.transcript_model_download_failed);
                    Log.e(TAG, "Model download failed", e);
                });
            }
        }, "AsrModelDownload").start();
    }

    private void updateModelDownloadProgress(int percent) {
        if (binding != null && modelDownloading) {
            if (percent >= 100) {
                // Extraction and SHA-256 verification of the 155 MB encoder run after the
                // transfer finished and used to look like a frozen 100 %.
                setGenerateText(R.string.transcript_model_installing);
                setStatusText(R.string.transcript_model_installing);
            } else {
                setGenerateText(R.string.transcript_model_downloading_format, percent);
                setStatusText(R.string.transcript_model_downloading_format, percent);
            }
        }
    }

    void cancelGeneration() {
        Log.d("AsrJitter", "cancelGeneration");
        FeedMedia media = host.getCurrentMedia();
        if (media == null) {
            return;
        }
        sessionToken++;
        AsrGenerationManager.stopIfRunningFor(media.getId());
        if (modelDownloader != null) {
            modelDownloader.cancel();
            modelDownloader = null;
            modelDownloading = false;
        }
        active = false;
        updateVisibility();
    }

    private void startSession() {
        Log.d("AsrJitter", "startSession currentMedia="
                + (host.getCurrentMedia() == null ? "null" : host.getCurrentMedia().getId()));
        Context context = host.getContext();
        FeedMedia media = host.getCurrentMedia();
        if (active || media == null || context == null || binding == null
                || (media.getItem() != null && media.getItem().getTranscriptUrl() != null)) {
            return;
        }
        active = true;
        completed = false;
        binding.btnGenerateTranscript.setEnabled(false);
        setGenerateText(R.string.transcript_generate_running);
        AsrGenerationManager.start(context.getApplicationContext(), media);
    }

    private void appendSegment(TranscriptSegment segment) {
        Log.d("AsrJitter", "appendSegment " + segment.getStartTime() + "-"
                + segment.getEndTime());
        FeedMedia media = host.getCurrentMedia();
        TranscriptAdapter adapter = host.getAdapter();
        if (binding == null || media == null) {
            return;
        }
        Transcript transcript = media.getTranscript();
        if (transcript == null || !transcript.isGenerated() || adapter == null) {
            // The first segment of a fresh generation: load the file once to build the transcript,
            // then later events are appended in place.
            long now = SystemClock.elapsedRealtime();
            if (now - lastReloadMs >= RELOAD_INTERVAL_MS) {
                lastReloadMs = now;
                host.reloadTranscript();
            }
            return;
        }
        if (adapter.appendTranscriptSegment(segment)) {
            binding.emptyState.setVisibility(View.GONE);
            binding.recyclerView.setVisibility(View.VISIBLE);
            updateVisibility();
            host.onGeneratedSegmentsAppended();
        }
    }
}
