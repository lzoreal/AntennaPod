package de.danoeh.antennapod.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Converts interleaved decoded PCM to mono float samples. */
public final class PcmConverter {
    private PcmConverter() {
    }

    public static float[] toMonoFloat(ByteBuffer buffer, int offset, int size,
                                      int channels, boolean floatEncoding) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (offset < 0 || size < 0 || offset + size > buffer.capacity()) {
            throw new IllegalArgumentException("invalid buffer window");
        }
        int bytesPerSample = floatEncoding ? 4 : 2;
        int frameBytes = bytesPerSample * channels;
        int frames = size / frameBytes;
        float[] mono = new float[frames];
        ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
        view.position(offset);
        view.limit(offset + frames * frameBytes);
        if (floatEncoding) {
            FloatBuffer floats = view.asFloatBuffer();
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0f;
                for (int channel = 0; channel < channels; channel++) {
                    sum += floats.get(frame * channels + channel);
                }
                mono[frame] = sum / channels;
            }
        } else {
            ShortBuffer shorts = view.asShortBuffer();
            for (int frame = 0; frame < frames; frame++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += shorts.get(frame * channels + channel);
                }
                mono[frame] = sum / (channels * 32768f);
            }
        }
        return mono;
    }
}
