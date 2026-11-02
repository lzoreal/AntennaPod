package de.danoeh.antennapod.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FftTest {
    @Test
    public void sinePeaksAtExpectedBin() {
        int n = 512;
        float[] re = new float[n];
        float[] im = new float[n];
        for (int i = 0; i < n; i++) {
            re[i] = (float) Math.sin(2 * Math.PI * 64 * i / n);
        }
        new Fft(n).transform(re, im);
        int best = 1;
        double bestMag = -1;
        for (int i = 1; i < n / 2; i++) {
            double m = Math.hypot(re[i], im[i]);
            if (m > bestMag) {
                bestMag = m;
                best = i;
            }
        }
        assertEquals(64, best);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPowerOfTwo() {
        new Fft(500);
    }
}
