package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.TranscriptSegment;

import org.junit.Test;

public class TranscriptTextUtilsTest {

    @Test
    public void testPrimaryLengthStopsAtNewline() {
        assertEquals(5, TranscriptTextUtils.primaryLength("Hello\nWelt"));
        assertEquals(11, TranscriptTextUtils.primaryLength("Hello World"));
        assertEquals(0, TranscriptTextUtils.primaryLength("\nWelt"));
        assertEquals(0, TranscriptTextUtils.primaryLength(""));
    }

    @Test
    public void testCharIndexAtUsesPrimaryLineOnly() {
        String text = "Hello\nWelt";
        assertEquals(0, TranscriptTextUtils.charIndexAt(text, 0f));
        assertEquals(5, TranscriptTextUtils.charIndexAt(text, 1f));
        assertEquals(3, TranscriptTextUtils.charIndexAt(text, 0.5f));
    }

    @Test
    public void testIsLongSegment() {
        assertFalse(TranscriptTextUtils.isLongSegment(segment(0, 14999, "Short.")));
        assertTrue(TranscriptTextUtils.isLongSegment(segment(0, 15000, "Long.")));
        assertTrue(TranscriptTextUtils.isLongSegment(segment(1000, 20000, "Long.")));
    }

    @Test
    public void testProgressAtClampsToSegment() {
        TranscriptSegment segment = segment(1000, 2000, "Text.");
        assertEquals(0f, TranscriptTextUtils.progressAt(segment, 0), 0.0001f);
        assertEquals(0.5f, TranscriptTextUtils.progressAt(segment, 1500), 0.0001f);
        assertEquals(1f, TranscriptTextUtils.progressAt(segment, 5000), 0.0001f);
        assertEquals(0f, TranscriptTextUtils.progressAt(segment(1000, 1000, "Text."), 1000), 0.0001f);
    }

    @Test
    public void testChunkStartsSplitOnSentenceEnd() {
        assertArrayEquals(new int[] {0, 6, 13}, TranscriptTextUtils.chunkStarts("Hello. World."));
        assertArrayEquals(new int[] {0, 4, 8}, TranscriptTextUtils.chunkStarts("第一句。第二句！"));
        assertArrayEquals(new int[] {0, 4}, TranscriptTextUtils.chunkStarts("One."));
    }

    @Test
    public void testChunkStartsKeepDecimalNumbersTogether() {
        assertArrayEquals(new int[] {0, 21}, TranscriptTextUtils.chunkStarts("Price is 3.5 dollars."));
        assertArrayEquals(new int[] {0, 26}, TranscriptTextUtils.chunkStarts("It costs 3.5. And 4.5 too."));
    }

    @Test
    public void testChunkStartsIncludeTrailingQuotes() {
        assertArrayEquals(new int[] {0, 13, 18}, TranscriptTextUtils.chunkStarts("He said \"Hi.\" Bye."));
        assertArrayEquals(new int[] {0, 10, 14}, TranscriptTextUtils.chunkStarts("Wait what? No."));
        assertArrayEquals(new int[] {0, 11, 11, 15}, TranscriptTextUtils.chunkStarts("Wait what?! No."));
    }

    @Test
    public void testChunkStartsIgnoreBoundaryBeyondLength() {
        assertArrayEquals(new int[] {0, 3}, TranscriptTextUtils.chunkStarts("Hi. There", 3));
    }

    @Test
    public void testSplitIntoSentences() {
        assertEquals("Hello.\nWorld.", TranscriptTextUtils.splitIntoSentences("Hello. World."));
        assertEquals("第一句。\n第二句！", TranscriptTextUtils.splitIntoSentences("第一句。第二句！"));
        assertEquals("One.", TranscriptTextUtils.splitIntoSentences("One."));
    }

    @Test
    public void testSplitIntoSentencesKeepsSecondaryLanguageLine() {
        assertEquals("Hello.\nWorld.\nHallo. Welt.",
                TranscriptTextUtils.splitIntoSentences("Hello. World.\nHallo. Welt."));
    }

    @Test
    public void testSentenceIndexAt() {
        String text = "One. Two. Three.";
        assertEquals(0, TranscriptTextUtils.sentenceIndexAt(text, 0f, text.length()));
        assertEquals(1, TranscriptTextUtils.sentenceIndexAt(text, 0.4f, text.length()));
        assertEquals(2, TranscriptTextUtils.sentenceIndexAt(text, 0.9f, text.length()));
    }

    @Test
    public void testSentenceStartTime() {
        String text = "One. Two. Three.";
        TranscriptSegment segment = segment(10000, 16000, text);
        assertEquals(10000, TranscriptTextUtils.sentenceStartTime(segment, text, 0));
        assertEquals(12000, TranscriptTextUtils.sentenceStartTime(segment, text, 1));
        assertEquals(14000, TranscriptTextUtils.sentenceStartTime(segment, text, 2));
        assertEquals(14000, TranscriptTextUtils.sentenceStartTime(segment, text, 99));
        assertEquals(10000, TranscriptTextUtils.sentenceStartTime(segment, "Single sentence.", 3));
    }

    private static TranscriptSegment segment(long start, long end, String words) {
        return new TranscriptSegment(start, end, words, "");
    }
}
