package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;

public class ResamplerTest {
    @Test
    public void streamingMatchesBatchAcrossOddBlockSizes() {
        int sourceRate = 44100;
        int n = sourceRate * 3 + 137;
        float[] src = new float[n];
        Random r = new Random(7);
        for (int i = 0; i < n; i++) {
            src[i] = (float) (r.nextDouble() * 2 - 1);
        }
        float[] batch = Resampler.to8k(src.clone(), sourceRate);

        Resampler.Stream stream = new Resampler.Stream(sourceRate);
        Floats out = new Floats(n);
        int[] sizes = {977, 4000, 129, 8192};
        int off = 0;
        int k = 0;
        while (off < n) {
            int len = Math.min(sizes[k++ % sizes.length], n - off);
            out.add(stream.process(src, off, len));
            off += len;
        }
        out.add(stream.finish());

        assertArrayEquals(batch, out.toArray(), 1e-6f);
    }

    static final class Floats {
        private float[] a;
        private int n;

        Floats(int cap) {
            a = new float[cap];
        }

        void add(float[] values) {
            if (n + values.length > a.length) {
                float[] b = new float[Math.max(a.length * 2, n + values.length)];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            System.arraycopy(values, 0, a, n, values.length);
            n += values.length;
        }

        float[] toArray() {
            float[] r = new float[n];
            System.arraycopy(a, 0, r, 0, n);
            return r;
        }
    }
}
