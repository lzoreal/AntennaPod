package de.danoeh.antennapod.ad;

import de.danoeh.antennapod.model.feed.AdFingerprintRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Matches an episode head against previously stored full-episode fingerprints. */
public final class HeadScanEngine {
    private static final int STRIDE = 4;

    private HeadScanEngine() {
    }

    public static List<AdScanEngine.Segment> scan(Fingerprinter.Hashes head, double durationSec,
                                                  List<AdFingerprintRow> references) {
        List<Fingerprinter.Hashes> db = new ArrayList<>();
        List<Integer> maxFrames = new ArrayList<>();
        for (AdFingerprintRow row : references) {
            try {
                Fingerprinter.Hashes hashes = FingerprintCodec.decode(row.getBlob()).hashes;
                db.add(hashes);
                maxFrames.add(hashes.frames);
            } catch (IOException ignored) {
                // Skip unreadable blobs; the remaining references still count.
            }
        }
        if (db.isEmpty()) {
            return List.of();
        }
        int[] dbMax = new int[maxFrames.size()];
        for (int i = 0; i < dbMax.length; i++) {
            dbMax[i] = maxFrames.get(i);
        }
        Index index = Index.build(db, STRIDE);
        List<Matcher.Detection> detections = Matcher.detect(index, db.size(), dbMax, head, STRIDE);
        List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(detections);

        List<AdScanEngine.Segment> out = new ArrayList<>();
        for (SegmentMerger.MergedSegment m : merged) {
            SegmentClassifier.Classified c = SegmentClassifier.classify(m, durationSec);
            out.add(new AdScanEngine.Segment(m.startSec, m.endSec, c.kind, c.tier,
                    m.matchRatio, m.support));
        }
        return out;
    }
}
