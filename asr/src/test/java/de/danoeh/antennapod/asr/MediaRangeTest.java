package de.danoeh.antennapod.asr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaRangeTest {
    @Test
    public void containsIsHalfOpen() {
        MediaRange range = new MediaRange(10, 20);
        assertTrue(range.contains(10));
        assertTrue(range.contains(19));
        assertFalse(range.contains(20));
    }

    @Test
    public void touchingRangesShareTheirBoundary() {
        assertTrue(new MediaRange(0, 10).touches(new MediaRange(10, 20)));
        assertFalse(new MediaRange(0, 10).touches(new MediaRange(11, 20)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyRangeIsRejected() {
        new MediaRange(10, 10);
    }

    @Test
    public void equalityUsesBothBounds() {
        assertEquals(new MediaRange(1, 2), new MediaRange(1, 2));
        assertFalse(new MediaRange(1, 2).equals(new MediaRange(1, 3)));
    }
}
