package de.danoeh.antennapod.ad;

import java.util.Arrays;

/** 4th-order low-pass + linear interpolation down to 8 kHz. */
public final class Resampler {
    private static final int TARGET = Fingerprinter.SAMPLE_RATE;

    private Resampler() {
    }

    /** Block-wise variant so a full episode never needs to be held in memory. */
    public static final class Stream {
        private final int sourceRate;
        private final double step;
        private final boolean passthrough;
        private final double alpha;
        private float lp1;
        private float lp2;
        private long inCount;
        private long outCount;
        private float prev;

        public Stream(int sourceRate) {
            this.sourceRate = sourceRate;
            this.step = (double) sourceRate / TARGET;
            this.passthrough = sourceRate == TARGET;
            this.alpha = 1 - Math.exp(-2 * Math.PI * 3600.0 / sourceRate);
        }

        public float[] process(float[] pcm, int offset, int length) {
            for (int i = 0; i < length; i++) {
                float x = pcm[offset + i];
                if (!passthrough) {
                    lp1 += (float) (alpha * (x - lp1));
                    lp2 += (float) (alpha * (lp1 - lp2));
                    x = lp2;
                }
                pcm[offset + i] = x;
            }
            float[] out = new float[(int) Math.ceil(length * step) + 1];
            int n = 0;
            long total = inCount + length;
            while (true) {
                double pos = (double) outCount * step;
                long idx = (long) pos;
                if (idx + 1 >= total) {
                    break;
                }
                float a = sample(idx, pcm, offset);
                float b = sample(idx + 1, pcm, offset);
                out[n++] = (float) (a + (b - a) * (pos - idx));
                outCount++;
            }
            if (length > 0) {
                prev = pcm[offset + length - 1];
            }
            inCount = total;
            return Arrays.copyOf(out, n);
        }

        /** Flushes the samples whose right neighbour only exists at end-of-stream. */
        public float[] finish() {
            float[] out = new float[2];
            int n = 0;
            while ((double) outCount * step < inCount) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, n * 2);
                }
                out[n++] = prev;
                outCount++;
            }
            return Arrays.copyOf(out, n);
        }

        private float sample(long idx, float[] pcm, int offset) {
            if (idx < inCount) {
                return prev;
            }
            return pcm[offset + (int) (idx - inCount)];
        }
    }

    public static float[] to8k(float[] pcm, int sourceRate) {
        Stream stream = new Stream(sourceRate);
        float[] head = stream.process(pcm, 0, pcm.length);
        float[] tail = stream.finish();
        float[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
