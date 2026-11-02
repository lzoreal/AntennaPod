package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MatcherTest {
    private static Fingerprinter.Hashes fp(float[] pcm) {
        return Fingerprinter.fingerprint(pcm);
    }

    @Test
    public void findsInsertedCopyAcrossEpisodes() {
        float[] ad = Synth.speechLike(15, 3);
        float[] a = Synth.speechLike(60, 1);
        float[] b = Synth.speechLike(60, 2);
        Synth.insert(a, ad, 40);
        Synth.insert(b, ad, 20);

        Fingerprinter.Hashes ha = fp(a);
        Fingerprinter.Hashes hb = fp(b);
        Index index = Index.build(List.of(ha), 8);
        List<Matcher.Detection> dets = Matcher.detect(index, 1, new int[]{ha.frames}, hb, 8);
        List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(dets);

        assertEquals(1, merged.size());
        SegmentMerger.MergedSegment m = merged.get(0);
        assertTrue("start=" + m.startSec, Math.abs(m.startSec - 20) < 2.0);
        assertTrue("end=" + m.endSec, Math.abs(m.endSec - 35) < 2.0);
        assertTrue("ratio=" + m.matchRatio, m.matchRatio >= 0.05);
        assertEquals(1, m.support);
    }

    @Test
    public void noMatchForDifferentContent() {
        Fingerprinter.Hashes ha = fp(Synth.speechLike(60, 11));
        Fingerprinter.Hashes hb = fp(Synth.speechLike(60, 12));
        Index index = Index.build(List.of(ha), 8);
        assertTrue(Matcher.detect(index, 1, new int[]{ha.frames}, hb, 8).isEmpty());
    }
}
