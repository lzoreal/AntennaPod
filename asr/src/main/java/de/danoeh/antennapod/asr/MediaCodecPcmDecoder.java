package de.danoeh.antennapod.asr;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;

/** MediaExtractor + MediaCodec decoder producing 16 kHz mono PCM chunks. */
public final class MediaCodecPcmDecoder implements PcmDecoder {
    private static final String TAG = "AsrPcmDecoder";
    private static final String KEY_PCM_ENCODING = "pcm-encoding";
    private static final int CHUNK_SAMPLES = 1600;
    private static final long DEQUEUE_TIMEOUT_US = 10_000;
    private static final long STALL_TIMEOUT_MS = 5_000;
    private static final long DISCONTINUITY_US = 200_000;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private MediaDataSource dataSource;
    private MediaExtractor extractor;
    private MediaCodec codec;
    private long durationMs = -1;
    private int inputSampleRateHz;
    private int channelCount;
    private int pcmEncoding;
    private PcmResampler resampler;
    private ChunkAssembler assembler;
    private boolean inputEnded;
    private boolean outputEnded;
    private boolean tailReported;
    private long dropUntilUs;
    private long lastOutputEndUs = -1;
    private float[] resampleBuffer = new float[0];

    @Override
    public void open(@NonNull AudioSource source, long startTimeMs) throws IOException {
        close();
        try {
            dataSource = source.open();
            extractor = new MediaExtractor();
            extractor.setDataSource(dataSource);
            MediaFormat format = selectAudioFormat(extractor);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("audio track has no MIME type");
            }
            inputSampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (inputSampleRateHz <= 0 || channelCount <= 0) {
                throw new IOException("invalid audio format");
            }
            pcmEncoding = format.containsKey(KEY_PCM_ENCODING)
                    ? format.getInteger(KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            requireSupportedEncoding(pcmEncoding);
            durationMs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : -1;
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            resampler = new PcmResampler(inputSampleRateHz);
            assembler = new ChunkAssembler(CHUNK_SAMPLES);
            resetProcessingState();
            seekTo(startTimeMs);
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw new IOException("cannot open audio decoder", e);
        }
    }

    @Override
    public void seekTo(long timeMs) throws IOException {
        if (codec == null || extractor == null) {
            throw new IllegalStateException("decoder is not open");
        }
        long targetUs = Math.max(0, timeMs) * 1000;
        try {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            resetProcessingState();
            dropUntilUs = targetUs;
        } catch (RuntimeException e) {
            throw new IOException("cannot seek audio decoder", e);
        }
    }

    @Nullable
    @Override
    public PcmChunk read() throws IOException {
        if (codec == null || assembler == null) {
            throw new IllegalStateException("decoder is not open");
        }
        long deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS;
        try {
            while (true) {
                PcmChunk chunk = assembler.poll();
                if (chunk != null) {
                    return chunk;
                }
                if (outputEnded) {
                    if (!tailReported) {
                        tailReported = true;
                        PcmChunk tail = assembler.flush();
                        if (tail != null) {
                            return tail;
                        }
                    }
                    return null;
                }
                boolean progressed = feedInput();
                progressed |= drainOutput();
                if (progressed) {
                    deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS;
                } else if (SystemClock.elapsedRealtime() > deadline) {
                    throw new IOException("audio decoder stalled");
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("audio decoder failed", e);
        }
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public void close() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException e) {
                Log.d(TAG, "codec stop failed", e);
            }
            try {
                codec.release();
            } catch (RuntimeException e) {
                Log.d(TAG, "codec release failed", e);
            }
            codec = null;
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (IOException e) {
                Log.d(TAG, "data source close failed", e);
            }
            dataSource = null;
        }
        resampler = null;
        assembler = null;
    }

    private MediaFormat selectAudioFormat(MediaExtractor mediaExtractor) throws IOException {
        for (int index = 0; index < mediaExtractor.getTrackCount(); index++) {
            MediaFormat format = mediaExtractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                mediaExtractor.selectTrack(index);
                return format;
            }
        }
        throw new IOException("no audio track");
    }

    private void resetProcessingState() {
        inputEnded = false;
        outputEnded = false;
        tailReported = false;
        lastOutputEndUs = -1;
        if (assembler != null) {
            assembler.reset();
        }
        if (resampler != null) {
            resampler.reset();
        }
    }

    private boolean feedInput() {
        if (inputEnded) {
            return false;
        }
        int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (index < 0) {
            return false;
        }
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null) {
            inputEnded = true;
            return true;
        }
        buffer.clear();
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputEnded = true;
        } else {
            long presentationTimeUs = Math.max(0, extractor.getSampleTime());
            codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0);
            extractor.advance();
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean drainOutput() throws IOException {
        int index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            applyOutputFormat(codec.getOutputFormat());
            return true;
        }
        if (index < 0) {
            return false;
        }
        try {
            if (bufferInfo.size > 0) {
                processOutput(codec.getOutputBuffer(index));
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputEnded = true;
            }
        } finally {
            codec.releaseOutputBuffer(index, false);
        }
        return true;
    }

    private void processOutput(@Nullable ByteBuffer buffer) throws IOException {
        if (buffer == null) {
            return;
        }
        int frameBytes = channelCount * bytesPerSample(pcmEncoding);
        int frames = bufferInfo.size / frameBytes;
        if (frames <= 0) {
            return;
        }
        long presentationTimeUs = bufferInfo.presentationTimeUs;
        if (presentationTimeUs < 0) {
            presentationTimeUs = lastOutputEndUs >= 0 ? lastOutputEndUs : 0;
        }
        int skipFrames = 0;
        if (dropUntilUs > 0 && presentationTimeUs < dropUntilUs) {
            long deltaUs = dropUntilUs - presentationTimeUs;
            skipFrames = (int) Math.min(frames,
                    (deltaUs * inputSampleRateHz + 999_999) / 1_000_000);
        }
        long bufferEndUs = presentationTimeUs + framesToUs(frames);
        if (skipFrames >= frames) {
            lastOutputEndUs = bufferEndUs;
            return;
        }
        dropUntilUs = 0;
        long keptStartUs = presentationTimeUs + skipFrames * 1_000_000L / inputSampleRateHz;
        if (lastOutputEndUs >= 0 && (presentationTimeUs > lastOutputEndUs + DISCONTINUITY_US
                || bufferEndUs < lastOutputEndUs)) {
            assembler.reset();
            resampler.reset();
        }
        float[] mono = PcmConverter.toMonoFloat(buffer,
                bufferInfo.offset + skipFrames * frameBytes,
                (frames - skipFrames) * frameBytes, channelCount, isFloatEncoding(pcmEncoding));
        int outputCount = resample(mono);
        assembler.append(resampleBuffer, 0, outputCount, Math.max(0, keptStartUs / 1000));
        lastOutputEndUs = bufferEndUs;
    }

    private int resample(float[] mono) {
        int capacity = (int) Math.ceil(mono.length * (double) PcmChunk.SAMPLE_RATE_HZ
                / inputSampleRateHz) + 2;
        if (resampleBuffer.length < capacity) {
            resampleBuffer = new float[capacity];
        }
        return resampler.process(mono, 0, mono.length, resampleBuffer);
    }

    private void applyOutputFormat(MediaFormat format) throws IOException {
        int newEncoding = format.containsKey(KEY_PCM_ENCODING)
                ? format.getInteger(KEY_PCM_ENCODING) : pcmEncoding;
        requireSupportedEncoding(newEncoding);
        int newRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : inputSampleRateHz;
        if (newRate <= 0) {
            throw new IOException("invalid output sample rate: " + newRate);
        }
        if (newRate != inputSampleRateHz) {
            inputSampleRateHz = newRate;
            resampler = new PcmResampler(newRate);
            assembler.reset();
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            int newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (newChannels <= 0) {
                throw new IOException("invalid output channel count: " + newChannels);
            }
            channelCount = newChannels;
        }
        pcmEncoding = newEncoding;
    }

    private static void requireSupportedEncoding(int encoding) throws IOException {
        if (encoding != AudioFormat.ENCODING_PCM_16BIT
                && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            throw new IOException("unsupported PCM encoding: " + encoding);
        }
    }

    private static boolean isFloatEncoding(int encoding) {
        return encoding == AudioFormat.ENCODING_PCM_FLOAT;
    }

    private static int bytesPerSample(int encoding) {
        return isFloatEncoding(encoding) ? 4 : 2;
    }

    private long framesToUs(int frames) {
        return frames * 1_000_000L / inputSampleRateHz;
    }
}
