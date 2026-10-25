package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TranscriptFollowMotionTest {

    @Test
    public void anchorIsAt45PercentOfViewport() {
        assertEquals(900, TranscriptFollowMotion.anchorY(2000));
        assertEquals(0, TranscriptFollowMotion.anchorY(0));
    }

    @Test
    public void errorIsMeasuredAgainstTheAnchor() {
        assertEquals(-100, TranscriptFollowMotion.errorPx(800, 2000));
        assertEquals(100, TranscriptFollowMotion.errorPx(1000, 2000));
    }

    @Test
    public void stopThresholdIsTwoDp() {
        assertTrue(TranscriptFollowMotion.isWithinStopThreshold(4, 2f));
        assertFalse(TranscriptFollowMotion.isWithinStopThreshold(5, 2f));
        assertTrue(TranscriptFollowMotion.isWithinStopThreshold(-4, 2f));
    }

    @Test
    public void startThresholdIsEightDp() {
        assertFalse(TranscriptFollowMotion.shouldStart(23, 3f));
        assertTrue(TranscriptFollowMotion.shouldStart(24, 3f));
        assertTrue(TranscriptFollowMotion.shouldStart(-24, 3f));
    }

    @Test
    public void snapThresholdIsThreeQuartersOfViewport() {
        assertFalse(TranscriptFollowMotion.shouldSnap(1500, 2000));
        assertTrue(TranscriptFollowMotion.shouldSnap(1501, 2000));
        assertTrue(TranscriptFollowMotion.shouldSnap(-1501, 2000));
    }

    @Test
    public void durationGrowsWithDistanceAndIsClamped() {
        assertEquals(320L, TranscriptFollowMotion.durationFor(10, 2f));
        assertEquals(360L, TranscriptFollowMotion.durationFor(80, 2f));
        assertEquals(460L, TranscriptFollowMotion.durationFor(160, 2f));
        assertEquals(520L, TranscriptFollowMotion.durationFor(400, 2f));
        assertEquals(520L, TranscriptFollowMotion.durationFor(-400, 2f));
    }

    @Test
    public void progressUsesCubicEaseOut() {
        assertEquals(0f, TranscriptFollowMotion.easedProgress(0, 240), 0.0001f);
        assertEquals(0.875f, TranscriptFollowMotion.easedProgress(120, 240), 0.0001f);
        assertEquals(1f, TranscriptFollowMotion.easedProgress(240, 240), 0.0001f);
        assertEquals(1f, TranscriptFollowMotion.easedProgress(300, 240), 0.0001f);
    }

    @Test
    public void scrollDeltaFollowsEaseOutAndKeepsDirection() {
        assertEquals(0, TranscriptFollowMotion.nextScrollDelta(400, 0, 240, 0));
        assertEquals(350, TranscriptFollowMotion.nextScrollDelta(400, 120, 240, 0));
        assertEquals(50, TranscriptFollowMotion.nextScrollDelta(400, 240, 240, 350));
        assertEquals(-350, TranscriptFollowMotion.nextScrollDelta(-400, 120, 240, 0));
    }
}
