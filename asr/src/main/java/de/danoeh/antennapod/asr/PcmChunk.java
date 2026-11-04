package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.Arrays;

/** A block of mono 16 kHz PCM together with the media time of its first sample. */
public final class PcmChunk {
    public static final int SAMPLE_RATE_HZ = 16000;

    private final float[] samples;
    private final long startTimeMs;

    public PcmChunk(@NonNull float[] samples, int length, long startTimeMs) {
        if (samples == null) {
            throw new IllegalArgumentException("samples is null");
        }
        if (length < 0 || length > samples.length) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
        if (startTimeMs < 0) {
            throw new IllegalArgumentException("startTimeMs is negative: " + startTimeMs);
        }
        this.samples = Arrays.copyOf(samples, length);
        this.startTimeMs = startTimeMs;
    }

    public int length() {
        return samples.length;
    }

    public long startTimeMs() {
        return startTimeMs;
    }

    public long endTimeMs() {
        return startTimeMs + (samples.length * 1000L + SAMPLE_RATE_HZ / 2) / SAMPLE_RATE_HZ;
    }

    public int sampleRateHz() {
        return SAMPLE_RATE_HZ;
    }

    /** Returns a copy so callers cannot modify the chunk's samples. */
    @NonNull
    public float[] toFloatArray() {
        return samples.clone();
    }
}
