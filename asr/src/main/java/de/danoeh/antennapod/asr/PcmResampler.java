package de.danoeh.antennapod.asr;

/**
 * Streaming mono resampler with a 4th-order Butterworth low-pass at 7.2 kHz
 * followed by linear interpolation. The output is always 16 kHz.
 */
public final class PcmResampler {
    private static final double LOW_PASS_HZ = 7200.0;
    private static final double Q1 = 0.541196100146197;
    private static final double Q2 = 1.306562964876377;

    private final int inputSampleRateHz;
    private final double ratio;
    private final boolean identity;
    private final Biquad lowPass1;
    private final Biquad lowPass2;

    private float[] filtered = new float[0];
    private double position;
    private long inputIndex;
    private float lastSample;
    private long lastSampleIndex = -1;
    private boolean hasLastSample;

    public PcmResampler(int inputSampleRateHz) {
        if (inputSampleRateHz <= 0) {
            throw new IllegalArgumentException("inputSampleRateHz must be positive: " + inputSampleRateHz);
        }
        this.inputSampleRateHz = inputSampleRateHz;
        this.ratio = (double) inputSampleRateHz / PcmChunk.SAMPLE_RATE_HZ;
        this.identity = inputSampleRateHz == PcmChunk.SAMPLE_RATE_HZ;
        if (identity) {
            this.lowPass1 = null;
            this.lowPass2 = null;
        } else {
            boolean filter = inputSampleRateHz > PcmChunk.SAMPLE_RATE_HZ;
            this.lowPass1 = filter ? new Biquad(LOW_PASS_HZ, inputSampleRateHz, Q1) : null;
            this.lowPass2 = filter ? new Biquad(LOW_PASS_HZ, inputSampleRateHz, Q2) : null;
        }
    }

    public int outputSampleRateHz() {
        return PcmChunk.SAMPLE_RATE_HZ;
    }

    /**
     * Writes at most {@code ceil(count / ratio) + 1} samples and returns how many were written.
     */
    public int process(float[] input, int offset, int count, float[] output) {
        if (count == 0) {
            return 0;
        }
        if (offset < 0 || count < 0 || offset + count > input.length) {
            throw new IllegalArgumentException("invalid input window");
        }
        if (identity) {
            System.arraycopy(input, offset, output, 0, count);
            lastSample = input[offset + count - 1];
            lastSampleIndex = inputIndex + count - 1;
            hasLastSample = true;
            inputIndex += count;
            return count;
        }

        ensureFilteredCapacity(count);
        for (int i = 0; i < count; i++) {
            float sample = input[offset + i];
            if (lowPass1 != null) {
                sample = lowPass2.process(lowPass1.process(sample));
            }
            filtered[i] = sample;
        }

        int outCount = 0;
        while (true) {
            long base = (long) Math.floor(position);
            double fraction = position - base;
            if (hasLastSample && base == lastSampleIndex && count > 0) {
                output[outCount++] = (float) (lastSample * (1.0 - fraction) + filtered[0] * fraction);
                position += ratio;
                continue;
            }
            long currentEnd = inputIndex + count;
            if (base + 1 < currentEnd) {
                int index = (int) (base - inputIndex);
                output[outCount++] = (float) (filtered[index] * (1.0 - fraction)
                        + filtered[index + 1] * fraction);
                position += ratio;
                continue;
            }
            if (base < inputIndex) {
                position = inputIndex;
                continue;
            }
            break;
        }

        lastSample = filtered[count - 1];
        lastSampleIndex = inputIndex + count - 1;
        hasLastSample = true;
        inputIndex += count;
        return outCount;
    }

    public void reset() {
        position = 0;
        inputIndex = 0;
        lastSample = 0;
        lastSampleIndex = -1;
        hasLastSample = false;
        if (lowPass1 != null) {
            lowPass1.reset();
            lowPass2.reset();
        }
    }

    private void ensureFilteredCapacity(int count) {
        if (filtered.length < count) {
            filtered = new float[count];
        }
    }

    private static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        Biquad(double cutoffHz, int sampleRateHz, double q) {
            double omega = 2.0 * Math.PI * cutoffHz / sampleRateHz;
            double alpha = Math.sin(omega) / (2.0 * q);
            double cos = Math.cos(omega);
            double a0 = 1.0 + alpha;
            b0 = (1.0 - cos) / 2.0 / a0;
            b1 = (1.0 - cos) / a0;
            b2 = b0;
            a1 = -2.0 * cos / a0;
            a2 = (1.0 - alpha) / a0;
        }

        float process(float sample) {
            final double y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            y2 = y1;
            y1 = y;
            x2 = x1;
            x1 = sample;
            return (float) y;
        }

        void reset() {
            x1 = 0;
            x2 = 0;
            y1 = 0;
            y2 = 0;
        }
    }
}
