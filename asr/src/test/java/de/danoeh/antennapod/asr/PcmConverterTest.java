package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PcmConverterTest {
    @Test
    public void sixteenBitStereoIsAveraged() {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buffer.asShortBuffer().put(new short[]{1000, -1000, 16384, 16384});

        float[] mono = PcmConverter.toMonoFloat(buffer, 0, 8, 2, false);

        assertEquals(2, mono.length);
        assertEquals(0f, mono[0], 1e-6f);
        assertEquals(0.5f, mono[1], 1e-6f);
    }

    @Test
    public void floatMonoIsCopied() {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(new float[]{0.25f, -0.5f});

        float[] mono = PcmConverter.toMonoFloat(buffer, 0, 8, 1, true);

        assertArrayEquals(new float[]{0.25f, -0.5f}, mono, 1e-6f);
    }

    @Test
    public void incompleteFrameIsDropped() {
        ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.nativeOrder());
        buffer.asShortBuffer().put(new short[]{100, 200, 300});

        float[] mono = PcmConverter.toMonoFloat(buffer, 0, 7, 2, false);

        assertEquals(1, mono.length);
    }

    @Test
    public void offsetIsRespected() {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buffer.asShortBuffer().put(new short[]{0, 1000, 2000, 3000});

        float[] mono = PcmConverter.toMonoFloat(buffer, 4, 4, 1, false);

        assertEquals(2, mono.length);
        assertEquals(2000 / 32768f, mono[0], 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroChannelsIsRejected() {
        PcmConverter.toMonoFloat(ByteBuffer.allocate(4), 0, 4, 0, false);
    }
}
