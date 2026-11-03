package de.danoeh.antennapod.playback.service.internal;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import de.danoeh.antennapod.ad.StreamingAdScanner;

/** Routes decoded PCM into the streaming ad scanner before speed processing. */
@UnstableApi
public final class PcmTapProcessor {
    private static final String TAG = "PcmTap";

    public interface PositionSource {
        long positionMs();
    }

    private PcmTapProcessor() {
    }

    public static AudioProcessor create(StreamingAdScanner scanner, PositionSource position) {
        return new TeeAudioProcessor(new TeeAudioProcessor.AudioBufferSink() {
            private int sampleRateHz;
            private int channels = 2;
            private int encoding = C.ENCODING_PCM_16BIT;

            @Override
            public void flush(int sampleRateHz, int channelCount, int encoding) {
                this.sampleRateHz = sampleRateHz;
                this.channels = channelCount;
                this.encoding = encoding;
                scanner.onFlush(position.positionMs());
                Log.d(TAG, "format " + sampleRateHz + "Hz ch=" + channelCount + " encoding=" + encoding);
            }

            @Override
            public void handleBuffer(ByteBuffer buffer) {
                ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
                int bytesPerSample = encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
                int frames = view.remaining() / (channels * bytesPerSample);
                if (frames <= 0) {
                    return;
                }
                float[] mono = new float[frames];
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    FloatBuffer floats = view.asFloatBuffer();
                    for (int i = 0; i < frames; i++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            sum += floats.get(i * channels + c);
                        }
                        mono[i] = sum / channels;
                    }
                } else {
                    ShortBuffer shorts = view.asShortBuffer();
                    for (int i = 0; i < frames; i++) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) {
                            sum += shorts.get(i * channels + c);
                        }
                        mono[i] = sum / (float) (channels * 32768);
                    }
                }
                scanner.accept(mono, 0, frames, sampleRateHz);
            }
        });
    }
}
