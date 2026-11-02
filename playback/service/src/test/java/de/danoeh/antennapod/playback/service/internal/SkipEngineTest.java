package de.danoeh.antennapod.playback.service.internal;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SkipEngineTest {
    @Test
    public void skipsOnEntryAndNotRepeatedly() {
        SkipEngine engine = new SkipEngine();
        engine.setSegments(List.of(new long[]{7, 10_000, 40_000}));
        assertNull(engine.onPosition(9_900));
        assertEquals(Long.valueOf(40_250), engine.onPosition(10_100));
        assertNull(engine.onPosition(40_100));
        assertNull(engine.onPosition(5_000));
    }

    @Test
    public void userSeekIntoRangeIsBypassed() {
        SkipEngine engine = new SkipEngine();
        engine.setSegments(List.of(new long[]{7, 10_000, 40_000}));
        engine.onUserSeek(25_000);
        assertNull(engine.onPosition(25_100));
        assertNull(engine.onPosition(30_000));
    }

    @Test
    public void disabledSegmentIsIgnored() {
        SkipEngine engine = new SkipEngine();
        engine.setSegments(List.of(new long[]{7, 10_000, 40_000}));
        engine.disable(7);
        assertNull(engine.onPosition(9_900));
        assertNull(engine.onPosition(10_100));
    }
}
