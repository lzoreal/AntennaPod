package de.danoeh.antennapod.asr;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ChunkAssemblerTest {
    @Test
    public void fullChunksCarryTheirMediaStartTimes() {
        ChunkAssembler assembler = new ChunkAssembler(1600);
        float[] samples = new float[5600];

        assembler.append(samples, 0, samples.length, 250);

        PcmChunk first = assembler.poll();
        PcmChunk second = assembler.poll();
        PcmChunk third = assembler.poll();
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertEquals(250, first.startTimeMs());
        assertEquals(350, second.startTimeMs());
        assertEquals(450, third.startTimeMs());
        assertNull(assembler.poll());

        PcmChunk tail = assembler.flush();
        assertNotNull(tail);
        assertEquals(800, tail.length());
        assertEquals(550, tail.startTimeMs());
    }

    @Test
    public void samplesAccumulateAcrossAppends() {
        ChunkAssembler assembler = new ChunkAssembler(1600);
        float[] half = new float[800];

        assembler.append(half, 0, half.length, 1000);
        assertNull(assembler.poll());
        assembler.append(half, 0, half.length, 1080);

        PcmChunk chunk = assembler.poll();
        assertNotNull(chunk);
        assertEquals(1600, chunk.length());
        assertEquals(1000, chunk.startTimeMs());
    }

    @Test
    public void resetDropsPendingSamples() {
        ChunkAssembler assembler = new ChunkAssembler(1600);
        assembler.append(new float[1200], 0, 1200, 0);

        assembler.reset();

        assertNull(assembler.flush());
        assertFalse(assembler.hasPendingSamples());
    }

    @Test
    public void chunkSamplesAreCopied() {
        ChunkAssembler assembler = new ChunkAssembler(2);
        float[] samples = {0.1f, 0.2f, 0.3f};
        assembler.append(samples, 0, samples.length, 0);
        samples[0] = 9f;
        samples[1] = 9f;

        PcmChunk chunk = assembler.poll();
        assertNotNull(chunk);
        assertArrayEquals(new float[]{0.1f, 0.2f}, chunk.toFloatArray(), 0f);
    }
}
