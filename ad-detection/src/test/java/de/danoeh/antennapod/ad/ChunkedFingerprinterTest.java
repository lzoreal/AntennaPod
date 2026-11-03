package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChunkedFingerprinterTest {
    private static Set<Long> pairs(Fingerprinter.Hashes h) {
        Set<Long> set = new HashSet<>();
        for (int i = 0; i < h.hash.length; i++) {
            set.add(((long) h.hash[i] << 32) | (h.frame[i] & 0xffffffffL));
        }
        return set;
    }

    private static Fingerprinter.Hashes chunked(float[] pcm, int chunkFrames) {
        ChunkedFingerprinter cf = new ChunkedFingerprinter(chunkFrames);
        int step = 977;
        for (int off = 0; off < pcm.length; off += step) {
            cf.add(pcm, off, Math.min(step, pcm.length - off));
        }
        return cf.finish();
    }

    @Test
    public void chunkedHashesMatchBatch() {
        float[] pcm = Synth.speechLike(20, 5);
        Fingerprinter.Hashes batch = Fingerprinter.fingerprint(pcm);
        Set<Long> expected = pairs(batch);

        Fingerprinter.Hashes actual = chunked(pcm, 252);
        assertTrue("no hashes produced", actual.hash.length > 1000);
        int matched = 0;
        for (Long pair : pairs(actual)) {
            if (expected.contains(pair)) {
                matched++;
            }
        }
        double fraction = matched / (double) pairs(actual).size();
        assertTrue("matched fraction=" + fraction, fraction >= 0.95);
    }

    @Test
    public void insertedCopyStillFoundAcrossChunkBoundaries() {
        float[] ad = Synth.speechLike(15, 3);
        float[] a = Synth.speechLike(60, 1);
        float[] b = Synth.speechLike(60, 2);
        Synth.insert(a, ad, 40);
        Synth.insert(b, ad, 20);

        Fingerprinter.Hashes ha = chunked(a, 252);
        Fingerprinter.Hashes hb = chunked(b, 252);
        Index index = Index.build(List.of(ha), 8);
        List<Matcher.Detection> dets = Matcher.detect(index, 1, new int[]{ha.frames}, hb, 8);
        List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(dets);

        assertEquals(1, merged.size());
        SegmentMerger.MergedSegment m = merged.get(0);
        assertTrue("start=" + m.startSec, Math.abs(m.startSec - 20) < 2.0);
        assertTrue("end=" + m.endSec, Math.abs(m.endSec - 35) < 2.0);
    }
}
