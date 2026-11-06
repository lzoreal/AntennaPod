package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import de.danoeh.antennapod.asr.AsrGenerationSession;
import de.danoeh.antennapod.asr.CacheAudioSource;
import de.danoeh.antennapod.asr.AsrModelCatalog;
import de.danoeh.antennapod.asr.AsrModelStore;
import de.danoeh.antennapod.asr.AsrSegment;
import de.danoeh.antennapod.asr.AsrTranscriptStore;
import de.danoeh.antennapod.asr.AudioFingerprint;
import de.danoeh.antennapod.asr.FileAudioSource;
import de.danoeh.antennapod.asr.GeneratedTranscriptCheckpoint;
import de.danoeh.antennapod.asr.LookaheadPlanner;
import de.danoeh.antennapod.asr.MediaCodecPcmDecoder;
import de.danoeh.antennapod.asr.MediaRange;
import de.danoeh.antennapod.asr.PreReadPcmPipeline;
import de.danoeh.antennapod.asr.SherpaRecognizerFactory;
import de.danoeh.antennapod.asr.SherpaStreamingRecognizer;
import de.danoeh.antennapod.asr.TranscriptCoverage;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.internal.ExoPlayerUtils;
import de.danoeh.antennapod.parser.media.Mp3DurationScanner;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/** Runs one generated-transcript session for a downloaded or streaming episode. */
@OptIn(markerClass = UnstableApi.class)
public final class AsrGenerationController {
    private static final String TAG = "AsrGeneration";
    private static final long CUSHION_MS = 5000;
    private static final long SEEK_THRESHOLD_MS = 1500;
    private static final long IDLE_SLEEP_MS = 50;
    private static final int COMPACT_SEGMENT_COUNT = 20;
    private static final long COMPACT_INTERVAL_MS = 30_000;

    public interface Listener {
        void onSegment(@NonNull AsrSegment segment);

        void onProgress(long decodedEndMs);

        void onCompleted();

        void onError(@NonNull Throwable error);
    }

    private final Context context;
    private final FeedMedia media;
    private final LongSupplier playheadMs;
    private final Listener listener;
    private final boolean finalizeToEnd;
    private final AtomicLong finalizePlayhead = new AtomicLong(0);
    private final AsrModelStore modelStore;
    private final String modelId;
    private long durationMs;
    private long audioBytes;
    private String audioFingerprint;
    private final List<AsrSegment> segments = new ArrayList<>();
    private int pendingJournalSegments;
    private long lastCompactionMs;
    private long lastPersistedWordEndMs = -1;
    private List<MediaRange> existingScanned = Collections.emptyList();

    private volatile boolean running;
    private volatile boolean stopRequested;
    private boolean leaseHeld;
    private AsrTranscriptStore transcriptStore;
    private PreReadPcmPipeline pipeline;
    private AsrGenerationSession session;

    public AsrGenerationController(@NonNull Context context, @NonNull FeedMedia media,
                                   @NonNull LongSupplier playheadMs,
                                   @NonNull Listener listener) {
        this(context, media, playheadMs, listener, false);
    }

    /**
     * @param finalizeToEnd decode every remaining gap as fast as possible instead of following
     *                      the playback position (used by the background finalize worker)
     */
    public AsrGenerationController(@NonNull Context context, @NonNull FeedMedia media,
                                   @NonNull LongSupplier playheadMs,
                                   @NonNull Listener listener, boolean finalizeToEnd) {
        this.context = context.getApplicationContext();
        this.media = media;
        this.playheadMs = playheadMs;
        this.listener = listener;
        this.finalizeToEnd = finalizeToEnd;
        this.modelStore = modelStore(context);
        this.modelId = AsrModelCatalog.model().getId();
        this.durationMs = Math.max(0, media.getDuration());
    }

    public static boolean isSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static AsrModelStore modelStore(@NonNull Context context) {
        return new AsrModelStore(new File(context.getFilesDir(), "asr-models"));
    }


    public boolean isRunning() {
        return running;
    }

    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("generation is already running");
        }
        if (!AsrSessionLease.acquire(media.getId())) {
            throw new SessionBusyException(media.getId());
        }
        leaseHeld = true;
        try {
            startLocked();
        } catch (IOException | RuntimeException e) {
            releaseLease();
            throw e;
        }
    }

    private void startLocked() throws IOException {
        File localFile = media.getLocalFileUrl() == null ? null : new File(media.getLocalFileUrl());
        String transcriptPath = media.getGeneratedTranscriptFileUrl();
        if (transcriptPath == null) {
            throw new IOException("episode has no transcript location");
        }
        androidx.media3.datasource.cache.SimpleCache streamCache = null;
        if (localFile == null) {
            streamCache = ExoPlayerUtils.getSimpleCache();
            if (streamCache == null || media.getStreamUrl() == null) {
                throw new IOException("stream cache is not available");
            }
        }
        durationMs = localFile != null ? resolveDuration(localFile) : Math.max(0, media.getDuration());
        if (localFile != null) {
            audioBytes = localFile.length();
            audioFingerprint = AudioFingerprint.of(localFile);
        }
        Log.i(TAG, "start media=" + media.getId()
                + " duration=" + durationMs + " playhead=" + playheadMs.getAsLong()
                + " streaming=" + (localFile == null));
        transcriptStore = new AsrTranscriptStore(new File(transcriptPath));
        TranscriptCoverage coverage = new TranscriptCoverage();
        synchronized (segments) {
            segments.clear();
            if (transcriptStore.hasTranscript()) {
                try {
                    segments.addAll(transcriptStore.loadSegments());
                } catch (IOException e) {
                    segments.clear();
                }
                try {
                    GeneratedTranscriptCheckpoint checkpoint = transcriptStore.loadCheckpoint();
                    if (!checkpoint.matchesAudio(durationMs, audioBytes, audioFingerprint)) {
                        // The episode was replaced or re-downloaded; old words do not line up.
                        Log.i(TAG, "checkpoint audio changed; discarding previous progress");
                        segments.clear();
                    } else {
                        coverage = checkpoint.toCoverage();
                        existingScanned = checkpoint.getScanned();
                        for (MediaRange range : checkpoint.getScanned()) {
                            coverage.add(range);
                        }
                    }
                } catch (IOException e) {
                    for (MediaRange range : AsrTranscriptStore.coverageOf(segments)) {
                        coverage.add(range);
                    }
                }
            }
        }

        pipeline = new PreReadPcmPipeline(new MediaCodecPcmDecoder(),
                new LookaheadPlanner(UserPreferences.getAsrLookaheadSeconds() * 1000L, CUSHION_MS),
                SEEK_THRESHOLD_MS, coverage);
        if (transcriptStore.hasJournal()) {
            // Fold a journal left by a crash into the snapshot before the UI reads it again.
            save();
        }
        if (localFile != null) {
            pipeline.open(new FileAudioSource(localFile));
        } else {
            pipeline.open(new CacheAudioSource(streamCache, media.getStreamUrl()));
        }
        if (stopRequested) {
            pipeline.close();
            releaseLease();
            return;
        }

        final SherpaStreamingRecognizer recognizer;
        try {
            recognizer = SherpaRecognizerFactory.create(modelStore.spec());
        } catch (RuntimeException e) {
            pipeline.close();
            throw new IOException("cannot load speech model", e);
        }
        if (stopRequested) {
            recognizer.close();
            pipeline.close();
            releaseLease();
            return;
        }

        final LongSupplier sessionPlayhead = finalizeToEnd ? finalizePlayhead::get : playheadMs;
        AsrGenerationSession newSession = new AsrGenerationSession(pipeline, recognizer,
                sessionPlayhead, new SessionListener(), IDLE_SLEEP_MS);
        if (stopRequested) {
            // stop() landed while the decoder/model were still being prepared; do not start a
            // worker that nobody is waiting for.
            newSession.stop();
            recognizer.close();
            pipeline.close();
            releaseLease();
            return;
        }
        session = newSession;
        running = true;
        Log.i(TAG, "Generation started");
        new Thread(() -> {
            try {
                session.run();
            } finally {
                releaseLease();
            }
        }, "AsrGeneration").start();
    }

    public void stop() {
        stopRequested = true;
        AsrGenerationSession current = session;
        if (current != null) {
            current.stop();
        }
    }

    private synchronized void releaseLease() {
        if (leaseHeld) {
            leaseHeld = false;
            AsrSessionLease.release(media.getId());
        }
    }

    /** Another session (live page or finalize worker) already owns this episode. */
    public static final class SessionBusyException extends IOException {
        SessionBusyException(long mediaId) {
            super("generation already owns media " + mediaId);
        }
    }

    private final class SessionListener implements AsrGenerationSession.Listener {
        @Override
        public void onSegment(@NonNull AsrSegment segment) {
            long segmentStart = segment.getWords().isEmpty() ? -1
                    : segment.getWords().get(0).getStartTime();
            long segmentEnd = segment.getWords().isEmpty() ? -1
                    : segment.getWords().get(segment.getWords().size() - 1).getEndTime();
            Log.d(TAG, "segment start=" + segmentStart + " end=" + segmentEnd
                    + " words=" + segment.getWords().size()
                    + " playhead=" + playheadMs.getAsLong()
                    + " decoded=" + pipeline.decodedEndMs()
                    + " coverage=" + pipeline.coverageRanges());
            synchronized (segments) {
                segments.add(segment);
                if (appendOrCompact(segment) && !segment.getWords().isEmpty()) {
                    lastPersistedWordEndMs = Math.max(lastPersistedWordEndMs,
                            segment.getWords().get(segment.getWords().size() - 1).getEndTime());
                }
            }
            listener.onSegment(segment);
        }

        @Override
        public void onCaughtUp(long decodedEndMs) {
            if (finalizeToEnd) {
                long next = decodedEndMs;
                if (next < 0) {
                    // Nothing was decoded in this run and the lookahead window is already
                    // covered: jump to the first gap so finalization makes progress.
                    next = pipeline.nextUncoveredMs(Math.max(0, finalizePlayhead.get()));
                }
                if (next >= 0) {
                    finalizePlayhead.set(next);
                }
            }
            Log.d(TAG, "caughtUp playhead=" + playheadMs.getAsLong()
                    + " decoded=" + decodedEndMs
                    + " coverage=" + pipeline.coverageRanges());
            listener.onProgress(decodedEndMs);
        }

        @Override
        public void onCompleted() {
            if (!save(true)) {
                running = false;
                listener.onError(new IOException("unable to persist generated transcript"));
                return;
            }
            running = false;
            listener.onCompleted();
        }

        @Override
        public void onError(@NonNull Throwable error) {
            running = false;
            listener.onError(error);
        }
    }

    /**
     * Appends one finalized segment to the journal and only rewrites the full snapshot when the
     * journal grows large or old. Audio that was decoded but never finalized is never marked as
     * covered, so a process death cannot skip it.
     */
    private boolean appendOrCompact(AsrSegment segment) {
        if (hasOfficialTranscript()) {
            // The published transcript replaced the generated one; late ASR segments must not
            // be appended to its sidecar.
            return false;
        }
        try {
            transcriptStore.append(segment);
            pendingJournalSegments++;
        } catch (IOException e) {
            Log.w(TAG, "Unable to append generated segment; falling back to a snapshot", e);
            save();
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (pendingJournalSegments >= COMPACT_SEGMENT_COUNT
                || now - lastCompactionMs >= COMPACT_INTERVAL_MS) {
            save();
        }
        return true;
    }

    private void save() {
        save(false);
    }

    /**
     * @return false when the snapshot could not be written; callers must not report success.
     */
    private boolean save(boolean complete) {
        if (transcriptStore == null || pipeline == null) {
            return true;
        }
        if (hasOfficialTranscript()) {
            // Not an error: the published transcript is the source of truth now.
            Log.i(TAG, "Official transcript available; skipping generated snapshot");
            return true;
        }
        List<MediaRange> coverage;
        synchronized (segments) {
            coverage = AsrTranscriptStore.coverageOf(segments);
        }
        try {
            transcriptStore.save(segments, durationMs, coverage, modelId,
                    audioBytes, audioFingerprint, scannedRanges(complete));
            pendingJournalSegments = 0;
            lastCompactionMs = SystemClock.elapsedRealtime();
            Log.i(TAG, "saved segments=" + segments.size()
                    + " coverage=" + coverage);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Unable to save generated transcript", e);
            return false;
        }
    }

    /**
     * Ranges decoded so far. Silence has no words, so the word coverage alone can never show
     * that the finalizer already visited it. Only ranges up to the last persisted word are
     * marked, so a crash cannot skip speech that was decoded but never written.
     */
    private List<MediaRange> scannedRanges(boolean complete) {
        TranscriptCoverage scanned = new TranscriptCoverage();
        for (MediaRange range : existingScanned) {
            scanned.add(range);
        }
        long frontier = complete
                ? Math.max(lastPersistedWordEndMs, pipeline.decodedEndMs())
                : lastPersistedWordEndMs;
        if (frontier > 0) {
            for (MediaRange range : pipeline.coverageRanges()) {
                if (range.startMs() >= frontier) {
                    continue;
                }
                scanned.add(new MediaRange(range.startMs(), Math.min(range.endMs(), frontier)));
            }
        }
        return scanned.ranges();
    }

    private boolean hasOfficialTranscript() {
        return media.getItem() != null && media.getItem().getTranscriptUrl() != null;
    }

    private long resolveDuration(File localFile) {
        long reported = Math.max(0, media.getDuration());
        if (!localFile.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            return reported;
        }
        try {
            Mp3DurationScanner.Result result = Mp3DurationScanner.scan(localFile);
            if (result != null && !result.hasXingHeader() && result.getDurationMs() > 0) {
                return result.getDurationMs();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not scan MP3 duration", e);
        }
        return reported;
    }
}
