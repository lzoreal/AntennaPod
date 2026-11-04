package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

import de.danoeh.antennapod.asr.AsrGenerationSession;
import de.danoeh.antennapod.asr.AsrModelCatalog;
import de.danoeh.antennapod.asr.AsrModelLanguage;
import de.danoeh.antennapod.asr.AsrModelStore;
import de.danoeh.antennapod.asr.AsrSegment;
import de.danoeh.antennapod.asr.AsrTranscriptStore;
import de.danoeh.antennapod.asr.FileAudioSource;
import de.danoeh.antennapod.asr.GeneratedTranscriptCheckpoint;
import de.danoeh.antennapod.asr.LookaheadPlanner;
import de.danoeh.antennapod.asr.MediaCodecPcmDecoder;
import de.danoeh.antennapod.asr.PreReadPcmPipeline;
import de.danoeh.antennapod.asr.SherpaRecognizerFactory;
import de.danoeh.antennapod.asr.SherpaStreamingRecognizer;
import de.danoeh.antennapod.asr.TranscriptCoverage;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.parser.media.Mp3DurationScanner;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/** Runs one generated-transcript session for a downloaded episode. */
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
    private final AsrModelLanguage language;
    private final LongSupplier playheadMs;
    private final Listener listener;
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
                                   @NonNull AsrModelLanguage language,
                                   @NonNull LongSupplier playheadMs,
                                   @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.media = media;
        this.language = language;
        this.playheadMs = playheadMs;
        this.listener = listener;
        this.modelStore = modelStore(context);
        this.modelId = AsrModelCatalog.forLanguage(language).getId();
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

    @NonNull
    public static AsrModelLanguage languageFor(@NonNull FeedMedia media) {
        String feedLanguage = media.getItem() != null && media.getItem().getFeed() != null
                ? media.getItem().getFeed().getLanguage() : null;
        AsrModelLanguage fallback = AsrModelLanguage.fromFeedLanguage(
                Locale.getDefault().getLanguage(), AsrModelLanguage.EN);
        return AsrModelLanguage.fromFeedLanguage(feedLanguage, fallback);
    }

    public boolean isRunning() {
        return running;
    }

    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("generation is already running");
        }
        if (media.getLocalFileUrl() == null || media.getTranscriptFileUrl() == null) {
            throw new IOException("episode has no local audio file");
        }
        File localFile = new File(media.getLocalFileUrl());
        durationMs = resolveDuration(localFile);
        Log.i(TAG, "start media=" + media.getId() + " language=" + language
                + " duration=" + durationMs + " playhead=" + playheadMs.getAsLong());
        transcriptStore = new AsrTranscriptStore(new File(media.getTranscriptFileUrl()));
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
        pipeline.open(new FileAudioSource(localFile));
        if (stopRequested) {
            pipeline.close();
            return;
        }

        final SherpaStreamingRecognizer recognizer;
        try {
            recognizer = SherpaRecognizerFactory.create(modelStore.spec(language));
        } catch (RuntimeException e) {
            pipeline.close();
            throw new IOException("cannot load speech model", e);
        }
        if (stopRequested) {
            recognizer.close();
            pipeline.close();
            return;
        }

        session = new AsrGenerationSession(pipeline, recognizer, playheadMs,
                new SessionListener(), IDLE_SLEEP_MS);
        running = true;
        Log.i(TAG, "Generation started for " + language);
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
            Log.d(TAG, "caughtUp playhead=" + playheadMs.getAsLong()
                    + " decoded=" + decodedEndMs
                    + " coverage=" + pipeline.coverageRanges());
            listener.onProgress(decodedEndMs);
        }

        @Override
        public void onCompleted() {
            save();
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
        if (transcriptStore == null || pipeline == null) {
            return;
        }
        try {
            transcriptStore.save(segments, durationMs, pipeline.coverageRanges(), modelId);
            Log.i(TAG, "saved segments=" + segments.size()
                    + " coverage=" + pipeline.coverageRanges());
        } catch (IOException e) {
            Log.e(TAG, "Unable to save generated transcript", e);
        }
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
