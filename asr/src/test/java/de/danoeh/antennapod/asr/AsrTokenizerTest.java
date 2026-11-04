package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsrTokenizerTest {
    @Test
    public void wordPieceMarksStartOfEveryWord() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello world",
                new String[]{"▁hello", "▁world"}, new float[]{0.1f, 0.5f}, 1000);

        assertEquals(2, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
        assertEquals(100, words.get(0).getStartTime());
        assertEquals(500, words.get(0).getEndTime());
        assertEquals("world", words.get(1).getText());
        assertEquals(6, words.get(1).getStartChar());
        assertEquals(11, words.get(1).getEndChar());
        assertEquals(500, words.get(1).getStartTime());
        assertEquals(1000, words.get(1).getEndTime());
    }

    @Test
    public void pieceWithoutBoundaryContinuesPreviousWord() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello",
                new String[]{"▁hel", "lo"}, new float[]{0.1f, 0.2f}, 1000);

        assertEquals(1, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
        assertEquals(100, words.get(0).getStartTime());
        assertEquals(1000, words.get(0).getEndTime());
    }

    @Test
    public void cjkPiecesBecomeOneWordPerCharacter() {
        List<AsrWord> words = AsrTokenizer.tokenize("你好",
                new String[]{"你", "好"}, new float[]{0.1f, 0.2f}, 1000);

        assertEquals(2, words.size());
        assertEquals("你", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(1, words.get(0).getEndChar());
        assertEquals(100, words.get(0).getStartTime());
        assertEquals(200, words.get(0).getEndTime());
        assertEquals("好", words.get(1).getText());
        assertEquals(1, words.get(1).getStartChar());
        assertEquals(2, words.get(1).getEndChar());
        assertEquals(200, words.get(1).getStartTime());
        assertEquals(1000, words.get(1).getEndTime());
    }

    @Test
    public void byteFallbackPieceKeepsItsLiteralText() {
        List<AsrWord> words = AsrTokenizer.tokenize("<0xE4>",
                new String[]{"<0xE4>"}, new float[]{0.1f}, 1000);

        assertEquals(1, words.size());
        assertEquals("<0xE4>", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(6, words.get(0).getEndChar());
        assertEquals(100, words.get(0).getStartTime());
        assertEquals(1000, words.get(0).getEndTime());
    }

    @Test
    public void pieceMissingFromTextDegradesToTheWholeSegment() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello",
                new String[]{"▁hel", "zzz"}, new float[]{0.1f, 0.2f}, 1000);

        assertEquals(1, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
        assertEquals(100, words.get(0).getStartTime());
        assertEquals(1000, words.get(0).getEndTime());
    }

    @Test
    public void wordRangesAreOrderedDisjointAndCoverTheirText() {
        String text = "one two three";
        List<AsrWord> words = AsrTokenizer.tokenize(text,
                new String[]{"▁one", "▁two", "▁three"},
                new float[]{0.1f, 0.3f, 0.6f}, 2000);

        assertEquals(3, words.size());
        StringBuilder covered = new StringBuilder();
        int previousEnd = -1;
        for (AsrWord word : words) {
            assertEquals(word.getText(),
                    text.substring(word.getStartChar(), word.getEndChar()));
            assertTrue(word.getStartChar() >= previousEnd);
            previousEnd = word.getEndChar();
            covered.append(word.getText());
        }
        assertEquals(text.replace(" ", ""), covered.toString());
    }

    @Test
    public void missingPunctuationPieceIsSkipped() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello world",
                new String[]{"▁hello", ",", "▁world"},
                new float[]{0.1f, 0.2f, 0.5f}, 1000);

        assertEquals(2, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals("world", words.get(1).getText());
    }

    @Test
    public void missingByteFallbackPieceIsSkipped() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello",
                new String[]{"▁hel", "<0xE4>", "lo"},
                new float[]{0.1f, 0.2f, 0.3f}, 1000);

        assertEquals(1, words.size());
        assertEquals("hello", words.get(0).getText());
    }

    @Test
    public void missingPieceAfterACompletedWordIsSkipped() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello world",
                new String[]{"▁hello", "▁world", "zzz"},
                new float[]{0.1f, 0.5f, 0.6f}, 1000);

        assertEquals(2, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals("world", words.get(1).getText());
    }

    @Test
    public void leadingSpaceTokensMarkWordBoundaries() {
        List<AsrWord> words = AsrTokenizer.tokenize("hello world",
                new String[]{"hello", " world"}, new float[]{0.1f, 0.5f}, 1000);

        assertEquals(2, words.size());
        assertEquals("hello", words.get(0).getText());
        assertEquals("world", words.get(1).getText());
        assertEquals(6, words.get(1).getStartChar());
    }

    @Test
    public void emptyTokensReturnNoWords() {
        assertTrue(AsrTokenizer.tokenize("hello", new String[0], new float[0], 0).isEmpty());
    }
}
