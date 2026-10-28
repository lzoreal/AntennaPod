package de.danoeh.antennapod.model.feed;

import java.util.Collections;
import java.util.List;

public class TranscriptSegment {
    private final long startTime;
    private long endTime;
    private String words;
    private final String speaker;
    private List<TranscriptWord> wordTimings;

    public TranscriptSegment(long start, long end, String w, String s) {
        this(start, end, w, s, Collections.emptyList());
    }

    public TranscriptSegment(long start, long end, String w, String s,
                             List<TranscriptWord> wordTimings) {
        startTime = start;
        endTime = end;
        words = w;
        speaker = s;
        this.wordTimings = wordTimings == null ? Collections.emptyList() : wordTimings;
    }

    public void append(long newEndTime, String wordsToAppend) {
        endTime = Math.max(endTime, newEndTime);
        words += " " + wordsToAppend;
        wordTimings = Collections.emptyList();
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public String getWords() {
        return words;
    }

    public String getSpeaker() {
        return speaker;
    }

    public List<TranscriptWord> getWordTimings() {
        return wordTimings;
    }

    public boolean hasWordTimings() {
        return !wordTimings.isEmpty();
    }

    /** Last word that started at or before {@code time}; -1 before the first word. */
    public int getWordIndexAt(long time) {
        int low = 0;
        int high = wordTimings.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (wordTimings.get(middle).getStartTime() <= time) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }
}
