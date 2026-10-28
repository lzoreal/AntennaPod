package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

public class TranscriptWordMapperTest {

    @Test
    public void testMapsWordsInSentenceSplitBlock() {
        TranscriptSegment segment = segment("Hello world. Second one.",
                "Hello", "world.", "Second", "one.");
        String display = "Hello world.\nSecond one.";

        int[][] spans = TranscriptWordMapper.map(segment, display,
                new int[][] {{0, display.length()}}, new int[] {0, 13});

        assertNotNull(spans);
        assertWord(display, spans, 0, "Hello");
        assertWord(display, spans, 1, "world.");
        assertWord(display, spans, 2, "Second");
        assertWord(display, spans, 3, "one.");
    }

    @Test
    public void testMapsWordsAcrossInterleavedSentenceLines() {
        TranscriptSegment segment = segment("Hello world. Second one.\n你好世界。第二句。",
                "Hello", "world.", "Second", "one.");
        String display = "Hello world.\n你好世界。\nSecond one.\n第二句。";

        int[][] spans = TranscriptWordMapper.map(segment, display,
                new int[][] {{0, 12}, {19, 30}}, new int[] {0, 13});

        assertNotNull(spans);
        assertWord(display, spans, 0, "Hello");
        assertWord(display, spans, 1, "world.");
        assertWord(display, spans, 2, "Second");
        assertWord(display, spans, 3, "one.");
    }

    @Test
    public void testDoesNotMatchTokenInsideLongerWord() {
        TranscriptSegment segment = new TranscriptSegment(0, 1000, "a and", "",
                Arrays.asList(new TranscriptWord(0, 1, 0, 10),
                        new TranscriptWord(2, 3, 0, 10)));

        int[][] spans = TranscriptWordMapper.map(segment, "a and",
                new int[][] {{0, 5}}, new int[] {0});

        assertNotNull(spans);
        assertWord("a and", spans, 0, "a");
        assertEquals(-1, spans[0][1]);
        assertEquals(-1, spans[1][1]);
    }

    private static TranscriptSegment segment(String raw, String... tokens) {
        int cursor = 0;
        List<TranscriptWord> words = new ArrayList<>();
        for (String token : tokens) {
            int start = raw.indexOf(token, cursor);
            words.add(new TranscriptWord(start, start + token.length(), 0, 0));
            cursor = start + token.length();
        }
        return new TranscriptSegment(0, 10000, raw, "", words);
    }

    private static void assertWord(String display, int[][] spans, int index, String expected) {
        int start = spans[0][index];
        int end = spans[1][index];
        assertEquals(expected, display.substring(start, end));
    }
}
