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
        return scan(head, durationSec, ReferenceSet.load(references));
    }

    public static List<AdScanEngine.Segment> scan(Fingerprinter.Hashes head, double durationSec,
                                                  ReferenceSet references) {
        return scan(head, durationSec, references, -1);
    }

    /** {@code selfMediaId} excludes the episode's own stored fingerprint from the references. */
    public static List<AdScanEngine.Segment> scan(Fingerprinter.Hashes head, double durationSec,
                                                  ReferenceSet references, long selfMediaId) {
        if (references.episodes == 0) {
            return List.of();
        }
        List<Matcher.Detection> detections = Matcher.detect(references.index, references.episodes,
                references.maxFrames, references.episodeOf(selfMediaId), head, STRIDE);
        List<SegmentMerger.MergedSegment> merged = SegmentMerger.merge(detections);

        List<AdScanEngine.Segment> out = new ArrayList<>();
        for (SegmentMerger.MergedSegment m : merged) {
            SegmentClassifier.Classified c = SegmentClassifier.classify(m, durationSec);
            long[] sources = new long[m.sourceEpisodes.length];
            for (int s = 0; s < sources.length; s++) {
                sources[s] = references.mediaIds[m.sourceEpisodes[s]];
            }
            out.add(new AdScanEngine.Segment(m.startSec, m.endSec, c.kind, c.tier,
                    m.matchRatio, m.support, sources));
        }
        return out;
    }

    /** Decoded reference episodes and their index, reusable across several head scans. */
    public static final class ReferenceSet {
        public final Index index;
        public final int[] maxFrames;
        private final long[] mediaIds;
        public final int episodes;

        private ReferenceSet(Index index, int[] maxFrames, long[] mediaIds, int episodes) {
            this.index = index;
            this.maxFrames = maxFrames;
            this.mediaIds = mediaIds;
            this.episodes = episodes;
        }

        public static ReferenceSet load(List<AdFingerprintRow> rows) {
            List<Fingerprinter.Hashes> db = new ArrayList<>();
            List<Integer> maxFrames = new ArrayList<>();
            List<Long> mediaIds = new ArrayList<>();
            for (AdFingerprintRow row : rows) {
                try {
                    Fingerprinter.Hashes hashes = FingerprintCodec.decode(row.getBlob()).hashes;
                    db.add(hashes);
                    maxFrames.add(hashes.frames);
                    mediaIds.add(row.getMediaId());
                } catch (IOException ignored) {
                    // Skip unreadable blobs; the remaining references still count.
                }
            }
            if (db.isEmpty()) {
                return new ReferenceSet(null, new int[0], new long[0], 0);
            }
            int[] frames = new int[maxFrames.size()];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = maxFrames.get(i);
            }
            long[] ids = new long[mediaIds.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = mediaIds.get(i);
            }
            return new ReferenceSet(Index.build(db, STRIDE), frames, ids, db.size());
        }

        public int episodeOf(long mediaId) {
            for (int i = 0; i < mediaIds.length; i++) {
                if (mediaIds[i] == mediaId) {
                    return i;
                }
            }
            return -1;
        }

        /** Changes whenever the stored reference fingerprints change. */
        public static String keyOf(List<AdFingerprintRow> rows) {
            StringBuilder key = new StringBuilder(rows.size() * 32);
            for (AdFingerprintRow row : rows) {
                key.append(row.getMediaId()).append(':')
                        .append(row.getAlgoVersion()).append(':')
                        .append(row.getStride()).append(':')
                        .append(row.getFrames()).append(':')
                        .append(row.getAudioSize()).append(':')
                        .append(row.getAudioModified()).append(';');
            }
            return key.toString();
        }
    }
}
