package de.danoeh.antennapod.playback.service.internal;

import de.danoeh.antennapod.model.feed.AdSegment;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdSkipControllerTest {
    @Test
    public void normalModeSkipsOnlyAutoSegments() {
        assertTrue(AdSkipController.isSkippable(segment(AdSegment.KIND_PREROLL, AdSegment.TIER_AUTO), false));
        assertFalse(AdSkipController.isSkippable(segment(AdSegment.KIND_MIDROLL, AdSegment.TIER_MARKED), false));
    }

    @Test
    public void aggressiveModeAlsoSkipsMarkedSegments() {
        assertTrue(AdSkipController.isSkippable(segment(AdSegment.KIND_MIDROLL, AdSegment.TIER_MARKED), true));
        assertTrue(AdSkipController.isSkippable(segment(AdSegment.KIND_OUTRO, AdSegment.TIER_MARKED), true));
    }

    @Test
    public void disabledSegmentIsNeverSkipped() {
        AdSegment segment = segment(AdSegment.KIND_PREROLL, AdSegment.TIER_AUTO);
        segment.setState(AdSegment.STATE_DISABLED);
        assertFalse(AdSkipController.isSkippable(segment, true));
    }

    private static AdSegment segment(int kind, int tier) {
        AdSegment segment = new AdSegment();
        segment.setKind(kind);
        segment.setTier(tier);
        segment.setState(AdSegment.STATE_ACTIVE);
        return segment;
    }
}
