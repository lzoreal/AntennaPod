package de.danoeh.antennapod.ad;

import java.util.Random;

/** Deterministic speech-like test signal: random-walk harmonics with block-wise envelopes. */
final class Synth {
    private static final int SR = 8000;
    private static final int BLOCK = 640;

    private Synth() {
    }

    static float[] speechLike(int seconds, long seed) {
        int n = seconds * SR;
        float[] out = new float[n];
        Random r = new Random(seed);
        int voices = 8;
        double[] freq = new double[voices];
        double[] amp = new double[voices];
        double[] phase = new double[voices];
        for (int k = 0; k < voices; k++) {
            freq[k] = 150 + r.nextDouble() * 2500;
            amp[k] = 0.04 + r.nextDouble() * 0.08;
            phase[k] = r.nextDouble() * Math.PI * 2;
        }
        int blocks = (n + BLOCK - 1) / BLOCK;
        for (int b = 0; b < blocks; b++) {
            for (int k = 0; k < voices; k++) {
                if (r.nextDouble() < 0.3) {
                    freq[k] *= 0.9 + 0.2 * r.nextDouble();
                    amp[k] = 0.03 + r.nextDouble() * 0.12;
                    phase[k] += r.nextDouble();
                }
            }
            int from = b * BLOCK;
            int to = Math.min(n, from + BLOCK);
            for (int i = from; i < to; i++) {
                double v = 0;
                for (int k = 0; k < voices; k++) {
                    v += amp[k] * Math.sin(2 * Math.PI * freq[k] * i / SR + phase[k]);
                }
                v += 0.01 * (r.nextDouble() * 2 - 1);
                out[i] = (float) v;
            }
        }
        return out;
    }

    static void insert(float[] dst, float[] src, int atSeconds) {
        int at = atSeconds * SR;
        System.arraycopy(src, 0, dst, at, Math.min(src.length, dst.length - at));
    }
}
