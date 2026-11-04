package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AsrResultMapperTest {
    @Test
    public void relativeTimestampsAreShiftedToMediaTime() {
        AsrSegment segment = AsrResultMapper.toSegment("hello world",
                new String[]{"▁hello", "▁world"}, new float[]{0.1f, 0.5f}, 10000, 12000);

        List<AsrWord> words = segment.getWords();
        assertEquals(2, words.size());
        assertEquals(9700, words.get(0).getStartTime());
        assertEquals(10100, words.get(0).getEndTime());
        assertEquals(10100, words.get(1).getStartTime());
        assertEquals(12000, words.get(1).getEndTime());
    }

    @Test
    public void emptyTimestampsFallBackToTheStreamSpan() {
        AsrSegment segment = AsrResultMapper.toSegment("hello", null, null, 4000, 4500);

        List<AsrWord> words = segment.getWords();
        assertEquals("Hello.", segment.getText());
        assertEquals(1, words.size());
        assertEquals("Hello", words.get(0).getText());
        assertEquals(4000, words.get(0).getStartTime());
        assertEquals(4500, words.get(0).getEndTime());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
    }

    @Test
    public void cjkTokensUseMediaTime() {
        AsrSegment segment = AsrResultMapper.toSegment("你好",
                new String[]{"你", "好"}, new float[]{0.1f, 0.2f}, 5000, 6000);

        List<AsrWord> words = segment.getWords();
        assertEquals(2, words.size());
        assertEquals(4700, words.get(0).getStartTime());
        assertEquals(4800, words.get(0).getEndTime());
        assertEquals(4800, words.get(1).getStartTime());
        assertEquals(6000, words.get(1).getEndTime());
    }

    @Test
    public void uppercaseModelOutputIsLowercased() {
        AsrSegment segment = AsrResultMapper.toSegment("HELLO WORLD",
                new String[]{"▁HELLO", "▁WORLD"}, new float[]{0.1f, 0.5f}, 10000, 12000);

        List<AsrWord> words = segment.getWords();
        assertEquals("Hello world.", segment.getText());
        assertEquals(2, words.size());
        assertEquals("Hello", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
        assertEquals("world", words.get(1).getText());
        assertEquals(6, words.get(1).getStartChar());
        assertEquals(11, words.get(1).getEndChar());
    }

    @Test
    public void unmatchedTokensFallBackToTheWholeText() {
        AsrSegment segment = AsrResultMapper.toSegment("hello",
                new String[]{"▁hel", "zzz"}, new float[]{0.1f, 0.2f}, 10000, 12000);

        List<AsrWord> words = segment.getWords();
        assertEquals("Hello.", segment.getText());
        assertEquals(1, words.size());
        assertEquals("Hello", words.get(0).getText());
        assertEquals(0, words.get(0).getStartChar());
        assertEquals(5, words.get(0).getEndChar());
        assertEquals(9700, words.get(0).getStartTime());
        assertEquals(12000, words.get(0).getEndTime());
    }
}
