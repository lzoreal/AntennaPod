package de.danoeh.antennapod.ad;

import java.util.ArrayList;
import java.util.Arrays;
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
        return detect(index, episodes, maxFrames, -1, query, stride);
    }

    /** {@code selfEpisode} is skipped while voting so one index can serve every episode. */
    public static List<Detection> detect(Index index, int episodes, int[] maxFrames, int selfEpisode,
                                         Fingerprinter.Hashes query, int stride) {
        QueryIndex q = new QueryIndex(index, query);
        VoteCounts votes = new VoteCounts(Math.max(16, q.size * 4));
        for (int i = 0; i < q.size; i++) {
            if (countPostings(index, q.lo[i], q.hi[i], selfEpisode) > MAX_HASH_DF) {
                continue;
            }
            for (int p = q.lo[i]; p < q.hi[i]; p++) {
                long entry = index.entryAt(p);
                int ep = Index.episodeOf(entry);
                if (ep == selfEpisode) {
                    continue;
                }
                int bin = q.frame[i] - Index.frameOf(entry) + maxFrames[ep];
                if (bin >= 0 && bin <= 2 * maxFrames[ep]) {
                    votes.add(ep, bin);
                }
            }
        }

        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < votes.slotCount(); i++) {
            if (!votes.occupied(i)) {
                continue;
            }
            int ep = votes.episodeAt(i);
            int bin = votes.binAt(i);
            int c = votes.countAt(i);
            if (c < MIN_VOTES) {
                continue;
            }
            int end = 2 * maxFrames[ep] + 1;
            if (bin < 6 || bin >= end - 6) {
                continue;
            }
            boolean peak = true;
            for (int k = 1; k <= 5; k++) {
                if (votes.get(ep, bin - k) > c || votes.get(ep, bin + k) > c) {
                    peak = false;
                    break;
                }
            }
            if (peak) {
                candidates.add(new int[]{ep, bin - maxFrames[ep], c});
            }
        }
        candidates.sort((x, y) -> {
            int byVotes = Integer.compare(y[2], x[2]);
            if (byVotes != 0) {
                return byVotes;
            }
            int byEpisode = Integer.compare(x[0], y[0]);
            return byEpisode != 0 ? byEpisode : Integer.compare(x[1], y[1]);
        });

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
            verify(index, q, c[0], c[1], stride, out);
        }
        return out;
    }

    private static int countPostings(Index index, int lo, int hi, int selfEpisode) {
        if (selfEpisode < 0) {
            return hi - lo;
        }
        int count = 0;
        for (int p = lo; p < hi; p++) {
            if (Index.episodeOf(index.entryAt(p)) != selfEpisode) {
                count++;
            }
        }
        return count;
    }

    private static void verify(Index index, QueryIndex q, int ep, int dt,
                               int stride, List<Detection> out) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < q.size; i++) {
            int t = q.frame[i];
            for (int p = q.lo[i]; p < q.hi[i]; p++) {
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

    /** Sorted query hashes with their shared posting range, so verification never re-bins. */
    private static final class QueryIndex {
        private final int[] frame;
        private final int[] lo;
        private final int[] hi;
        private final int size;

        QueryIndex(Index index, Fingerprinter.Hashes query) {
            long[] packed = new long[query.hash.length];
            for (int i = 0; i < query.hash.length; i++) {
                packed[i] = ((long) query.hash[i] << 32) | (query.frame[i] & 0xFFFFFFFFL);
            }
            Arrays.sort(packed);
            frame = new int[packed.length];
            lo = new int[packed.length];
            hi = new int[packed.length];
            size = packed.length;
            int i = 0;
            while (i < packed.length) {
                int hash = (int) (packed[i] >>> 32);
                int next = i + 1;
                while (next < packed.length && (int) (packed[next] >>> 32) == hash) {
                    next++;
                }
                int start = index.lowerBound(hash);
                int end = start;
                while (end < index.size() && Index.hashOf(index.entryAt(end)) == hash) {
                    end++;
                }
                for (int j = i; j < next; j++) {
                    frame[j] = (int) packed[j];
                    lo[j] = start;
                    hi[j] = end;
                }
                i = next;
            }
        }
    }

    /** Sparse (episode, offset) vote counter; most of the offset space stays at zero. */
    private static final class VoteCounts {
        private long[] keys;
        private int[] counts;
        private int mask;
        private int size;

        VoteCounts(int expected) {
            int capacity = 16;
            while (capacity < expected) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            counts = new int[capacity];
            mask = capacity - 1;
            Arrays.fill(keys, -1);
        }

        void add(int episode, int bin) {
            insert(key(episode, bin), 1);
            if (size * 2 > keys.length) {
                grow();
            }
        }

        int get(int episode, int bin) {
            long key = key(episode, bin);
            int i = slot(key);
            while (true) {
                long k = keys[i];
                if (k == key) {
                    return counts[i];
                }
                if (k == -1) {
                    return 0;
                }
                i = (i + 1) & mask;
            }
        }

        int slotCount() {
            return keys.length;
        }

        boolean occupied(int i) {
            return keys[i] != -1;
        }

        int episodeAt(int i) {
            return (int) (keys[i] >>> 32);
        }

        int binAt(int i) {
            return (int) keys[i];
        }

        int countAt(int i) {
            return counts[i];
        }

        private void insert(long key, int delta) {
            int i = slot(key);
            while (true) {
                long k = keys[i];
                if (k == key) {
                    counts[i] += delta;
                    return;
                }
                if (k == -1) {
                    keys[i] = key;
                    counts[i] = delta;
                    size++;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldCounts = counts;
            int capacity = keys.length << 1;
            keys = new long[capacity];
            counts = new int[capacity];
            mask = capacity - 1;
            Arrays.fill(keys, -1);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != -1) {
                    insert(oldKeys[i], oldCounts[i]);
                }
            }
        }

        private int slot(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }

        private static long key(int episode, int bin) {
            return ((long) episode << 32) | (bin & 0xFFFFFFFFL);
        }
    }
}
