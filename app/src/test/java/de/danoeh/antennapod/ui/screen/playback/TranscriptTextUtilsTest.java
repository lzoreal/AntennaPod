package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.ui.transcript.TranscriptTextUtils;

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
    public void testCharIndexAtUsesPrimaryBlockOnly() {
        assertEquals(11, TranscriptTextUtils.charIndexAt("Hello there\n你好。", 1f));
        assertEquals(6, TranscriptTextUtils.charIndexAt("Hello there\n你好。", 0.5f));
        assertEquals(11, TranscriptTextUtils.charIndexAt("Hello there", 1f));
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
        assertArrayEquals(new int[] {0, 13, 26}, TranscriptTextUtils.chunkStarts("It costs 3.5. And 4.5 too."));
    }

    @Test
    public void testChunkStartsSplitAfterNumberedSentence() {
        assertArrayEquals(new int[] {0, 12, 27},
                TranscriptTextUtils.chunkStarts("At least 38. It is precise."));
        assertArrayEquals(new int[] {0, 12, 32},
                TranscriptTextUtils.chunkStarts("It was 1999. Everything changed."));
    }

    @Test
    public void testChunkStartsIncludeTrailingQuotes() {
        assertArrayEquals(new int[] {0, 13, 18}, TranscriptTextUtils.chunkStarts("He said \"Hi.\" Bye."));
        assertArrayEquals(new int[] {0, 10, 14}, TranscriptTextUtils.chunkStarts("Wait what? No."));
        assertArrayEquals(new int[] {0, 11, 15}, TranscriptTextUtils.chunkStarts("Wait what?! No."));
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
    public void testSplitIntoSentencesSplitsSameScriptLines() {
        assertEquals("Hello.\nWorld.\nHallo.\nWelt.",
                TranscriptTextUtils.splitIntoSentences("Hello. World.\nHallo. Welt."));
    }

    @Test
    public void testLanguageSplitIndexLocatesTrailingLanguageBlock() {
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("Hello\nWelt"));
        assertEquals(5, TranscriptTextUtils.languageSplitIndex("Hello\n你好。\n世界。"));
        assertEquals(13, TranscriptTextUtils.languageSplitIndex("Hello. World.\n你好。"));
        assertEquals(13, TranscriptTextUtils.languageSplitIndex("Hello. World.\n你好。\n世界。"));
        assertEquals(5, TranscriptTextUtils.languageSplitIndex("Hello\n\n你好。"));
        assertEquals(10, TranscriptTextUtils.languageSplitIndex("Hi.\nThere.\n你好。\n世界。"));
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("Hello. World."));
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("Hello. World.\nHallo. Welt."));
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("Hello\n"));
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("Hello\n\n"));
    }

    @Test
    public void testSplitIntoSentencesKeepsWrappedPrimarySentences() {
        assertEquals("Hello.\nWorld.\n你好。",
                TranscriptTextUtils.splitIntoSentences("Hello. World.\n你好。"));
        assertEquals("Hello.\nWorld.\n你好。\n世界。",
                TranscriptTextUtils.splitIntoSentences("Hello. World.\n你好。\n世界。"));
        assertEquals("Hi.\nThere.\n你好。\n世界。",
                TranscriptTextUtils.splitIntoSentences("Hi. There.\n你好。\n世界。"));
        assertEquals("你好。\n世界。",
                TranscriptTextUtils.splitIntoSentences("你好。世界。"));
    }

    @Test
    public void testInterleavedScriptsFallBackToWholeText() {
        assertEquals(-1, TranscriptTextUtils.languageSplitIndex("你好。\nHello.\n世界。"));
    }

    @Test
    public void testEveryLanguageModeKeepsAllSentences() {
        String cue = "Hello there my friend.\nHow are you doing today?\n你好，朋友。\n你今天怎么样？";
        int split = TranscriptTextUtils.languageSplitIndex(cue);
        assertEquals(47, split);
        assertEquals("Hello there my friend.\nHow are you doing today?\n你好，朋友。\n你今天怎么样？",
                TranscriptTextUtils.splitIntoSentences(cue));
        assertEquals("Hello there my friend.\nHow are you doing today?",
                TranscriptTextUtils.splitIntoSentences(cue.substring(0, split)));
        assertEquals("你好，朋友。\n你今天怎么样？",
                TranscriptTextUtils.splitIntoSentences(cue.substring(split + 1)));
        assertEquals(47, TranscriptTextUtils.charIndexAt(TranscriptTextUtils.splitIntoSentences(cue), 1f));
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
        assertEquals(10000, TranscriptTextUtils.sentenceStartTime(segment, text, 0, text.length()));
        assertEquals(11500, TranscriptTextUtils.sentenceStartTime(segment, text, 1, text.length()));
        assertEquals(13375, TranscriptTextUtils.sentenceStartTime(segment, text, 2, text.length()));
        assertEquals(13375, TranscriptTextUtils.sentenceStartTime(segment, text, 99, text.length()));
        String single = "Single sentence.";
        assertEquals(10000, TranscriptTextUtils.sentenceStartTime(segment, single, 3, single.length()));
    }

    @Test
    public void testSentenceEndTime() {
        String text = "One. Two. Three.";
        TranscriptSegment segment = segment(10000, 16000, text);

        assertEquals(11500, TranscriptTextUtils.sentenceEndTime(segment, text, 0, text.length()));
        assertEquals(13375, TranscriptTextUtils.sentenceEndTime(segment, text, 1, text.length()));
        assertEquals(16000, TranscriptTextUtils.sentenceEndTime(segment, text, 2, text.length()));
        assertEquals(16000, TranscriptTextUtils.sentenceEndTime(segment, text, 99, text.length()));
        assertEquals(16000, TranscriptTextUtils.sentenceEndTime(segment, "Single.", 0, 7));
    }

    private static TranscriptSegment segment(long start, long end, String words) {
        return new TranscriptSegment(start, end, words, "");
    }
}
