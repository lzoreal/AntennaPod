package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Pull-based pre-read pipeline. The caller drives it with the current playhead and
 * receives one 16 kHz mono chunk per call, or {@code null} when the lookahead window
 * is already covered.
 */
public final class PreReadPcmPipeline implements AutoCloseable {
    private static final long DEFAULT_SEEK_THRESHOLD_MS = 1500;

    private final PcmDecoder decoder;
    private final LookaheadPlanner planner;
    private final TranscriptCoverage coverage;
    private final long seekThresholdMs;

    private long lastPlayheadMs = -1;
    private long decodedEndMs = -1;
    private boolean seekRequested = true;
    private boolean sourceEnded;

    public PreReadPcmPipeline(@NonNull PcmDecoder decoder, long windowMs, long cushionMs) {
        this(decoder, new LookaheadPlanner(windowMs, cushionMs), DEFAULT_SEEK_THRESHOLD_MS);
    }

    public PreReadPcmPipeline(@NonNull PcmDecoder decoder, @NonNull LookaheadPlanner planner,
                              long seekThresholdMs) {
        this(decoder, planner, seekThresholdMs, new TranscriptCoverage());
    }

    public PreReadPcmPipeline(@NonNull PcmDecoder decoder, @NonNull LookaheadPlanner planner,
                              long seekThresholdMs, @NonNull TranscriptCoverage coverage) {
        if (seekThresholdMs < 0) {
            throw new IllegalArgumentException("seekThresholdMs is negative: " + seekThresholdMs);
        }
        this.decoder = decoder;
        this.planner = planner;
        this.seekThresholdMs = seekThresholdMs;
        this.coverage = coverage;
    }

    public void open(@NonNull AudioSource source) throws IOException {
        decoder.open(source, 0);
        lastPlayheadMs = -1;
        decodedEndMs = -1;
        seekRequested = true;
        sourceEnded = false;
    }

    /**
     * Decodes the next chunk that is inside the lookahead window and not yet transcribed.
     *
     * @param playheadMs current playback position
     * @return a chunk starting before the window end, or null when the window is covered
     */
    @Nullable
    public PcmChunk next(long playheadMs) throws IOException {
        long playhead = Math.max(0, playheadMs);
        boolean seek = seekRequested || lastPlayheadMs < 0
                || Math.abs(playhead - lastPlayheadMs) > seekThresholdMs;
        lastPlayheadMs = playhead;

        LookaheadPlanner.Plan plan = planner.next(playhead, decodedEndMs, seek, coverage,
                decoder.durationMs());
        if (plan == null) {
            long durationMs = decoder.durationMs();
            if (durationMs > 0 && decodedEndMs >= durationMs) {
                sourceEnded = true;
            }
            return null;
        }
        if (seek || decodedEndMs < 0 || plan.startMs() != decodedEndMs) {
            decoder.seekTo(plan.startMs());
            decodedEndMs = plan.startMs();
            seekRequested = false;
            sourceEnded = false;
        }
        PcmChunk chunk = decoder.read();
        if (chunk == null) {
            sourceEnded = true;
            return null;
        }
        decodedEndMs = Math.max(decodedEndMs, chunk.endTimeMs());
        coverage.add(new MediaRange(chunk.startTimeMs(), chunk.endTimeMs()));
        return chunk;
    }

    public boolean isCovered(long startMs, long endMs) {
        return coverage.covers(startMs, endMs);
    }

    @NonNull
    public List<MediaRange> coverageGaps(long startMs, long endMs) {
        return coverage.gaps(startMs, endMs);
    }

    @NonNull
    public List<MediaRange> coverageRanges() {
        return coverage.ranges();
    }

    public long decodedEndMs() {
        return decodedEndMs;
    }

    public boolean isSourceEnded() {
        return sourceEnded;
    }

    @Override
    public void close() {
        decoder.close();
    }
}
