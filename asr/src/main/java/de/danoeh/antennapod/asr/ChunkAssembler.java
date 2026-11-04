package de.danoeh.antennapod.asr;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/** Collects 16 kHz mono samples into fixed-size chunks carrying their media start time. */
public final class ChunkAssembler {
    private final int chunkSamples;
    private final long chunkDurationMs;
    private final float[] buffer;
    private final Deque<PcmChunk> ready = new ArrayDeque<>();

    private int filled;
    private long chunkStartMs;

    public ChunkAssembler(int chunkSamples) {
        if (chunkSamples <= 0) {
            throw new IllegalArgumentException("chunkSamples must be positive: " + chunkSamples);
        }
        this.chunkSamples = chunkSamples;
        this.chunkDurationMs = chunkSamples * 1000L / PcmChunk.SAMPLE_RATE_HZ;
        this.buffer = new float[chunkSamples];
    }

    public void append(float[] samples, int offset, int count, long startTimeMs) {
        if (offset < 0 || count < 0 || offset + count > samples.length) {
            throw new IllegalArgumentException("invalid samples window");
        }
        if (startTimeMs < 0) {
            throw new IllegalArgumentException("startTimeMs is negative: " + startTimeMs);
        }
        if (count == 0) {
            return;
        }
        if (filled == 0) {
            chunkStartMs = startTimeMs;
        }
        while (count > 0) {
            int copied = Math.min(count, chunkSamples - filled);
            System.arraycopy(samples, offset, buffer, filled, copied);
            filled += copied;
            offset += copied;
            count -= copied;
            if (filled == chunkSamples) {
                ready.add(new PcmChunk(buffer, chunkSamples, chunkStartMs));
                filled = 0;
                chunkStartMs += chunkDurationMs;
            }
        }
    }

    @Nullable
    public PcmChunk poll() {
        return ready.poll();
    }

    @Nullable
    public PcmChunk flush() {
        if (filled == 0) {
            return null;
        }
        PcmChunk chunk = new PcmChunk(buffer, filled, chunkStartMs);
        filled = 0;
        return chunk;
    }

    public void reset() {
        ready.clear();
        filled = 0;
        chunkStartMs = 0;
    }

    public boolean hasPendingSamples() {
        return filled > 0 || !ready.isEmpty();
    }
}
