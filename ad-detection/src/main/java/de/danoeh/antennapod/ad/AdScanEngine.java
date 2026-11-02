package de.danoeh.antennapod.ad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure matching pipeline: episodes in, per-media ad segments out. */
public final class AdScanEngine {
    private static final int STRIDE = 4;

    public static final class EpisodeData {
        public final long mediaId;
        public final double durationSec;
        public final Fingerprinter.Hashes hashes;

        public EpisodeData(long mediaId, double durationSec, Fingerprinter.Hashes hashes) {
            this.mediaId = mediaId;
            this.durationSec = durationSec;
            this.hashes = hashes;
        }
    }

    public static final class Segment {
        public final double startSec;
        public final double endSec;
        public final int kind;
        public final int tier;
        public final double matchRatio;
        public final int support;

        Segment(double startSec, double endSec, int kind, int tier, double matchRatio, int support) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.kind = kind;
            this.tier = tier;
            this.matchRatio = matchRatio;
            this.support = support;
        }
    }

    public static final class Result {
        private final Map<Long, List<Segment>> byMedia = new HashMap<>();

        public List<Segment> segments(long mediaId) {
            return byMedia.getOrDefault(mediaId, List.of());
        }
    }

    public Result scan(List<EpisodeData> episodes) {
        Result result = new Result();
        for (EpisodeData query : episodes) {
            List<Fingerprinter.Hashes> db = new ArrayList<>();
            int[] dbMax = new int[episodes.size() - 1];
            int k = 0;
            for (EpisodeData e : episodes) {
                if (e.mediaId != query.mediaId) {
                    db.add(e.hashes);
                    dbMax[k++] = e.hashes.frames;
                }
            }
            Index index = Index.build(db, STRIDE);
            List<Matcher.Detection> dets = Matcher.detect(index, db.size(), dbMax, query.hashes, STRIDE);
            List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(dets);

            List<Segment> out = new ArrayList<>();
            for (SegmentMerger.MergedSegment m : merged) {
                SegmentClassifier.Classified c = SegmentClassifier.classify(m, query.durationSec);
                out.add(new Segment(m.startSec, m.endSec, c.kind, c.tier, m.matchRatio, m.support));
            }
            result.byMedia.put(query.mediaId, out);
        }
        return result;
    }
}
