package de.danoeh.antennapod.storage.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TranscriptTimeOffsetsTest {
    @Test
    public void testOffsetIsPiecewiseAndDefaultsToZero() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        assertEquals(0, offsets.offsetAtMediaTime(0));
        assertEquals(0, offsets.offsetAtCueTime(0));

        offsets.setAnchor(10_000, 3_000);
        offsets.setAnchor(60_000, 5_000);

        assertEquals(0, offsets.offsetAtMediaTime(9_999));
        assertEquals(3_000, offsets.offsetAtMediaTime(10_000));
        assertEquals(3_000, offsets.offsetAtMediaTime(59_999));
        assertEquals(5_000, offsets.offsetAtMediaTime(60_000));

        assertEquals(0, offsets.offsetAtCueTime(6_999));
        assertEquals(3_000, offsets.offsetAtCueTime(7_000));
        assertEquals(5_000, offsets.offsetAtCueTime(55_000));
    }

    @Test
    public void testFirstLineAnchorCoversEpisodeStart() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        offsets.setAnchor(2_000, 2_000);

        assertEquals(2_000, offsets.offsetAtMediaTime(0));
        assertEquals(2_000, offsets.offsetAtCueTime(0));
    }

    @Test
    public void testResyncNearbyKeepsEarliestCoverage() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        offsets.setAnchor(10_000, 1_000);
        offsets.setAnchor(10_500, 2_000);

        assertEquals(1, offsets.getAnchors().size());
        assertEquals(10_000, offsets.getAnchors().get(0).mediaPositionMs);
        assertEquals(2_000, offsets.getAnchors().get(0).offsetMs);
        assertEquals(2_000, offsets.offsetAtMediaTime(10_000));
    }

    @Test
    public void testSegmentOffsetClampsBetweenNeighbours() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        offsets.setAnchor(10_000, 1_000);
        offsets.setAnchor(30_000, 4_000);

        offsets.setOffsetForSegment(30_000, 25_000);

        assertEquals(1_000, offsets.offsetAtCueTime(9_000));
        assertEquals(20_999, offsets.offsetAtCueTime(9_001));
        assertEquals(20_999, offsets.offsetAtMediaTime(30_000));

        offsets.setOffsetForSegment(10_000, -5_000);

        assertEquals(1_000, offsets.offsetAtMediaTime(10_000));
        assertEquals(9_001, offsets.getAnchors().get(1).cueTimeMs());
    }

    @Test
    public void testUnreachableAnchorIsDropped() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        offsets.setAnchor(10_000, 0);
        offsets.setAnchor(30_000, 25_000);

        assertEquals(1, offsets.getAnchors().size());
        assertEquals(0, offsets.offsetAtMediaTime(60_000));
    }

    @Test
    public void testAnchorsSharingCueTimeAreKept() {
        TranscriptTimeOffsets offsets = new TranscriptTimeOffsets();
        offsets.setAnchor(10_000, 1_000);
        offsets.setAnchor(11_500, 2_500);

        assertEquals(2, offsets.getAnchors().size());
        assertEquals(1_000, offsets.offsetAtMediaTime(10_500));
        assertEquals(2_500, offsets.offsetAtMediaTime(11_500));
    }
}
