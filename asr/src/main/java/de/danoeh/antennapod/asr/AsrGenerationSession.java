package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * Drives the pre-read pipeline and the streaming recognizer from one worker thread.
 * Segments are handed to the listener as soon as the recognizer finalizes them.
 */
public final class AsrGenerationSession {
    private static final long DEFAULT_IDLE_SLEEP_MS = 50;
    private static final int MAX_CONSECUTIVE_RETRIES = 12;
    private static final long RETRY_BACKOFF_MS = 500;
    private static final long MAX_RETRY_BACKOFF_MS = 4000;

    public interface Listener {
        void onSegment(@NonNull AsrSegment segment);

        void onCaughtUp(long decodedEndMs);

        void onCompleted();

        void onError(@NonNull Throwable error);
    }

    private final PreReadPcmPipeline pipeline;
    private final StreamingAsr recognizer;
    private final LongSupplier playheadMs;
    private final Listener listener;
    private final long idleSleepMs;

    private volatile boolean running = true;
    private volatile Thread runner;
    private long lastCaughtUpMs = Long.MIN_VALUE;

    public AsrGenerationSession(@NonNull PreReadPcmPipeline pipeline,
                                @NonNull StreamingAsr recognizer,
                                @NonNull LongSupplier playheadMs,
                                @NonNull Listener listener) {
        this(pipeline, recognizer, playheadMs, listener, DEFAULT_IDLE_SLEEP_MS);
    }

    public AsrGenerationSession(@NonNull PreReadPcmPipeline pipeline,
                                @NonNull StreamingAsr recognizer,
                                @NonNull LongSupplier playheadMs,
                                @NonNull Listener listener,
                                long idleSleepMs) {
        if (idleSleepMs <= 0) {
            throw new IllegalArgumentException("idleSleepMs must be positive: " + idleSleepMs);
        }
        this.pipeline = pipeline;
        this.recognizer = recognizer;
        this.playheadMs = playheadMs;
        this.listener = listener;
        this.idleSleepMs = idleSleepMs;
    }

    public void run() {
        if (runner != null) {
            throw new IllegalStateException("session is already running");
        }
        runner = Thread.currentThread();
        int consecutiveRetries = 0;
        try {
            while (running) {
                long playhead = Math.max(0, playheadMs.getAsLong());
                PcmChunk chunk;
                try {
                    chunk = pipeline.next(playhead);
                    consecutiveRetries = 0;
                } catch (AsrRetryableException e) {
                    if (!retryAfterBackoff(++consecutiveRetries)) {
                        listener.onError(e);
                        return;
                    }
                    continue;
                } catch (IOException | RuntimeException e) {
                    listener.onError(e);
                    return;
                }
                if (chunk != null) {
                    try {
                        recognizer.accept(chunk);
                    } catch (RuntimeException e) {
                        listener.onError(e);
                        return;
                    }
                    drainSegments();
                    continue;
                }
                if (pipeline.isSourceEnded()) {
                    break;
                }
                reportCaughtUp();
                if (!sleepQuietly()) {
                    break;
                }
            }
            try {
                recognizer.flush();
            } catch (RuntimeException e) {
                listener.onError(e);
                return;
            }
            drainSegments();
            if (running) {
                listener.onCompleted();
            }
        } finally {
            runner = null;
            try {
                recognizer.close();
            } finally {
                pipeline.close();
            }
        }
    }

    public void stop() {
        running = false;
        Thread thread = runner;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void drainSegments() {
        AsrSegment segment;
        while ((segment = recognizer.pollSegment()) != null) {
            listener.onSegment(segment);
        }
    }

    private void reportCaughtUp() {
        long decodedEndMs = pipeline.decodedEndMs();
        if (decodedEndMs != lastCaughtUpMs) {
            lastCaughtUpMs = decodedEndMs;
            listener.onCaughtUp(decodedEndMs);
        }
    }

    private boolean retryAfterBackoff(int attempt) {
        if (attempt > MAX_CONSECUTIVE_RETRIES) {
            return false;
        }
        long backoff = Math.min(MAX_RETRY_BACKOFF_MS, RETRY_BACKOFF_MS * attempt);
        try {
            Thread.sleep(backoff);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean sleepQuietly() {
        try {
            Thread.sleep(idleSleepMs);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
