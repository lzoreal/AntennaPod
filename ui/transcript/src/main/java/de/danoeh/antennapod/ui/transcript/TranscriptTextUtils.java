package de.danoeh.antennapod.ui.transcript;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.parser.transcript.TranscriptSentenceUtils;

public final class TranscriptTextUtils {

    private TranscriptTextUtils() {
    }

    public static float progressAt(TranscriptSegment segment, long position) {
        long duration = segment.getEndTime() - segment.getStartTime();
        if (duration <= 0) {
            return 0f;
        }
        float progress = (position - segment.getStartTime()) / (float) duration;
        return Math.max(0f, Math.min(1f, progress));
    }

    public static int primaryLength(String text) {
        return TranscriptSentenceUtils.primaryLength(text);
    }

    public static int languageSplitIndex(String text) {
        return TranscriptSentenceUtils.languageSplitIndex(text);
    }

    public static int charIndexAt(String text, float progress) {
        return TranscriptSentenceUtils.charIndexAt(text, progress);
    }

    public static int[] chunkStarts(String text) {
        return TranscriptSentenceUtils.chunkStarts(text);
    }

    public static int[] chunkStarts(String text, int length) {
        return TranscriptSentenceUtils.chunkStarts(text, length);
    }

    public static int sentenceIndexAt(String text, float progress, int length) {
        return TranscriptSentenceUtils.sentenceIndexAt(text, progress, length);
    }

    public static int sentenceIndexAt(int[] starts, float progress, int length) {
        return TranscriptSentenceUtils.sentenceIndexAt(starts, progress, length);
    }

    public static long sentenceEndTime(TranscriptSegment segment, String text, int sentenceIndex, int length) {
        return TranscriptSentenceUtils.sentenceEndTime(segment, text, sentenceIndex, length);
    }

    public static long sentenceEndTime(TranscriptSegment segment, int[] starts, int sentenceIndex) {
        return TranscriptSentenceUtils.sentenceEndTime(segment, starts, sentenceIndex);
    }

    public static long sentenceStartTime(TranscriptSegment segment, String text, int sentenceIndex, int length) {
        return TranscriptSentenceUtils.sentenceStartTime(segment, text, sentenceIndex, length);
    }

    public static long sentenceStartTime(TranscriptSegment segment, int[] starts, int sentenceIndex) {
        return TranscriptSentenceUtils.sentenceStartTime(segment, starts, sentenceIndex);
    }

    public static String splitIntoSentences(String text) {
        return TranscriptSentenceUtils.splitIntoSentences(text);
    }
}
