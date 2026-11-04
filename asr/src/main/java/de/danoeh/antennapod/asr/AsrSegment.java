package de.danoeh.antennapod.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

/** One recognized sentence and the word ranges that cover it. */
public final class AsrSegment {
    private final String text;
    private final List<AsrWord> words;

    public AsrSegment(String text, List<AsrWord> words) {
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
        this.text = text;
        this.words = words == null ? Collections.emptyList() : new ArrayList<>(words);
        for (AsrWord word : this.words) {
            validateWord(word);
        }
    }

    public String getText() {
        return text;
    }

    public List<AsrWord> getWords() {
        return Collections.unmodifiableList(words);
    }

    public TranscriptSegment toTranscriptSegment(String speaker) {
        if (words.isEmpty()) {
            return new TranscriptSegment(0, 0, text, speaker);
        }
        long startTime = Long.MAX_VALUE;
        long endTime = 0;
        List<TranscriptWord> wordTimings = new ArrayList<>(words.size());
        for (AsrWord word : words) {
            startTime = Math.min(startTime, word.getStartTime());
            endTime = Math.max(endTime, word.getEndTime());
            wordTimings.add(new TranscriptWord(word.getStartChar(), word.getEndChar(),
                    word.getStartTime(), word.getEndTime()));
        }
        return new TranscriptSegment(startTime, endTime, text, speaker, wordTimings);
    }

    private void validateWord(AsrWord word) {
        if (word == null) {
            throw new IllegalArgumentException("word is null");
        }
        if (word.getStartChar() < 0) {
            throw new IllegalArgumentException("word '" + word.getText()
                    + "' starts before the text: " + word.getStartChar());
        }
        if (word.getEndChar() > text.length()) {
            throw new IllegalArgumentException("word '" + word.getText()
                    + "' ends past the text: " + word.getEndChar() + " > " + text.length());
        }
        if (word.getEndChar() < word.getStartChar()) {
            throw new IllegalArgumentException("word '" + word.getText()
                    + "' has inverted range: " + word.getStartChar() + ".." + word.getEndChar());
        }
        String covered = text.substring(word.getStartChar(), word.getEndChar());
        if (!covered.equals(word.getText())) {
            throw new IllegalArgumentException("word '" + word.getText()
                    + "' does not match its range " + word.getStartChar() + ".."
                    + word.getEndChar() + ", which holds '" + covered + "'");
        }
    }
}
