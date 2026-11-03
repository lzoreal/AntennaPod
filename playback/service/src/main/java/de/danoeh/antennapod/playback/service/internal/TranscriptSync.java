package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * Keeps the transcript aligned when an automatically detected ad is cut out: the audio after the
 * ad corresponds to the transcript before it, so the offset grows by the skipped duration.
 */
public final class TranscriptSync {
    private TranscriptSync() {
    }

    public static final class Shift {
        private final long mediaId;
        private final int endMs;
        private final long offsetBefore;
        private final boolean hadOffsets;

        private Shift(long mediaId, int endMs, long offsetBefore, boolean hadOffsets) {
            this.mediaId = mediaId;
            this.endMs = endMs;
            this.offsetBefore = offsetBefore;
            this.hadOffsets = hadOffsets;
        }
    }

    /**
     * Moves the transcript offset past the skipped ad. Marked segments are left alone because
     * their boundaries are uncertain, and an alignment the user set after the ad is never
     * overridden.
     */
    @Nullable
    public static Shift alignAfterSkip(long mediaId, AdSegment segment, long positionMs) {
        if (segment.getTier() != AdSegment.TIER_AUTO) {
            return null;
        }
        long offsetBefore = UserPreferences.getTranscriptTimeOffset(mediaId, positionMs);
        long cueTime = positionMs - offsetBefore;
        if (cueTime < 0) {
            // The stored offset already assumes this ad was cut out; shifting again counts it twice.
            return null;
        }
        if (UserPreferences.getTranscriptTimeOffset(mediaId, segment.getEndMs()) != offsetBefore) {
            return null;
        }
        boolean hadOffsets = UserPreferences.hasTranscriptTimeOffset(mediaId);
        long offsetAfter = segment.getEndMs() - cueTime;
        UserPreferences.setTranscriptTimeOffset(mediaId, segment.getEndMs(), offsetAfter);
        return new Shift(mediaId, segment.getEndMs(), offsetBefore, hadOffsets);
    }

    public static void undo(@Nullable Shift shift) {
        if (shift == null) {
            return;
        }
        if (shift.hadOffsets) {
            UserPreferences.setTranscriptTimeOffset(shift.mediaId, shift.endMs, shift.offsetBefore);
        } else {
            UserPreferences.removeTranscriptTimeOffset(shift.mediaId);
        }
    }
}
