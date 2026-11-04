package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Build;
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
import de.danoeh.antennapod.asr.AsrWord;
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
    private final List<AsrSegment> segments = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean stopRequested;
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
        Log.i(TAG, "start media=" + media.getId()
                + " duration=" + durationMs + " playhead=" + playheadMs.getAsLong()
                + " streaming=" + (localFile == null));
        transcriptStore = new AsrTranscriptStore(new File(transcriptPath));
        TranscriptCoverage coverage = new TranscriptCoverage();
        synchronized (segments) {
            segments.clear();
            if (transcriptStore.hasTranscript()) {
                try {
                    GeneratedTranscriptCheckpoint checkpoint = transcriptStore.loadCheckpoint();
                    segments.addAll(transcriptStore.loadSegments());
                    coverage = checkpoint.toCoverage();
                } catch (IOException e) {
                    segments.clear();
                }
            }
        }

        pipeline = new PreReadPcmPipeline(new MediaCodecPcmDecoder(),
                new LookaheadPlanner(UserPreferences.getAsrLookaheadSeconds() * 1000L, CUSHION_MS),
                SEEK_THRESHOLD_MS, coverage);
        if (localFile != null) {
            pipeline.open(new FileAudioSource(localFile));
        } else {
            pipeline.open(new CacheAudioSource(streamCache, media.getStreamUrl()));
        }
        if (stopRequested) {
            pipeline.close();
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
            return;
        }
        if (finalizeToEnd) {
            // Resume at the end of the checkpoint; starting inside covered ranges leaves the
            // lookahead window fully covered and the session stalls without new callbacks.
            long coveredEnd = pipeline.coverageEndMs();
            if (coveredEnd > 0) {
                finalizePlayhead.set(coveredEnd);
            }
        }

        final LongSupplier sessionPlayhead = finalizeToEnd ? finalizePlayhead::get : playheadMs;
        session = new AsrGenerationSession(pipeline, recognizer, sessionPlayhead,
                new SessionListener(), IDLE_SLEEP_MS);
        running = true;
        Log.i(TAG, "Generation started");
        new Thread(session::run, "AsrGeneration").start();
    }

    public void stop() {
        stopRequested = true;
        AsrGenerationSession current = session;
        if (current != null) {
            current.stop();
        }
    }

    private final class SessionListener implements AsrGenerationSession.Listener {
        @Override
        public void onSegment(@NonNull AsrSegment segment) {
            long segmentStart = segment.getWords().isEmpty() ? -1
                    : segment.getWords().get(0).getStartTime();
            long segmentEnd = segment.getWords().isEmpty() ? -1
                    : segment.getWords().get(segment.getWords().size() - 1).getEndTime();
            Log.i(TAG, "segment start=" + segmentStart + " end=" + segmentEnd
                    + " words=" + segment.getWords().size()
                    + " playhead=" + playheadMs.getAsLong()
                    + " decoded=" + pipeline.decodedEndMs()
                    + " coverage=" + pipeline.coverageRanges());
            synchronized (segments) {
                segments.add(segment);
                save();
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
            save(true);
            running = false;
            listener.onCompleted();
        }

        @Override
        public void onError(@NonNull Throwable error) {
            running = false;
            listener.onError(error);
        }
    }

    private void save() {
        save(false);
    }

    private void save(boolean finished) {
        if (transcriptStore == null || pipeline == null) {
            return;
        }
        try {
            List<MediaRange> coverage = finished
                    ? pipeline.coverageRanges() : recognizedCoverage();
            transcriptStore.save(segments, durationMs, coverage, modelId);
            Log.i(TAG, "saved segments=" + segments.size()
                    + " coverage=" + coverage);
        } catch (IOException e) {
            Log.e(TAG, "Unable to save generated transcript", e);
        }
    }

    /**
     * Audio that was decoded but never finalized by the recognizer must not be marked as
     * covered, otherwise a process death would skip it on the next run.
     */
    private List<MediaRange> recognizedCoverage() {
        long recognizedEnd = -1;
        synchronized (segments) {
            for (AsrSegment segment : segments) {
                List<AsrWord> words = segment.getWords();
                if (!words.isEmpty()) {
                    recognizedEnd = Math.max(recognizedEnd,
                            words.get(words.size() - 1).getEndTime());
                }
            }
        }
        if (recognizedEnd < 0) {
            return Collections.emptyList();
        }
        List<MediaRange> clamped = new ArrayList<>();
        for (MediaRange range : pipeline.coverageRanges()) {
            if (range.startMs() >= recognizedEnd) {
                break;
            }
            clamped.add(new MediaRange(range.startMs(), Math.min(range.endMs(), recognizedEnd)));
        }
        return clamped;
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
