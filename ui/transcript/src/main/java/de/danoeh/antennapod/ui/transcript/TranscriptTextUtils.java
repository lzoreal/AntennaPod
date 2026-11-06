package de.danoeh.antennapod.ui.transcript;

import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
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

    /**
     * Progress through the row text following the word timings. Used where a sentence has to be
     * picked from a word-level segment: the media duration says nothing about where the words are.
     */
    public static float wordProgressAt(TranscriptSegment segment, long position) {
        List<TranscriptWord> words = segment.getWordTimings();
        int textLength = segment.getWords().length();
        if (words.isEmpty() || textLength <= 0) {
            return progressAt(segment, position);
        }
        int wordIndex = segment.getWordIndexAt(position);
        if (wordIndex < 0) {
            return 0f;
        }
        if (wordIndex >= words.size()) {
            wordIndex = words.size() - 1;
        }
        TranscriptWord word = words.get(wordIndex);
        long duration = word.getEndTime() - word.getStartTime();
        float within = duration <= 0 ? 1f
                : Math.max(0f, Math.min(1f,
                        (position - word.getStartTime()) / (float) duration));
        float charIndex = word.getStartChar()
                + within * (word.getEndChar() - word.getStartChar());
        return Math.max(0f, Math.min(1f, charIndex / textLength));
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
