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
        public final long[] sourceMediaIds;

        Segment(double startSec, double endSec, int kind, int tier, double matchRatio, int support) {
            this(startSec, endSec, kind, tier, matchRatio, support, new long[0]);
        }

        Segment(double startSec, double endSec, int kind, int tier, double matchRatio, int support,
                long[] sourceMediaIds) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.kind = kind;
            this.tier = tier;
            this.matchRatio = matchRatio;
            this.support = support;
            this.sourceMediaIds = sourceMediaIds;
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
        if (episodes.size() < 2) {
            return result;
        }
        List<Fingerprinter.Hashes> db = new ArrayList<>(episodes.size());
        int[] dbMax = new int[episodes.size()];
        for (int i = 0; i < episodes.size(); i++) {
            db.add(episodes.get(i).hashes);
            dbMax[i] = episodes.get(i).hashes.frames;
        }
        Index index = Index.build(db, STRIDE);
        for (int i = 0; i < episodes.size(); i++) {
            EpisodeData query = episodes.get(i);
            List<Matcher.Detection> dets = Matcher.detect(index, db.size(), dbMax, i, query.hashes, STRIDE);
            List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(dets);

            List<Segment> out = new ArrayList<>();
            for (SegmentMerger.MergedSegment m : merged) {
                SegmentClassifier.Classified c = SegmentClassifier.classify(m, query.durationSec);
                long[] sources = new long[m.sourceEpisodes.length];
                for (int s = 0; s < sources.length; s++) {
                    sources[s] = episodes.get(m.sourceEpisodes[s]).mediaId;
                }
                out.add(new Segment(m.startSec, m.endSec, c.kind, c.tier, m.matchRatio, m.support, sources));
            }
            result.byMedia.put(query.mediaId, out);
        }
        return result;
    }
}
