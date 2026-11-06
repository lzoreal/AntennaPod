package de.danoeh.antennapod.ad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spectral-peak landmark fingerprinter (Wang-style hashes). */
public final class Fingerprinter {
    public static final int SAMPLE_RATE = 8000;
    public static final int FRAME = 512;
    public static final int HOP = 128;
    public static final double FPS = (double) SAMPLE_RATE / HOP;

    private static final int BIN_LO = 13;
    private static final int BIN_HI = 224;
    private static final int BIN_N = BIN_HI - BIN_LO + 1;
    private static final int PEAKS_PER_100MS = 4;
    private static final int FAN_OUT = 10;
    private static final int DT_MAX = 64;
    private static final int FREQ_SPAN = 32;
    private static final int PEAK_OVER_MEAN_DB10 = 60;

    public static final class Hashes {
        public final int[] hash;
        public final int[] frame;
        public final int frames;
        public final int peaks;

        public Hashes(int[] hash, int[] frame, int frames, int peaks) {
            this.hash = hash;
            this.frame = frame;
            this.frames = frames;
            this.peaks = peaks;
        }
    }

    private static final class Peak {
        final int frame;
        final int freqIdx;
        final int mag;

        Peak(int frame, int freqIdx, int mag) {
            this.frame = frame;
            this.freqIdx = freqIdx;
            this.mag = mag;
        }
    }

    private Fingerprinter() {
    }

    public static Hashes fingerprint(float[] pcm) {
        int frameCount = 1 + Math.max(0, (pcm.length - FRAME) / HOP);
        short[] lm = new short[frameCount * BIN_N];
        float[] frameMean = new float[frameCount];
        Fft fft = new Fft(FRAME);
        float[] re = new float[FRAME];
        float[] im = new float[FRAME];
        float[] win = new float[FRAME];
        for (int i = 0; i < FRAME; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
        }
        for (int f = 0; f < frameCount; f++) {
            int off = f * HOP;
            for (int i = 0; i < FRAME; i++) {
                re[i] = pcm[off + i] * win[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            double sum = 0;
            int base = f * BIN_N;
            for (int b = 0; b < BIN_N; b++) {
                int bin = BIN_LO + b;
                double mag = Math.sqrt(re[bin] * re[bin] + im[bin] * im[bin]);
                short q = (short) Math.round(20 * Math.log10(mag + 1e-6) * 10);
                lm[base + b] = q;
                sum += q;
            }
            frameMean[f] = (float) (sum / BIN_N);
        }

        List<Peak> candidates = new ArrayList<>();
        for (int f = 2; f < frameCount - 2; f++) {
            int base = f * BIN_N;
            for (int b = 1; b < BIN_N - 1; b++) {
                int v = lm[base + b];
                if (v < frameMean[f] + PEAK_OVER_MEAN_DB10
                        || v <= lm[base + b - 1] || v < lm[base + b + 1]
                        || v < lm[base - BIN_N + b] || v < lm[base + BIN_N + b]
                        || v <= lm[base - 2 * BIN_N + b] || v <= lm[base + 2 * BIN_N + b]) {
                    continue;
                }
                candidates.add(new Peak(f, BIN_LO + b, v));
            }
        }

        candidates.sort(Comparator.comparingInt((Peak p) -> p.frame / 6)
                .thenComparing(Comparator.comparingInt((Peak p) -> p.mag).reversed()));
        List<Peak> peaks = new ArrayList<>(candidates.size());
        int bucket = -1;
        int taken = 0;
        for (Peak p : candidates) {
            int b = p.frame / 6;
            if (b != bucket) {
                bucket = b;
                taken = 0;
            }
            if (taken < PEAKS_PER_100MS) {
                peaks.add(p);
                taken++;
            }
        }
        peaks.sort(Comparator.comparingInt(p -> p.frame));

        IntList hs = new IntList(peaks.size() * 4);
        IntList ts = new IntList(peaks.size() * 4);
        for (int i = 0; i < peaks.size(); i++) {
            Peak a = peaks.get(i);
            int used = 0;
            for (int j = i + 1; j < peaks.size() && used < FAN_OUT; j++) {
                Peak t = peaks.get(j);
                int dt = t.frame - a.frame;
                if (dt > DT_MAX) {
                    break;
                }
                if (dt <= 0 || Math.abs(t.freqIdx - a.freqIdx) > FREQ_SPAN) {
                    continue;
                }
                hs.add((a.freqIdx << 14) | (t.freqIdx << 6) | (dt - 1));
                ts.add(a.frame);
                used++;
            }
        }
        return new Hashes(hs.toArray(), ts.toArray(), frameCount, peaks.size());
    }

    static final class IntList {
        private int[] values;
        private int size;

        IntList(int cap) {
            values = new int[Math.max(8, cap)];
        }

        void add(int v) {
            if (size == values.length) {
                int[] b = new int[values.length * 2];
                System.arraycopy(values, 0, b, 0, size);
                values = b;
            }
            values[size++] = v;
        }

        int[] toArray() {
            int[] r = new int[size];
            System.arraycopy(values, 0, r, 0, size);
            return r;
        }
    }
}
