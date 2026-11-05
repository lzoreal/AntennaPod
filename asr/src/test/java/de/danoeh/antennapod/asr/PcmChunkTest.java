package de.danoeh.antennapod.asr;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PcmChunkTest {
    @Test
    public void endTimeRoundsToNearestMillisecond() {
        PcmChunk chunk = new PcmChunk(new float[1600], 1600, 2500);
        assertEquals(2600, chunk.endTimeMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeStartIsRejected() {
        new PcmChunk(new float[1], 1, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void lengthPastTheArrayIsRejected() {
        new PcmChunk(new float[2], 3, 0);
    }

    @Test
    public void toFloatArrayCopiesOnlyTheValidSamples() {
        float[] samples = {0.25f, 0.5f, 0.75f, 99f};
        PcmChunk chunk = new PcmChunk(samples, 3, 0);
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.75f}, chunk.toFloatArray(), 0f);
    }

    @Test
    public void tailShorterThanOneMillisecondStillHasARange() {
        PcmChunk chunk = new PcmChunk(new float[7], 7, 1000);

        assertEquals(1000, chunk.startTimeMs());
        assertEquals(1001, chunk.endTimeMs());
        assertTrue(chunk.endTimeMs() > chunk.startTimeMs());
    }
}
