package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

import static org.junit.Assert.assertEquals;

public class AsrSegmentContractTest {
    @Test
    public void eachWordCoversItsOwnTextInTheSegment() {
        AsrSegment segment = new AsrSegment("hello world", Arrays.asList(
                word("hello", 0, 100, 0, 5),
                word("world", 100, 200, 6, 11)));

        assertEquals("hello world", segment.getText());
        assertEquals(2, segment.getWords().size());
        assertEquals("hello", segment.getWords().get(0).getText());
        assertEquals("world", segment.getWords().get(1).getText());
    }

    @Test
    public void segmentSpanCoversItsWords() {
        AsrSegment segment = new AsrSegment("hello world", Arrays.asList(
                word("hello", 120, 300, 0, 5),
                word("world", 250, 480, 6, 11)));

        TranscriptSegment transcriptSegment = segment.toTranscriptSegment("speaker");

        assertEquals(120, transcriptSegment.getStartTime());
        assertEquals(480, transcriptSegment.getEndTime());
        assertEquals("speaker", transcriptSegment.getSpeaker());
        assertEquals(2, transcriptSegment.getWordTimings().size());
        TranscriptWord first = transcriptSegment.getWordTimings().get(0);
        assertEquals(0, first.getStartChar());
        assertEquals(5, first.getEndChar());
        assertEquals(120, first.getStartTime());
        assertEquals(300, first.getEndTime());
    }

    @Test
    public void emptyWordsUseAZeroSpan() {
        AsrSegment segment = new AsrSegment("hello", Collections.emptyList());

        TranscriptSegment transcriptSegment = segment.toTranscriptSegment("");

        assertEquals(0, transcriptSegment.getStartTime());
        assertEquals(0, transcriptSegment.getEndTime());
        assertEquals(0, transcriptSegment.getWordTimings().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void wordStartingBeforeTheTextIsRejected() {
        new AsrSegment("hello", Collections.singletonList(word("hello", 0, 100, -1, 5)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void wordPastTheEndOfTheTextIsRejected() {
        new AsrSegment("hello", Collections.singletonList(word("hello", 0, 100, 1, 6)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void wordWithInvertedRangeIsRejected() {
        new AsrSegment("hello", Collections.singletonList(word("ell", 0, 100, 3, 2)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void wordThatDisagreesWithItsRangeIsRejected() {
        new AsrSegment("hello", Collections.singletonList(word("world", 0, 100, 0, 5)));
    }

    private static AsrWord word(String text, long start, long end, int startChar, int endChar) {
        return new AsrWord(text, start, end, startChar, endChar);
    }
}
