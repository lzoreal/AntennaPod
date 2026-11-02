package de.danoeh.antennapod.ui.transcript;

import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptWord;

/**
 * Maps transcript time to the character that should be at the reading position of one
 * transcript sentence. Built from the word timings of a segment, so the floating window
 * scrolls in step with the words that are actually being read instead of assuming a
 * constant speed over the whole sentence.
 */
public final class TranscriptScrollTimeline {
    private final long[] times;
    private final int[] chars;
    private final float[] slopes;

    private TranscriptScrollTimeline(long[] times, int[] chars) {
        this.times = times;
        this.chars = chars;
        this.slopes = computeSlopes(times, chars);
    }

    /**
     * @param words        word timings of the segment (character offsets relative to the segment)
     * @param displayStart character offset of the displayed sentence inside the segment text
     * @param length       length of the displayed sentence text
     * @param position     current transcript position
     * @param endTime      transcript time at which the displayed sentence ends
     */
    public static TranscriptScrollTimeline of(List<TranscriptWord> words, int displayStart,
                                              int length, long position, long endTime) {
        long end = Math.max(endTime, position + 1);
        long[] times = new long[words.size() + 2];
        int[] chars = new int[words.size() + 2];
        int count = 0;
        times[count] = position;
        chars[count] = 0;
        for (TranscriptWord word : words) {
            if (word.getStartTime() > position) {
                break;
            }
            chars[count] = clamp(word.getStartChar() - displayStart, length);
        }
        count++;
        for (TranscriptWord word : words) {
            long startTime = word.getStartTime();
            if (startTime <= times[count - 1] || startTime >= end) {
                continue;
            }
            times[count] = startTime;
            chars[count] = clamp(word.getStartChar() - displayStart, length);
            count++;
        }
        times[count] = end;
        chars[count] = length;
        count++;
        long[] resultTimes = new long[count];
        int[] resultChars = new int[count];
        System.arraycopy(times, 0, resultTimes, 0, count);
        System.arraycopy(chars, 0, resultChars, 0, count);
        return new TranscriptScrollTimeline(resultTimes, resultChars);
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(value, length));
    }

    /** Transcript time at which the displayed sentence ends. */
    public long getEndTime() {
        return times[times.length - 1];
    }

    /** Character offset (relative to the displayed sentence) at {@code time}. */
    public float charIndexAt(long time) {
        int last = times.length - 1;
        if (time <= times[0]) {
            return chars[0];
        }
        if (time >= times[last]) {
            return chars[last];
        }
        int index = 1;
        while (index < last && time > times[index]) {
            index++;
        }
        long span = times[index] - times[index - 1];
        if (span <= 0) {
            return chars[index];
        }
        // Monotone cubic Hermite, so the speed changes smoothly at word boundaries
        // instead of jumping from one constant speed to the next.
        float s = (time - times[index - 1]) / (float) span;
        float m0 = slopes[index - 1] * span;
        float m1 = slopes[index] * span;
        float s2 = s * s;
        float s3 = s2 * s;
        return (2f * s3 - 3f * s2 + 1f) * chars[index - 1]
                + (s3 - 2f * s2 + s) * m0
                + (-2f * s3 + 3f * s2) * chars[index]
                + (s3 - s2) * m1;
    }

    /** Monotone (Fritsch-Carlson) tangents in characters per millisecond. */
    private static float[] computeSlopes(long[] times, int[] chars) {
        int count = times.length - 1;
        float[] secants = new float[count];
        for (int i = 0; i < count; i++) {
            long span = times[i + 1] - times[i];
            secants[i] = span <= 0 ? 0f : (chars[i + 1] - chars[i]) / (float) span;
        }
        float[] result = new float[times.length];
        if (count == 1) {
            result[0] = secants[0];
            result[1] = secants[0];
            return result;
        }
        result[0] = secants[0];
        result[times.length - 1] = secants[count - 1];
        for (int i = 1; i < count; i++) {
            if (secants[i - 1] * secants[i] <= 0f) {
                result[i] = 0f;
                continue;
            }
            float slope = (secants[i - 1] + secants[i]) / 2f;
            float limit = 3f * Math.min(Math.abs(secants[i - 1]), Math.abs(secants[i]));
            if (Math.abs(slope) > limit) {
                slope = Math.signum(slope) * limit;
            }
            result[i] = slope;
        }
        return result;
    }
}
