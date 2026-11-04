package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Decides which media range the pre-reader should decode next. */
public final class LookaheadPlanner {
    private final long windowMs;
    private final long cushionMs;

    public LookaheadPlanner(long windowMs, long cushionMs) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive: " + windowMs);
        }
        if (cushionMs < 0) {
            throw new IllegalArgumentException("cushionMs is negative: " + cushionMs);
        }
        this.windowMs = windowMs;
        this.cushionMs = cushionMs;
    }

    /**
     * @param playheadMs   current playback position
     * @param decodedEndMs end of the audio already handed to the recognizer, or -1 before the first read
     * @param seek         true when the playhead jumped and the decoder must be repositioned
     * @param coverage     ranges that already have a transcript
     * @param durationMs   media duration, or a non-positive value when unknown
     * @return the next range to decode, or null when the window is already covered
     */
    @Nullable
    public Plan next(long playheadMs, long decodedEndMs, boolean seek,
                     @NonNull TranscriptCoverage coverage, long durationMs) {
        long playhead = Math.max(0, playheadMs);
        long end;
        if (playhead > Long.MAX_VALUE - windowMs) {
            end = Long.MAX_VALUE;
        } else {
            end = playhead + windowMs;
        }
        if (durationMs > 0 && end > durationMs) {
            end = durationMs;
        }

        long start;
        if (seek || decodedEndMs < 0) {
            start = Math.max(0, playhead - cushionMs);
        } else {
            start = decodedEndMs;
        }
        if (start >= end) {
            return null;
        }

        long uncovered = coverage.nextUncovered(start, end);
        if (uncovered < 0 || uncovered >= end) {
            return null;
        }
        return new Plan(uncovered, end);
    }

    public static final class Plan {
        private final long startMs;
        private final long endMs;

        Plan(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long startMs() {
            return startMs;
        }

        public long endMs() {
            return endMs;
        }

        @Override
        public String toString() {
            return "Plan[" + startMs + ", " + endMs + ")";
        }
    }
}
