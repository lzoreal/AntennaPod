package de.danoeh.antennapod.ad;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Decodes any MediaExtractor-supported audio to mono float PCM at its native sample rate. */
public final class PcmDecoder {
    public static final class Result {
        public final float[] pcm;
        public final int sampleRate;

        Result(float[] pcm, int sampleRate) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
        }
    }

    /** Receives mono PCM blocks in decode order. */
    public interface Sink {
        void onSamples(float[] mono, int count, int sampleRate);
    }

    private PcmDecoder() {
    }

    public static Result decodeToMono(File file) throws IOException {
        final FloatList out = new FloatList(16000);
        int sampleRate = decodeStreaming(file, (mono, count, rate) -> {
            for (int i = 0; i < count; i++) {
                out.add(mono[i]);
            }
        });
        return new Result(out.toArray(), sampleRate);
    }

    public static int decodeStreaming(File file, Sink sink) throws IOException {
        return decodeStreaming(file, sink, Double.MAX_VALUE);
    }

    public static int decodeStreaming(File file, Sink sink, double maxSeconds) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("no audio track");
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = format.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long produced = 0;
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (info.size > 0) {
                        ByteBuffer buf = codec.getOutputBuffer(index);
                        if (buf != null) {
                            buf.order(ByteOrder.nativeOrder());
                            ShortBuffer shorts = buf.asShortBuffer();
                            int frames = info.size / (2 * channels);
                            long limit = maxSeconds == Double.MAX_VALUE
                                    ? Long.MAX_VALUE : (long) (maxSeconds * sampleRate);
                            int take = (int) Math.min(frames, Math.max(0, limit - produced));
                            if (take > 0) {
                                float[] mono = new float[take];
                                for (int i = 0; i < take; i++) {
                                    int sum = 0;
                                    for (int c = 0; c < channels; c++) {
                                        sum += shorts.get(i * channels + c);
                                    }
                                    mono[i] = sum / (float) (channels * 32768);
                                }
                                produced += take;
                                sink.onSamples(mono, take, sampleRate);
                            }
                            if (produced >= limit) {
                                outputDone = true;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            return sampleRate;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // already stopped
                }
                codec.release();
            }
            extractor.release();
        }
    }

    static final class FloatList {
        private float[] values;
        private int size;

        FloatList(int cap) {
            values = new float[cap];
        }

        void add(float v) {
            if (size == values.length) {
                float[] b = new float[values.length * 2];
                System.arraycopy(values, 0, b, 0, size);
                values = b;
            }
            values[size++] = v;
        }

        float[] toArray() {
            float[] r = new float[size];
            System.arraycopy(values, 0, r, 0, size);
            return r;
        }
    }
}
