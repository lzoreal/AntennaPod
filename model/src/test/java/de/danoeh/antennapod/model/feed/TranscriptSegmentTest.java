package de.danoeh.antennapod.model.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class TranscriptSegmentTest {
    @Test
    public void testAppendKeepsLatestEndTime() {
        TranscriptSegment segment = new TranscriptSegment(1000, 5000, "First", "");
        segment.append(4000, "Second");

        assertEquals("First Second", segment.getWords());
        assertEquals(5000, segment.getEndTime());

        segment.append(6000, "Third");

        assertEquals(6000, segment.getEndTime());
    }

    @Test
    public void testWordIndexAtFindsLastStartedWord() {
        List<TranscriptWord> words = Arrays.asList(
                new TranscriptWord(0, 3, 1000, 1200),
                new TranscriptWord(4, 7, 1500, 1700));
        TranscriptSegment segment = new TranscriptSegment(1000, 2000, "one two", "", words);

        assertTrue(segment.hasWordTimings());
        assertEquals(-1, segment.getWordIndexAt(999));
        assertEquals(0, segment.getWordIndexAt(1000));
        assertEquals(0, segment.getWordIndexAt(1400));
        assertEquals(1, segment.getWordIndexAt(1500));
        assertEquals(1, segment.getWordIndexAt(5000));
    }

    @Test
    public void testAppendDiscardsWordTimings() {
        List<TranscriptWord> words = Arrays.asList(
                new TranscriptWord(0, 3, 1000, 1200));
        TranscriptSegment segment = new TranscriptSegment(1000, 2000, "one", "", words);

        segment.append(3000, "two");

        assertFalse(segment.hasWordTimings());
        assertEquals(-1, segment.getWordIndexAt(1500));
    }
}
