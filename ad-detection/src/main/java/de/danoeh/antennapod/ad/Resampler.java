package de.danoeh.antennapod.ad;

/** 4th-order low-pass + linear interpolation down to 8 kHz. */
public final class Resampler {
    private static final int TARGET = Fingerprinter.SAMPLE_RATE;

    private Resampler() {
    }

    public static float[] to8k(float[] pcm, int sourceRate) {
        if (sourceRate == TARGET) {
            return pcm;
        }
        lowPass(pcm, sourceRate, 3600);
        int outLen = (int) ((long) pcm.length * TARGET / sourceRate);
        float[] out = new float[outLen];
        double step = (double) sourceRate / TARGET;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int idx = (int) pos;
            double frac = pos - idx;
            float a = pcm[Math.min(idx, pcm.length - 1)];
            float b = pcm[Math.min(idx + 1, pcm.length - 1)];
            out[i] = (float) (a + (b - a) * frac);
        }
        return out;
    }

    /** Two cascaded one-pole sections (12 dB/oct), sufficient for landmark peaks. */
    private static void lowPass(float[] pcm, int sampleRate, double cutoff) {
        double alpha = 1 - Math.exp(-2 * Math.PI * cutoff / sampleRate);
        for (int pass = 0; pass < 2; pass++) {
            float prev = 0;
            for (int i = 0; i < pcm.length; i++) {
                prev += (float) (alpha * (pcm[i] - prev));
                pcm[i] = prev;
            }
        }
    }
}
