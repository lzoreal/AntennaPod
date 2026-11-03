package de.danoeh.antennapod.ad;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns decoded playback PCM into fingerprinted chunks, one continuous playback segment at a time.
 * Audio thread hands over resampled blocks; a single worker thread does the fingerprinting.
 */
public final class StreamingAdScanner {
    public interface Listener {
        void onChunk(Chunk chunk);
    }

    public static final class Chunk {
        public final long segmentStartMs;
        public final int startFrame;
        public final int frames;
        public final Fingerprinter.Hashes hashes;

        Chunk(long segmentStartMs, int startFrame, int frames, Fingerprinter.Hashes hashes) {
            this.segmentStartMs = segmentStartMs;
            this.startFrame = startFrame;
            this.frames = frames;
            this.hashes = hashes;
        }

        /** Frame index of the chunk start counted from the beginning of the episode. */
        public int mediaFrame() {
            return (int) (segmentStartMs * Fingerprinter.FPS / 1000) + startFrame;
        }
    }

    private static final int DEFAULT_CHUNK_SECONDS = 300;
    private static final int MIN_CHUNK_SAMPLES = Fingerprinter.SAMPLE_RATE;
    private static final long CONTINUITY_TOLERANCE_MS = 1500;
    private static final Object END = new Object();

    private static final class Marker {
        final long startMs;

        Marker(long startMs) {
            this.startMs = startMs;
        }
    }

    private final int chunkSamples;
    private final Listener listener;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final float[] buffer;

    // Audio thread only.
    private Resampler.Stream resampler;
    private long segmentStartMs;
    private long captured8k;
    private volatile long pendingResetMs = -1;

    // Worker thread only.
    private int buffered;
    private int frameOffset;
    private long workerSegmentStartMs;

    public StreamingAdScanner(Listener listener) {
        this(DEFAULT_CHUNK_SECONDS, listener);
    }

    StreamingAdScanner(int chunkSeconds, Listener listener) {
        this.chunkSamples = chunkSeconds * Fingerprinter.SAMPLE_RATE;
        this.buffer = new float[chunkSamples];
        this.listener = listener;
        this.worker = new Thread(this::drain, "ad-stream-scan");
        this.worker.start();
    }

    public void start(long playbackPositionMs) {
        long startMs = Math.max(0, playbackPositionMs);
        flushResampler();
        segmentStartMs = startMs;
        captured8k = 0;
        queue.offer(new Marker(startMs));
    }

    /** Called from the player's discontinuity callback, which knows the real new position. */
    public void jumpTo(long playbackPositionMs) {
        pendingResetMs = Math.max(0, playbackPositionMs);
    }

    public void accept(float[] pcm, int offset, int count, int sampleRate) {
        long reset = pendingResetMs;
        if (reset >= 0) {
            pendingResetMs = -1;
            flushResampler();
            queue.offer(new Marker(reset));
            segmentStartMs = reset;
            captured8k = 0;
        }
        if (resampler == null) {
            resampler = new Resampler.Stream(sampleRate);
        }
        float[] pcm8k = resampler.process(pcm, offset, count);
        if (pcm8k.length > 0) {
            captured8k += pcm8k.length;
            queue.offer(pcm8k);
        }
    }

    /** Playback jumped or paused; finish the current chunk so it does not span the gap. */
    public void endSegment() {
        flushResampler();
        queue.offer(END);
    }

    private void flushResampler() {
        if (resampler == null) {
            return;
        }
        float[] tail = resampler.finish();
        resampler = null;
        if (tail.length > 0) {
            captured8k += tail.length;
            queue.offer(tail);
        }
    }

    /** Audio pipeline reconfigured; split only when the playback position actually jumped. */
    public void onFlush(long playbackPositionMs) {
        if (pendingResetMs >= 0) {
            return;
        }
        if (Math.abs(playbackPositionMs - expectedPositionMs()) <= CONTINUITY_TOLERANCE_MS) {
            return;
        }
        start(playbackPositionMs);
    }

    public long expectedPositionMs() {
        return segmentStartMs + captured8k * 1000L / Fingerprinter.SAMPLE_RATE;
    }

    public void shutdown() {
        running.set(false);
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            Object item;
            try {
                item = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (item == null) {
                continue;
            }
            if (item == END) {
                finishChunk();
            } else if (item instanceof Marker) {
                finishChunk();
                frameOffset = 0;
                workerSegmentStartMs = ((Marker) item).startMs;
            } else {
                append((float[]) item);
            }
        }
    }

    private void append(float[] block) {
        int off = 0;
        while (off < block.length) {
            int take = Math.min(block.length - off, chunkSamples - buffered);
            System.arraycopy(block, off, buffer, buffered, take);
            buffered += take;
            off += take;
            if (buffered == chunkSamples) {
                finishChunk();
            }
        }
    }

    private void finishChunk() {
        if (buffered < MIN_CHUNK_SAMPLES) {
            buffered = 0;
            return;
        }
        float[] pcm = buffered == buffer.length ? buffer : Arrays.copyOf(buffer, buffered);
        Fingerprinter.Hashes hashes = Fingerprinter.fingerprint(pcm);
        listener.onChunk(new Chunk(workerSegmentStartMs, frameOffset, hashes.frames, hashes));
        frameOffset += buffered / Fingerprinter.HOP;
        buffered = 0;
    }
}
