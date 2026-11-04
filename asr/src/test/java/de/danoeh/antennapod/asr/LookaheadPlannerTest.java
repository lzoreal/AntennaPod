package de.danoeh.antennapod.asr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class LookaheadPlannerTest {
    private static final long WINDOW = 30000;
    private static final long CUSHION = 5000;

    private final TranscriptCoverage coverage = new TranscriptCoverage();
    private final LookaheadPlanner planner = new LookaheadPlanner(WINDOW, CUSHION);

    @Test
    public void firstPlanStartsBeforeThePlayhead() {
        LookaheadPlanner.Plan plan = planner.next(10000, -1, true, coverage, -1);

        assertNotNull(plan);
        assertEquals(5000, plan.startMs());
        assertEquals(40000, plan.endMs());
    }

    @Test
    public void firstPlanDoesNotGoBelowZero() {
        LookaheadPlanner.Plan plan = planner.next(2000, -1, true, coverage, -1);

        assertNotNull(plan);
        assertEquals(0, plan.startMs());
        assertEquals(32000, plan.endMs());
    }

    @Test
    public void caughtUpWindowReturnsNoPlan() {
        coverage.add(new MediaRange(0, 40000));

        assertNull(planner.next(10000, 40000, false, coverage, -1));
    }

    @Test
    public void planSkipsTheCoveredPrefix() {
        coverage.add(new MediaRange(0, 20000));

        LookaheadPlanner.Plan plan = planner.next(15000, 20000, false, coverage, -1);

        assertNotNull(plan);
        assertEquals(20000, plan.startMs());
        assertEquals(45000, plan.endMs());
    }

    @Test
    public void seekIgnoresTheOldDecodedEnd() {
        LookaheadPlanner.Plan plan = planner.next(100000, 50000, true, coverage, -1);

        assertNotNull(plan);
        assertEquals(95000, plan.startMs());
        assertEquals(130000, plan.endMs());
    }

    @Test
    public void durationClampsTheWindow() {
        LookaheadPlanner.Plan plan = planner.next(40000, 40000, false, coverage, 50000);

        assertNotNull(plan);
        assertEquals(40000, plan.startMs());
        assertEquals(50000, plan.endMs());
    }

    @Test
    public void endOfMediaReturnsNoPlan() {
        assertNull(planner.next(50000, 50000, false, coverage, 50000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveWindowIsRejected() {
        new LookaheadPlanner(0, 0);
    }
}
