package de.danoeh.antennapod.ad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Groups per-episode detections that describe the same interval. */
public final class SegmentMerger {
    public static final class MergedSegment {
        public final double startSec;
        public final double endSec;
        public final double matchRatio;
        public final int support;
        public final int[] sourceEpisodes;

        MergedSegment(double startSec, double endSec, double matchRatio, int support) {
            this(startSec, endSec, matchRatio, support, new int[0]);
        }

        MergedSegment(double startSec, double endSec, double matchRatio, int support, int[] sourceEpisodes) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.matchRatio = matchRatio;
            this.support = support;
            this.sourceEpisodes = sourceEpisodes;
        }
    }

    private SegmentMerger() {
    }

    public static List<MergedSegment> merge(List<Matcher.Detection> detections) {
        List<MergedSegment> out = new ArrayList<>();
        List<Matcher.Detection> sorted = new ArrayList<>(detections);
        sorted.sort((a, b) -> Double.compare(a.startSec, b.startSec));
        boolean[] used = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) {
                continue;
            }
            Matcher.Detection base = sorted.get(i);
            List<Matcher.Detection> group = new ArrayList<>();
            for (int j = i; j < sorted.size(); j++) {
                if (!used[j] && iou(base, sorted.get(j)) > 0.5) {
                    used[j] = true;
                    group.add(sorted.get(j));
                }
            }
            double start = Double.MAX_VALUE;
            double end = -1;
            double ratio = 0;
            int[] eps = new int[group.size()];
            int support = 0;
            for (Matcher.Detection d : group) {
                start = Math.min(start, d.startSec);
                end = Math.max(end, d.endSec);
                ratio = Math.max(ratio, d.matchRatio);
                boolean seen = false;
                for (int m = 0; m < support; m++) {
                    if (eps[m] == d.episode) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    eps[support++] = d.episode;
                }
            }
            out.add(new MergedSegment(start, end, ratio, support, Arrays.copyOf(eps, support)));
        }
        return out;
    }

    private static double iou(Matcher.Detection a, Matcher.Detection b) {
        double inter = Math.max(0, Math.min(a.endSec, b.endSec) - Math.max(a.startSec, b.startSec));
        double union = Math.max(a.endSec, b.endSec) - Math.min(a.startSec, b.startSec);
        return union <= 0 ? 0 : inter / union;
    }
}
