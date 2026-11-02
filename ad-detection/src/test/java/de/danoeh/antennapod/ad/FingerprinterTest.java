package de.danoeh.antennapod.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FingerprinterTest {
    @Test
    public void deterministic() {
        float[] pcm = Synth.speechLike(20, 7);
        Fingerprinter.Hashes a = Fingerprinter.fingerprint(pcm);
        Fingerprinter.Hashes b = Fingerprinter.fingerprint(pcm);
        assertEquals(a.hash.length, b.hash.length);
        for (int i = 0; i < a.hash.length; i++) {
            assertEquals(a.hash[i], b.hash[i]);
            assertEquals(a.frame[i], b.frame[i]);
        }
    }

    @Test
    public void peakDensityIsSane() {
        float[] pcm = Synth.speechLike(60, 3);
        Fingerprinter.Hashes h = Fingerprinter.fingerprint(pcm);
        double peaksPerSecond = h.peaks / 60.0;
        assertTrue("peaks/s=" + peaksPerSecond, peaksPerSecond > 10 && peaksPerSecond < 60);
        assertTrue("hashes/s=" + (h.hash.length / 60.0), h.hash.length / 60.0 > 50);
    }
}
