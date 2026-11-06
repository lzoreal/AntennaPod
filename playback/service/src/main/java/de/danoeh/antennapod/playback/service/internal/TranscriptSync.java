package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.Nullable;

import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.storage.preferences.TranscriptTimeOffsets;
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
        private final TranscriptTimeOffsets offsetsBefore;
        private final TranscriptTimeOffsets offsetsAfter;

        private Shift(long mediaId,
                      TranscriptTimeOffsets offsetsBefore, TranscriptTimeOffsets offsetsAfter) {
            this.mediaId = mediaId;
            this.offsetsBefore = offsetsBefore;
            this.offsetsAfter = offsetsAfter;
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
        TranscriptTimeOffsets offsetsBefore =
                UserPreferences.getTranscriptTimeOffsetsForMedia(mediaId);
        long offsetAfter = segment.getEndMs() - cueTime;
        UserPreferences.setTranscriptTimeOffset(mediaId, segment.getEndMs(), offsetAfter);
        TranscriptTimeOffsets offsetsAfter =
                UserPreferences.getTranscriptTimeOffsetsForMedia(mediaId);
        return new Shift(mediaId, offsetsBefore, offsetsAfter);
    }

    public static void undo(@Nullable Shift shift) {
        if (shift == null) {
            return;
        }
        TranscriptTimeOffsets current =
                UserPreferences.getTranscriptTimeOffsetsForMedia(shift.mediaId);
        if (!sameAnchors(current, shift.offsetsAfter)) {
            // The user changed another anchor after the skip; restoring the snapshot would
            // discard that edit.
            return;
        }
        UserPreferences.setTranscriptTimeOffsetsForMedia(shift.mediaId, shift.offsetsBefore);
    }

    private static boolean sameAnchors(TranscriptTimeOffsets first, TranscriptTimeOffsets second) {
        List<TranscriptTimeOffsets.Anchor> left = first.getAnchors();
        List<TranscriptTimeOffsets.Anchor> right = second.getAnchors();
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).mediaPositionMs != right.get(i).mediaPositionMs
                    || left.get(i).offsetMs != right.get(i).offsetMs) {
                return false;
            }
        }
        return true;
    }
}
