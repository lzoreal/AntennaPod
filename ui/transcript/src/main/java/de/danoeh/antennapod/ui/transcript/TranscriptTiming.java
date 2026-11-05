package de.danoeh.antennapod.ui.transcript;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/** Shared rules for mapping player positions to transcript positions in every display path. */
public final class TranscriptTiming {
    private static final long MIN_DURATION_DIFF_MS = 250;
    private static final double MIN_SCALE = 0.9;
    private static final double MAX_SCALE = 1.1;

    private TranscriptTiming() {
    }

    public static long offsetAt(Transcript transcript, long mediaId, long mediaPositionMs) {
        return UserPreferences.getTranscriptTimeOffset(mediaId, mediaPositionMs);
    }

    public static long offsetAtCue(Transcript transcript, long mediaId, long cueTimeMs) {
        return UserPreferences.getTranscriptTimeOffsetAtCueTime(mediaId, cueTimeMs);
    }

    public static double scale(Transcript transcript, long reportedDurationMs) {
        if (transcript == null || transcript.isGenerated()) {
            return 1.0;
        }
        return scaleFromDurations(transcript.getAudioDurationMs(), reportedDurationMs);
    }

    /**
     * True audio length from the transcript file versus the player's reported length. A ratio
     * outside {@code [0.9, 1.1]} means the metadata is broken, so it is ignored rather than
     * stretching the whole episode.
     */
    public static double scaleFromDurations(long audioDurationMs, long reportedDurationMs) {
        if (reportedDurationMs <= 0 || audioDurationMs <= 0
                || Math.abs(audioDurationMs - reportedDurationMs) < MIN_DURATION_DIFF_MS) {
            return 1.0;
        }
        double candidate = (double) audioDurationMs / reportedDurationMs;
        if (candidate < MIN_SCALE || candidate > MAX_SCALE) {
            return 1.0;
        }
        return Math.abs(candidate - 1.0) >= 0.0002 ? candidate : 1.0;
    }

    public static long toTranscriptTime(long playerPositionMs, double scale) {
        if (playerPositionMs < 0 || scale == 1.0) {
            return playerPositionMs;
        }
        return Math.round(playerPositionMs * scale);
    }

    public static long toPlayerTime(long transcriptPositionMs, double scale) {
        if (transcriptPositionMs < 0 || scale == 1.0) {
            return transcriptPositionMs;
        }
        return Math.round(transcriptPositionMs / scale);
    }
}
