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

    TranscriptAsrCoordinator(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
    }

    void detach() {
        if (modelDownloader != null) {
            modelDownloader.cancel();
            modelDownloader = null;
        }
        sessionToken++;
        active = false;
        modelDownloading = false;
        handler.removeCallbacksAndMessages(null);
        binding = null;
    }

    boolean isRunningHere() {
        FeedMedia media = host.getCurrentMedia();
        return media != null && (AsrGenerationManager.isRunning(media.getId()) || modelDownloading);
    }

    void syncRunningState() {
        FeedMedia media = host.getCurrentMedia();
        active = media != null && AsrGenerationManager.isRunning(media.getId());
        updateVisibility();
    }

    /** Stops the previous episode's generation when the page switches media. */
    void onMediaChanged(@Nullable FeedMedia previous, @Nullable FeedMedia next) {
        if (previous != null && (next == null || previous.getId() != next.getId())
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
        syncRunningState();
    }

    public void onEvent(AsrGenerationEvent event) {
        FeedMedia media = host.getCurrentMedia();
        if (media == null || event.mediaId != media.getId()) {
            return;
        }
        switch (event.type) {
            case STARTED:
                active = true;
                updateVisibility();
                break;
            case SEGMENT:
                active = true;
                if (event.segment != null) {
                    appendSegment(event.segment.toTranscriptSegment(null));
                }
                break;
            case PROGRESS:
                if (binding != null && active) {
                    binding.btnGenerateTranscript.setText(host.getContext().getString(
                            R.string.transcript_generate_progress_format,
                            DateUtils.formatElapsedTime(event.decodedEndMs / 1000)));
                }
                break;
            case COMPLETED:
                active = false;
                lastReloadMs = 0;
                updateVisibility();
                host.reloadTranscript();
                host.showToast(R.string.transcript_generate_done);
                break;
            case ERROR:
                active = false;
                updateVisibility();
                host.showToast(R.string.transcript_generate_failed);
                if (event.error != null) {
                    Log.e(TAG, "ASR generation failed", event.error);
                }
                break;
            case STOPPED:
                active = false;
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
        boolean support = hasSource
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
        if (available) {
            binding.btnGenerateTranscript.setEnabled(!active && !modelDownloading);
            if (!active && !modelDownloading) {
                binding.btnGenerateTranscript.setText(R.string.transcript_generate);
            } else if (!modelDownloading) {
                binding.btnGenerateTranscript.setText(R.string.transcript_generate_running);
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
        binding.btnContinueTranscript.setVisibility(continueVisible ? View.VISIBLE : View.GONE);
        boolean runningHere = media != null && AsrGenerationManager.isRunning(media.getId());
        binding.btnCancelTranscript.setVisibility(runningHere ? View.VISIBLE : View.GONE);
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
        binding.btnGenerateTranscript.setText(R.string.transcript_generate_running);
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
                        startSession();
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
                binding.btnGenerateTranscript.setText(R.string.transcript_model_installing);
            } else {
                binding.btnGenerateTranscript.setText(
                        host.getContext().getString(
                                R.string.transcript_model_downloading_format, percent));
            }
        }
    }

    void cancelGeneration() {
        FeedMedia media = host.getCurrentMedia();
        if (media == null) {
            return;
        }
        AsrGenerationManager.stopIfRunningFor(media.getId());
        active = false;
        updateVisibility();
    }

    private void startSession() {
        Context context = host.getContext();
        if (active || host.getCurrentMedia() == null || context == null || binding == null) {
            return;
        }
        active = true;
        binding.btnGenerateTranscript.setEnabled(false);
        binding.btnGenerateTranscript.setText(R.string.transcript_generate_running);
        AsrGenerationManager.start(context.getApplicationContext(), host.getCurrentMedia());
    }

    private void appendSegment(TranscriptSegment segment) {
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
