package de.danoeh.antennapod.asr;

import android.util.Log;

import androidx.annotation.Nullable;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.util.ArrayDeque;
import java.util.Deque;

/** Streaming sherpa-onnx recognizer. Not thread-safe: drive it from one worker thread. */
public final class SherpaStreamingRecognizer implements StreamingAsr {
    private static final String TAG = "AsrSherpa";
    private static final long DISCONTINUITY_TOLERANCE_MS = 200;

    private final OnlineRecognizer recognizer;
    private final OnlineStream stream;
    private final Deque<AsrSegment> segments = new ArrayDeque<>();

    private long streamStartMs = -1;
    private long lastEndMs = -1;
    private boolean flushed;
    private boolean closed;
    private boolean firstResultLogged;

    SherpaStreamingRecognizer(OnlineRecognizer recognizer) {
        this.recognizer = recognizer;
        this.stream = recognizer.createStream("");
    }

    @Override
    public void accept(PcmChunk chunk) {
        if (flushed) {
            throw new IllegalStateException("stream already flushed");
        }
        if (chunk.length() == 0) {
            return;
        }
        if (streamStartMs < 0) {
            streamStartMs = chunk.startTimeMs();
        } else if (lastEndMs >= 0
                && Math.abs(chunk.startTimeMs() - lastEndMs) > DISCONTINUITY_TOLERANCE_MS) {
            recognizer.reset(stream);
            streamStartMs = chunk.startTimeMs();
        }
        stream.acceptWaveform(chunk.toFloatArray(), PcmChunk.SAMPLE_RATE_HZ);
        lastEndMs = chunk.endTimeMs();
        decodeReady();
        if (recognizer.isEndpoint(stream)) {
            enqueueResult(lastEndMs);
            recognizer.reset(stream);
            streamStartMs = lastEndMs;
        }
    }

    @Nullable
    @Override
    public AsrSegment pollSegment() {
        return segments.poll();
    }

    @Override
    public void flush() {
        if (flushed) {
            return;
        }
        if (streamStartMs >= 0) {
            stream.inputFinished();
            decodeReady();
            enqueueResult(lastEndMs >= 0 ? lastEndMs : streamStartMs);
        }
        flushed = true;
    }

    @Override
    public long lastProcessedMs() {
        return lastEndMs;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stream.release();
        } catch (RuntimeException e) {
            Log.d(TAG, "stream release failed", e);
        }
        try {
            recognizer.release();
        } catch (RuntimeException e) {
            Log.d(TAG, "recognizer release failed", e);
        }
    }

    private void decodeReady() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
    }

    private void enqueueResult(long fallbackEndMs) {
        OnlineRecognizerResult result = recognizer.getResult(stream);
        String text = result.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (!firstResultLogged) {
            firstResultLogged = true;
            Log.i(TAG, "First result: " + abbreviate(text) + " tokens="
                    + abbreviate(result.getTokens()));
        }
        AsrSegment segment = AsrResultMapper.toSegment(text, result.getTokens(),
                result.getTimestamps(), Math.max(0, streamStartMs), fallbackEndMs);
        segments.add(segment);
        float[] times = result.getTimestamps();
        long mappedFirst = segment.getWords().isEmpty() ? -1
                : segment.getWords().get(0).getStartTime();
        long mappedLast = segment.getWords().isEmpty() ? -1
                : segment.getWords().get(segment.getWords().size() - 1).getEndTime();
        Log.i(TAG, "result streamStart=" + streamStartMs + " fallbackEnd=" + fallbackEndMs
                + " tokens=" + (result.getTokens() == null ? 0 : result.getTokens().length)
                + " words=" + segment.getWords().size()
                + " firstTime=" + (times == null || times.length == 0 ? "none" : times[0])
                + " lastTime=" + (times == null || times.length == 0 ? "none" : times[times.length - 1])
                + " mappedFirst=" + mappedFirst + " mappedLast=" + mappedLast);
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static String abbreviate(String[] tokens) {
        if (tokens == null) {
            return "null";
        }
        int count = Math.min(tokens.length, 40);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(tokens[i]);
        }
        if (tokens.length > count) {
            builder.append(" …(").append(tokens.length).append(')');
        }
        return builder.toString();
    }
}
