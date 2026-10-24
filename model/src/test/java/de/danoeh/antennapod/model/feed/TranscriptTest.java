package de.danoeh.antennapod.model.feed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TranscriptTest {
    @Test
    public void testAddSegmentKeepsChronologicalOrder() {
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(5000, 6000, "Second", ""));
        transcript.addSegment(new TranscriptSegment(1000, 2000, "First", ""));

        assertEquals(2, transcript.getSegmentCount());
        assertEquals("First", transcript.getSegmentAt(0).getWords());
        assertEquals("Second", transcript.getSegmentAt(1).getWords());
        assertEquals(0, transcript.findSegmentIndexBefore(3000));
        assertEquals(1, transcript.findSegmentIndexBefore(5500));
    }
}
