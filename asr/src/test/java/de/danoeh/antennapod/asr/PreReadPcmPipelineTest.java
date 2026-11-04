package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PreReadPcmPipelineTest {
    private static final long CHUNK_MS = 100;

    private final FakeDecoder decoder = new FakeDecoder();
    private final AudioSource source = new AudioSource() {
        @Override
        public MediaDataSource open() {
            throw new UnsupportedOperationException("not used");
        }
    };

    @Test
    public void deliversChunksUntilTheWindowIsCovered() throws IOException {
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);

        List<Long> starts = drain(pipeline, 0, 20);

        assertEquals(Arrays.asList(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L),
                starts);
        assertTrue(pipeline.isCovered(0, 1000));
        assertNull(pipeline.next(0));
    }

    @Test
    public void advancingPlayheadExtendsTheWindow() throws IOException {
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        drain(pipeline, 0, 20);

        List<Long> starts = drain(pipeline, 500, 20);

        assertEquals(Arrays.asList(1000L, 1100L, 1200L, 1300L, 1400L), starts);
        assertTrue(pipeline.isCovered(1000, 1500));
    }

    @Test
    public void forwardSeekStartsAtTheCushionAndLeavesAGap() throws IOException {
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        drain(pipeline, 0, 20);

        PcmChunk chunk = pipeline.next(10000);

        assertEquals(9800, chunk.startTimeMs());
        assertEquals(2, decoder.seekCount);
        assertEquals(Arrays.asList(new MediaRange(1000, 9800)),
                pipeline.coverageGaps(0, 9800));
    }

    @Test
    public void backwardSeekInsideCoverageDoesNotSeek() throws IOException {
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);
        drain(pipeline, 0, 20);
        drain(pipeline, 1000, 20);
        drain(pipeline, 2000, 20);
        drain(pipeline, 3000, 20);
        int seeksBefore = decoder.seekCount;

        assertNull(pipeline.next(0));

        assertEquals(seeksBefore, decoder.seekCount);
    }

    @Test
    public void gapIsFilledWhenThePlayheadEntersIt() throws IOException {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(0, 1000));
        coverage.add(new MediaRange(2000, 3000));
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder,
                new LookaheadPlanner(1000, 200), 1500, coverage);
        pipeline.open(source);

        PcmChunk chunk = pipeline.next(1500);

        assertEquals(1300, chunk.startTimeMs());
    }

    @Test
    public void endOfMediaReturnsNoMoreChunks() throws IOException {
        decoder.durationMs = 300;
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder, 1000, 200);
        pipeline.open(source);

        List<Long> starts = drain(pipeline, 0, 20);

        assertEquals(Arrays.asList(0L, 100L, 200L), starts);
        assertNull(pipeline.next(0));
        assertNull(pipeline.next(0));
    }

    @Test
    public void coveredWindowReportsTheNextGapWithoutEndingTheSource() throws IOException {
        TranscriptCoverage coverage = new TranscriptCoverage();
        coverage.add(new MediaRange(0, 2000));
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder,
                new LookaheadPlanner(1000, 200), 1500, coverage);
        pipeline.open(source);

        assertNull(pipeline.next(0));
        assertEquals(2000, pipeline.nextUncoveredMs(0));
        assertFalse(pipeline.isSourceEnded());

        coverage.add(new MediaRange(0, decoder.durationMs));
        PreReadPcmPipeline full = new PreReadPcmPipeline(decoder,
                new LookaheadPlanner(1000, 200), 1500, coverage);
        full.open(source);
        assertNull(full.next(0));
        assertTrue(full.isSourceEnded());
    }

    @Test
    public void coverageEndIsTheLastCoveredMillisecond() {
        TranscriptCoverage coverage = new TranscriptCoverage();
        assertEquals(-1, new PreReadPcmPipeline(decoder, 1000, 200).coverageEndMs());
        coverage.add(new MediaRange(0, 1000));
        coverage.add(new MediaRange(2000, 3000));
        PreReadPcmPipeline pipeline = new PreReadPcmPipeline(decoder,
                new LookaheadPlanner(1000, 200), 1500, coverage);
        assertEquals(3000, pipeline.coverageEndMs());
    }

    private static List<Long> drain(PreReadPcmPipeline pipeline, long playheadMs, int maxChunks)
            throws IOException {
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < maxChunks; i++) {
            PcmChunk chunk = pipeline.next(playheadMs);
            if (chunk == null) {
                break;
            }
            starts.add(chunk.startTimeMs());
        }
        return starts;
    }

    private static final class FakeDecoder implements PcmDecoder {
        private long position;
        private long durationMs = 60000;
        private int seekCount;

        @Override
        public void open(AudioSource source, long startTimeMs) {
            position = startTimeMs;
        }

        @Override
        public void seekTo(long timeMs) {
            seekCount++;
            position = timeMs;
        }

        @Override
        public PcmChunk read() {
            if (position >= durationMs) {
                return null;
            }
            PcmChunk chunk = new PcmChunk(new float[1600], 1600, position);
            position += CHUNK_MS;
            return chunk;
        }

        @Override
        public long durationMs() {
            return durationMs;
        }

        @Override
        public void close() {
        }
    }
}
