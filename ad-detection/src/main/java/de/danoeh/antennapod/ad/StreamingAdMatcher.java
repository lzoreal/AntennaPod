package de.danoeh.antennapod.ad;

import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;

import java.util.ArrayList;
import java.util.List;

/** Maps chunk-relative detections onto the episode timeline and into persisted rows. */
public final class StreamingAdMatcher {
    private StreamingAdMatcher() {
    }

    public static List<AdSegment> match(long mediaId, StreamingAdScanner.Chunk chunk,
                                        double durationSec, List<AdFingerprintRow> references) {
        return match(mediaId, chunk, durationSec, HeadScanEngine.ReferenceSet.load(references));
    }

    public static List<AdSegment> match(long mediaId, StreamingAdScanner.Chunk chunk,
                                        double durationSec, HeadScanEngine.ReferenceSet references) {
        List<AdScanEngine.Segment> found =
                HeadScanEngine.scan(chunk.hashes, durationSec, references, mediaId);
        long offsetMs = chunk.segmentStartMs
                + (long) chunk.startFrame * Fingerprinter.HOP * 1000L / Fingerprinter.SAMPLE_RATE;
        List<AdSegment> out = new ArrayList<>();
        for (AdScanEngine.Segment s : found) {
            AdSegment segment = new AdSegment();
            segment.setMediaId(mediaId);
            segment.setStartMs((int) (offsetMs + s.startSec * 1000));
            segment.setEndMs((int) (offsetMs + s.endSec * 1000));
            segment.setKind(s.kind);
            segment.setTier(s.tier);
            segment.setMatchRatio((float) s.matchRatio);
            segment.setSupport(s.support);
            segment.setSourceMediaIds(s.sourceMediaIds);
            out.add(segment);
        }
        return out;
    }
}
