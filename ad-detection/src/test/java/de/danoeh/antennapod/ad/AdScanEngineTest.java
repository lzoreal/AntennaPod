package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdScanEngineTest {
    @Test
    public void prerollFoundForEveryEpisode() {
        float[] ad = Synth.speechLike(15, 3);
        float[] a = Synth.speechLike(60, 1);
        float[] b = Synth.speechLike(60, 2);
        Synth.insert(a, ad, 0);
        Synth.insert(b, ad, 0);
        List<AdScanEngine.EpisodeData> eps = Arrays.asList(
                new AdScanEngine.EpisodeData(1, 60, Fingerprinter.fingerprint(a)),
                new AdScanEngine.EpisodeData(2, 60, Fingerprinter.fingerprint(b)));
        AdScanEngine.Result r = new AdScanEngine().scan(eps);
        List<AdScanEngine.Segment> s1 = r.segments(1);
        assertEquals(1, s1.size());
        assertEquals(SegmentClassifier.KIND_PREROLL, s1.get(0).kind);
        assertEquals(SegmentClassifier.TIER_AUTO, s1.get(0).tier);
        assertTrue(s1.get(0).support >= 1);
        assertEquals(1, r.segments(2).size());
    }
}
