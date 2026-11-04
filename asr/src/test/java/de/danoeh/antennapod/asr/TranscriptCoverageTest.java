package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TranscriptCoverageTest {
    @Test
    public void overlappingAndAdjacentRangesMerge() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(0, 10));
        coverage.add(new MediaRange(10, 20));
        coverage.add(new MediaRange(15, 30));

        assertEquals(Arrays.asList(new MediaRange(0, 30)), coverage.ranges());
    }

    @Test
    public void disjointRangesStaySorted() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(50, 60));
        coverage.add(new MediaRange(10, 20));

        assertEquals(Arrays.asList(new MediaRange(10, 20), new MediaRange(50, 60)),
                coverage.ranges());
    }

    @Test
    public void coversNeedsTheWholeInterval() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(10, 20));

        assertTrue(coverage.covers(12, 18));
        assertFalse(coverage.covers(5, 15));
    }

    @Test
    public void nextUncoveredSkipsCoveredPrefix() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(10, 20));

        assertEquals(20, coverage.nextUncovered(10, 30));
        assertEquals(5, coverage.nextUncovered(5, 15));
        assertEquals(-1, coverage.nextUncovered(12, 18));
    }

    @Test
    public void gapsListWhatIsStillMissing() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(10, 20));
        coverage.add(new MediaRange(30, 40));

        assertEquals(Arrays.asList(new MediaRange(0, 10), new MediaRange(20, 30),
                        new MediaRange(40, 50)),
                coverage.gaps(0, 50));
    }

    @Test
    public void gapsAreEmptyWhenFullyCovered() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(0, 100));
        assertTrue(coverage.gaps(0, 100).isEmpty());
    }
}
