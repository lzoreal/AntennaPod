package de.danoeh.antennapod.model.feed;

import static org.junit.Assert.assertEquals;

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
}
