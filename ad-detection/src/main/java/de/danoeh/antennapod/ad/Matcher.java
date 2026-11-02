package de.danoeh.antennapod.ad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Offset voting over the sparse index, then per-interval density verification. */
public final class Matcher {
    private static final int MIN_VOTES = 8;
    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_HASH_DF = 50;
    private static final int CANDIDATE_SEPARATION = 32;
    private static final double MIN_MATCH_RATIO = 0.05;
    private static final int MIN_RUN_FRAMES = (int) (10 * Fingerprinter.FPS);
    private static final int MAX_GAP_FRAMES = (int) (2 * Fingerprinter.FPS);

    public static final class Detection {
        public final int episode;
        public final double startSec;
        public final double endSec;
        public final double matchRatio;

        Detection(int episode, double startSec, double endSec, double matchRatio) {
            this.episode = episode;
            this.startSec = startSec;
            this.endSec = endSec;
            this.matchRatio = matchRatio;
        }
    }

    private Matcher() {
    }

    public static List<Detection> detect(Index index, int episodes, int[] maxFrames,
                                         Fingerprinter.Hashes query, int stride) {
        int[][] votes = new int[episodes][];
        for (int e = 0; e < episodes; e++) {
            votes[e] = new int[2 * maxFrames[e] + 1];
        }
        for (int i = 0; i < query.hash.length; i++) {
            int hash = query.hash[i];
            int t = query.frame[i];
            int p = index.lowerBound(hash);
            int end = p;
            while (end < index.size() && Index.hashOf(index.entryAt(end)) == hash) {
                end++;
            }
            if (end - p > MAX_HASH_DF) {
                continue;
            }
            for (; p < end; p++) {
                long e = index.entryAt(p);
                int ep = Index.episodeOf(e);
                int off = t - Index.frameOf(e) + maxFrames[ep];
                if (off >= 0 && off < votes[ep].length) {
                    votes[ep][off]++;
                }
            }
        }

        List<int[]> candidates = new ArrayList<>();
        for (int e = 0; e < episodes; e++) {
            int[] v = votes[e];
            for (int d = 6; d < v.length - 6; d++) {
                int c = v[d];
                if (c < MIN_VOTES) {
                    continue;
                }
                boolean peak = true;
                for (int k = 1; k <= 5; k++) {
                    if (v[d - k] > c || v[d + k] > c) {
                        peak = false;
                        break;
                    }
                }
                if (peak) {
                    candidates.add(new int[]{e, d - maxFrames[e], c});
                }
            }
        }
        candidates.sort((x, y) -> Integer.compare(y[2], x[2]));

        List<Detection> out = new ArrayList<>();
        List<int[]> chosen = new ArrayList<>();
        int attempts = 0;
        for (int[] c : candidates) {
            if (attempts >= MAX_CANDIDATES) {
                break;
            }
            boolean close = false;
            for (int[] s : chosen) {
                if (s[0] == c[0] && Math.abs(s[1] - c[1]) < CANDIDATE_SEPARATION) {
                    close = true;
                    break;
                }
            }
            if (close) {
                continue;
            }
            chosen.add(c);
            attempts++;
            verify(index, query, c[0], c[1], stride, out);
        }
        return out;
    }

    private static void verify(Index index, Fingerprinter.Hashes query, int ep, int dt,
                               int stride, List<Detection> out) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < query.hash.length; i++) {
            int t = query.frame[i];
            int hash = query.hash[i];
            int p = index.lowerBound(hash);
            for (; p < index.size() && Index.hashOf(index.entryAt(p)) == hash; p++) {
                long e = index.entryAt(p);
                if (Index.episodeOf(e) == ep && Math.abs(t - Index.frameOf(e) - dt) <= 1) {
                    matched.add(t);
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            return;
        }
        Collections.sort(matched);
        int runStart = matched.get(0);
        int prev = matched.get(0);
        int count = 1;
        for (int i = 1; i < matched.size(); i++) {
            int t = matched.get(i);
            if (t == prev) {
                continue;
            }
            if (t - prev > MAX_GAP_FRAMES) {
                addRun(out, ep, runStart, prev, count, stride);
                runStart = t;
                count = 0;
            }
            prev = t;
            count++;
        }
        addRun(out, ep, runStart, prev, count, stride);
    }

    private static void addRun(List<Detection> out, int ep, int start, int end, int count, int stride) {
        int len = end - start + 1;
        if (len < MIN_RUN_FRAMES) {
            return;
        }
        double ratio = count / (double) len;
        if (ratio < MIN_MATCH_RATIO) {
            return;
        }
        out.add(new Detection(ep, start / Fingerprinter.FPS, (end + 1) / Fingerprinter.FPS, ratio));
    }
}
