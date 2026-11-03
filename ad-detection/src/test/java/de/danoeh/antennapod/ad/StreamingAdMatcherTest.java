package de.danoeh.antennapod.ad;

import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StreamingAdMatcherTest {
    @Test
    public void mapsChunkMatchesToEpisodeTimeline() {
        float[] ad = Synth.speechLike(15, 3);
        float[] reference = Synth.speechLike(300, 1);
        Synth.insert(reference, ad, 40);
        float[] head = Synth.speechLike(180, 2);
        Synth.insert(head, ad, 10);

        Fingerprinter.Hashes refHashes = Fingerprinter.fingerprint(reference);
        AdFingerprintRow row = new AdFingerprintRow();
        row.setAlgoVersion(FingerprintCodec.ALGO_VERSION);
        row.setStride(4);
        row.setFrames(refHashes.frames);
        row.setBlob(FingerprintCodec.encode(refHashes, 4, Fingerprinter.SAMPLE_RATE));

        Fingerprinter.Hashes chunkHashes = Fingerprinter.fingerprint(head);
        StreamingAdScanner.Chunk chunk =
                new StreamingAdScanner.Chunk(60_000, 0, chunkHashes.frames, chunkHashes);

        List<AdSegment> segments =
                StreamingAdMatcher.match(11130, chunk, 3000, List.of(row));

        assertEquals(1, segments.size());
        AdSegment s = segments.get(0);
        assertEquals(11130, s.getMediaId());
        assertTrue("start=" + s.getStartMs(), Math.abs(s.getStartMs() - 70_000) < 2000);
        assertTrue("end=" + s.getEndMs(), Math.abs(s.getEndMs() - 85_000) < 2000);
    }
}
