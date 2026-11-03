package de.danoeh.antennapod.ad;

import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HeadScanEngineTest {
    private static AdFingerprintRow row(long mediaId, float[] pcm) {
        Fingerprinter.Hashes hashes = Fingerprinter.fingerprint(pcm);
        AdFingerprintRow row = new AdFingerprintRow();
        row.setMediaId(mediaId);
        row.setAlgoVersion(FingerprintCodec.ALGO_VERSION);
        row.setStride(4);
        row.setFrames(hashes.frames);
        row.setBlob(FingerprintCodec.encode(hashes, 4, Fingerprinter.SAMPLE_RATE));
        return row;
    }

    @Test
    public void detectsPrerollInHeadAgainstStoredEpisode() {
        float[] ad = Synth.speechLike(15, 3);
        float[] reference = Synth.speechLike(300, 1);
        Synth.insert(reference, ad, 40);
        float[] head = Synth.speechLike(180, 2);
        Synth.insert(head, ad, 10);

        List<AdScanEngine.Segment> segments = HeadScanEngine.scan(
                Fingerprinter.fingerprint(head), 3000, List.of(row(11127, reference)));

        assertEquals(1, segments.size());
        AdScanEngine.Segment s = segments.get(0);
        assertTrue("start=" + s.startSec, Math.abs(s.startSec - 10) < 2.0);
        assertTrue("end=" + s.endSec, Math.abs(s.endSec - 25) < 2.0);
        assertEquals(AdSegment.KIND_PREROLL, s.kind);
    }

    @Test
    public void emptyReferenceReturnsNothing() {
        float[] head = Synth.speechLike(180, 2);
        assertTrue(HeadScanEngine.scan(Fingerprinter.fingerprint(head), 3000, List.of()).isEmpty());
    }
}
