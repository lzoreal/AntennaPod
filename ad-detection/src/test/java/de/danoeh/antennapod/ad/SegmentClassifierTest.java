package de.danoeh.antennapod.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SegmentClassifierTest {
    private static SegmentMerger.MergedSegment seg(double s, double e, double ratio, int support) {
        return new SegmentMerger.MergedSegment(s, e, ratio, support);
    }

    @Test
    public void prerollWithSupportIsAuto() {
        SegmentClassifier.Classified c = SegmentClassifier.classify(seg(0.1, 31.5, 0.3, 2), 4000);
        assertEquals(SegmentClassifier.KIND_PREROLL, c.kind);
        assertEquals(SegmentClassifier.TIER_AUTO, c.tier);
    }

    @Test
    public void outroIsMarkedOnly() {
        SegmentClassifier.Classified c = SegmentClassifier.classify(seg(3980, 3999, 0.4, 2), 4000);
        assertEquals(SegmentClassifier.KIND_OUTRO, c.kind);
        assertEquals(SegmentClassifier.TIER_MARKED, c.tier);
    }

    @Test
    public void tooShortIsMarked() {
        SegmentClassifier.Classified c = SegmentClassifier.classify(seg(100, 106, 0.5, 2), 4000);
        assertEquals(SegmentClassifier.TIER_MARKED, c.tier);
    }

    @Test
    public void lowRatioIsMarked() {
        SegmentClassifier.Classified c = SegmentClassifier.classify(seg(100, 130, 0.04, 2), 4000);
        assertEquals(SegmentClassifier.TIER_MARKED, c.tier);
    }
}
