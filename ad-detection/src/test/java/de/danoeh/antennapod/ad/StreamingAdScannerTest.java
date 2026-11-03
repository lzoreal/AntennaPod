package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StreamingAdScannerTest {
    private static final class Collect implements StreamingAdScanner.Listener {
        final List<StreamingAdScanner.Chunk> chunks =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onChunk(StreamingAdScanner.Chunk chunk) {
            chunks.add(chunk);
        }
    }

    private static void feed(StreamingAdScanner scanner, float[] pcm, int block) {
        for (int off = 0; off < pcm.length; off += block) {
            scanner.accept(pcm, off, Math.min(block, pcm.length - off), Fingerprinter.SAMPLE_RATE);
        }
    }

    @Test
    public void continuousPlaybackEmitsAlignedChunks() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(0);
        feed(scanner, Synth.speechLike(10, 1), 1000);
        scanner.endSegment();
        scanner.shutdown();

        assertEquals(3, collect.chunks.size());
        assertEquals(0, collect.chunks.get(0).startFrame);
        assertEquals(4 * Fingerprinter.SAMPLE_RATE / Fingerprinter.HOP, collect.chunks.get(1).startFrame);
        assertTrue("hashes", collect.chunks.get(0).hashes.hash.length > 0);
        assertEquals(0, collect.chunks.get(0).segmentStartMs);
    }

    @Test
    public void continuousFlushKeepsAccumulating() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(0);
        feed(scanner, Synth.speechLike(10, 3), 1000);
        scanner.onFlush(10_000);
        feed(scanner, Synth.speechLike(5, 4), 1000);
        scanner.endSegment();
        scanner.shutdown();

        assertEquals(4, collect.chunks.size());
        assertEquals(0, collect.chunks.get(2).segmentStartMs);
        assertEquals(8 * Fingerprinter.SAMPLE_RATE / Fingerprinter.HOP,
                collect.chunks.get(2).startFrame);
    }

    @Test
    public void jumpFlushStartsNewSegment() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(0);
        feed(scanner, Synth.speechLike(5, 5), 1000);
        scanner.onFlush(120_000);
        feed(scanner, Synth.speechLike(5, 6), 1000);
        scanner.endSegment();
        scanner.shutdown();

        assertEquals(4, collect.chunks.size());
        assertEquals(120_000, collect.chunks.get(2).segmentStartMs);
        assertEquals(0, collect.chunks.get(2).startFrame);
    }

    @Test
    public void jumpToRestartsSegmentAtSeekTarget() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(0);
        feed(scanner, Synth.speechLike(5, 8), 1000);
        scanner.jumpTo(120_000);
        feed(scanner, Synth.speechLike(5, 9), 1000);
        scanner.endSegment();
        scanner.shutdown();

        assertEquals(4, collect.chunks.size());
        assertEquals(120_000, collect.chunks.get(2).segmentStartMs);
        assertEquals(0, collect.chunks.get(2).startFrame);
    }

    @Test
    public void chunkMediaFrameCombinesSegmentAndOffset() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(120_000);
        feed(scanner, Synth.speechLike(10, 2), 1000);
        scanner.endSegment();
        scanner.shutdown();

        int segmentFrame = (int) (120_000 * Fingerprinter.FPS / 1000);
        assertEquals(segmentFrame, collect.chunks.get(0).mediaFrame());
        assertEquals(segmentFrame + 4 * Fingerprinter.SAMPLE_RATE / Fingerprinter.HOP,
                collect.chunks.get(1).mediaFrame());
    }

    @Test
    public void tinyChunkIsDropped() {
        Collect collect = new Collect();
        StreamingAdScanner scanner = new StreamingAdScanner(4, collect);
        scanner.start(0);
        scanner.accept(Synth.speechLike(1, 7), 0, 4000, Fingerprinter.SAMPLE_RATE);
        scanner.endSegment();
        scanner.shutdown();

        assertEquals(0, collect.chunks.size());
    }
}
