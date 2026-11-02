package de.danoeh.antennapod.ad;

public final class SegmentClassifier {
    public static final int KIND_PREROLL = 0;
    public static final int KIND_MIDROLL = 1;
    public static final int KIND_OUTRO = 2;
    public static final int TIER_AUTO = 0;
    public static final int TIER_MARKED = 1;

    private static final double PREROLL_LIMIT_SEC = 90;
    private static final double OUTRO_LIMIT_SEC = 120;
    private static final double MIN_SEC = 10;
    private static final double MAX_SEC = 150;
    private static final double MIN_RATIO = 0.05;

    public static final class Classified {
        public final int kind;
        public final int tier;

        Classified(int kind, int tier) {
            this.kind = kind;
            this.tier = tier;
        }
    }

    private SegmentClassifier() {
    }

    public static Classified classify(SegmentMerger.MergedSegment s, double durationSec) {
        int kind;
        if (s.startSec < PREROLL_LIMIT_SEC) {
            kind = KIND_PREROLL;
        } else if (s.endSec > durationSec - OUTRO_LIMIT_SEC) {
            kind = KIND_OUTRO;
        } else {
            kind = KIND_MIDROLL;
        }
        double len = s.endSec - s.startSec;
        boolean auto = kind != KIND_OUTRO
                && s.support >= 1
                && len >= MIN_SEC
                && len <= MAX_SEC
                && s.matchRatio >= MIN_RATIO;
        return new Classified(kind, auto ? TIER_AUTO : TIER_MARKED);
    }
}
