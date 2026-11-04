package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PcmResamplerTest {
    @Test
    public void sixteenKhzIsCopiedUnchanged() {
        PcmResampler resampler = new PcmResampler(16000);
        float[] input = {0.1f, -0.2f, 0.3f};
        float[] output = new float[3];

        int count = resampler.process(input, 0, input.length, output);

        assertEquals(3, count);
        assertArrayEquals(input, output, 0f);
    }

    @Test
    public void fortyEightKhzProducesOneThirdOfTheSamples() {
        PcmResampler resampler = new PcmResampler(48000);
        float[] input = new float[48000];
        float[] output = new float[16002];

        int count = resampler.process(input, 0, input.length, output);

        assertEquals(16000, count);
    }

    @Test
    public void dcPassesTheLowPass() {
        PcmResampler resampler = new PcmResampler(48000);
        float[] input = new float[4800];
        java.util.Arrays.fill(input, 1f);
        float[] output = new float[1700];

        int count = resampler.process(input, 0, input.length, output);

        assertTrue(count > 0);
        assertEquals(1f, output[count - 1], 0.01f);
    }

    @Test
    public void highFrequenciesAreAttenuated() {
        PcmResampler resampler = new PcmResampler(48000);
        float[] input = sine(12000, 48000, 1.0);
        float[] output = new float[16002];

        int count = resampler.process(input, 0, input.length, output);

        double inputRms = rms(input, input.length);
        double outputRms = rms(output, count);
        assertTrue(outputRms < inputRms * 0.3);
    }

    @Test
    public void chunkedInputMatchesOneShotInput() {
        float[] input = sine(440, 44100, 1.0);

        PcmResampler oneShot = new PcmResampler(44100);
        float[] oneShotOutput = new float[input.length + 4];
        int oneShotCount = oneShot.process(input, 0, input.length, oneShotOutput);

        PcmResampler chunked = new PcmResampler(44100);
        List<Float> collected = new ArrayList<>();
        int[] chunkSizes = {333, 1, 777, 512, 2048, 97, 4000};
        int offset = 0;
        int chunkIndex = 0;
        while (offset < input.length) {
            int size = Math.min(chunkSizes[chunkIndex % chunkSizes.length], input.length - offset);
            float[] chunkOutput = new float[(int) Math.ceil(size * 1.0) + 2];
            int count = chunked.process(input, offset, size, chunkOutput);
            for (int i = 0; i < count; i++) {
                collected.add(chunkOutput[i]);
            }
            offset += size;
            chunkIndex++;
        }

        assertEquals(oneShotCount, collected.size());
        for (int i = 0; i < oneShotCount; i++) {
            assertEquals(oneShotOutput[i], collected.get(i), 1e-6f);
        }
    }

    @Test
    public void resetRestartsTheTimeline() {
        PcmResampler resampler = new PcmResampler(48000);
        float[] input = new float[4800];
        java.util.Arrays.fill(input, 1f);
        float[] output = new float[1700];
        resampler.process(input, 0, input.length, output);
        resampler.reset();

        int count = resampler.process(input, 0, input.length, output);

        assertEquals(1f, output[count - 1], 0.01f);
    }

    @Test
    public void eightKhzIsUpsampled() {
        PcmResampler resampler = new PcmResampler(8000);
        float[] input = new float[800];
        java.util.Arrays.fill(input, 1f);
        float[] output = new float[1700];

        int count = resampler.process(input, 0, input.length, output);

        assertTrue(count >= 1590 && count <= 1601);
        assertEquals(1f, output[count - 1], 0.01f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveInputRateIsRejected() {
        new PcmResampler(0);
    }

    private static float[] sine(double frequencyHz, int sampleRateHz, double seconds) {
        int samples = (int) Math.round(sampleRateHz * seconds);
        float[] result = new float[samples];
        for (int i = 0; i < samples; i++) {
            result[i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRateHz);
        }
        return result;
    }

    private static double rms(float[] samples, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += samples[i] * samples[i];
        }
        return Math.sqrt(sum / count);
    }
}
