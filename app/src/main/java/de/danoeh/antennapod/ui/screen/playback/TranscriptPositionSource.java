package de.danoeh.antennapod.ui.screen.playback;

import android.util.Log;
import androidx.annotation.Nullable;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.parser.media.Mp3DurationScanner;
import de.danoeh.antennapod.ui.transcript.TranscriptSession;
import de.danoeh.antennapod.ui.transcript.TranscriptTiming;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Converts between player and transcript time, including MP3 duration-scale correction. */
final class TranscriptPositionSource {
    private static final String TAG = "TranscriptPosition";
    private static final Map<String, Double> SCALE_CACHE = new ConcurrentHashMap<>();

    interface Host {
        boolean isAdded();

        @Nullable
        FeedMedia getMedia();

        long getLastPositionMs();

        long getPlayerPositionOrNegative();

        void post(Runnable runnable);

        void onScaleResolved(long mediaId);
    }

    private final Host host;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile TranscriptSession session;
    private long generation;

    TranscriptPositionSource(Host host) {
        this.host = host;
    }

    double scale() {
        TranscriptSession current = session;
        return current == null ? 1.0 : current.getScale();
    }

    long currentPlaybackPosition() {
        long position = host.getPlayerPositionOrNegative();
        return position >= 0 ? toTranscriptTime(position) : host.getLastPositionMs();
    }

    long offsetAt(long positionMs) {
        TranscriptSession current = session;
        return current == null ? 0 : current.offsetAt(positionMs);
    }

    long offsetAtCue(long cueTimeMs) {
        TranscriptSession current = session;
        return current == null ? 0 : current.offsetAtCue(cueTimeMs);
    }

    long toTranscriptTime(long playerPositionMs) {
        TranscriptSession current = session;
        return current == null
                ? TranscriptTiming.toTranscriptTime(playerPositionMs, 1.0)
                : current.toTranscriptTime(playerPositionMs);
    }

    long toPlayerTime(long transcriptPositionMs) {
        TranscriptSession current = session;
        return current == null
                ? TranscriptTiming.toPlayerTime(transcriptPositionMs, 1.0)
                : current.toPlayerTime(transcriptPositionMs);
    }

    void load(FeedMedia media) {
        session = new TranscriptSession(media.getId(), media.getTranscript());
        final TranscriptSession loadSession = session;
        final long loadGeneration = ++generation;
        if (media.getTranscript() != null && media.getTranscript().isGenerated()) {
            return;
        }
        final long reportedDuration = media.getDuration();
        if (reportedDuration <= 0) {
            return;
        }
        long audioDuration = media.getTranscript() == null
                ? 0 : media.getTranscript().getAudioDurationMs();
        if (audioDuration > 0) {
            String cacheKey = media.getId() + ":json:" + audioDuration + ":" + reportedDuration;
            Double cached = SCALE_CACHE.get(cacheKey);
            double resolved = cached != null ? cached
                    : resolveScale(audioDuration, reportedDuration);
            loadSession.setScale(resolved);
            SCALE_CACHE.put(cacheKey, resolved);
            return;
        }
        if (media.getMediaType() != MediaType.AUDIO || !media.localFileAvailable()) {
            return;
        }
        String path = media.getLocalFileUrl();
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return;
        }
        String cacheKey = media.getId() + ":mp3:" + file.length() + ":" + reportedDuration;
        Double cached = SCALE_CACHE.get(cacheKey);
        if (cached != null) {
            loadSession.setScale(cached);
            return;
        }
        final long mediaId = media.getId();
        executor.execute(() -> {
            double resolvedScale = 1.0;
            try {
                Mp3DurationScanner.Result result = Mp3DurationScanner.scan(file);
                if (result != null && !result.hasXingHeader()) {
                    resolvedScale = resolveScale(result.getDurationMs(), reportedDuration);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not scan MP3 duration", e);
            }
            SCALE_CACHE.put(cacheKey, resolvedScale);
            final double resolved = resolvedScale;
            host.post(() -> {
                if (loadGeneration != generation || !host.isAdded()) {
                    return;
                }
                loadSession.setScale(resolved);
                FeedMedia mediaNow = host.getMedia();
                if (resolved != 1.0 && mediaNow != null && mediaNow.getId() == mediaId) {
                    host.onScaleResolved(mediaId);
                }
            });
        });
    }

    void shutdown() {
        generation++;
        executor.shutdownNow();
    }

    private static double resolveScale(long trueDurationMs, long reportedDurationMs) {
        return TranscriptTiming.scaleFromDurations(trueDurationMs, reportedDurationMs);
    }
}
