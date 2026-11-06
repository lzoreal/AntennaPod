package de.danoeh.antennapod.ad;

import java.util.Arrays;
import java.util.List;

/** Sparse sorted inverted index: packed (hash, episode, frame). */
public final class Index {
    static final int MAX_EPISODES = 256;
    static final int MAX_FRAMES = 0xFFFFF;

    private final long[] entries;

    private Index(long[] entries) {
        this.entries = entries;
    }

    public static Index build(List<Fingerprinter.Hashes> episodes, int stride) {
        if (episodes.size() > MAX_EPISODES) {
            throw new IllegalArgumentException("too many episodes: " + episodes.size());
        }
        for (Fingerprinter.Hashes h : episodes) {
            if (h.frames > MAX_FRAMES) {
                throw new IllegalArgumentException("episode too long: " + h.frames + " frames");
            }
        }
        int count = 0;
        for (Fingerprinter.Hashes h : episodes) {
            for (int f : h.frame) {
                if (f % stride == 0) {
                    count++;
                }
            }
        }
        long[] a = new long[count];
        int k = 0;
        for (int e = 0; e < episodes.size(); e++) {
            Fingerprinter.Hashes h = episodes.get(e);
            for (int i = 0; i < h.hash.length; i++) {
                if (h.frame[i] % stride == 0) {
                    a[k++] = ((long) h.hash[i] << 40) | ((long) e << 20) | h.frame[i];
                }
            }
        }
        Arrays.sort(a);
        return new Index(a);
    }

    public int size() {
        return entries.length;
    }

    public int lowerBound(int hash) {
        int lo = 0;
        int hi = entries.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if ((int) (entries[mid] >>> 40) < hash) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public long entryAt(int i) {
        return entries[i];
    }

    public static int hashOf(long entry) {
        return (int) (entry >>> 40);
    }

    public static int episodeOf(long entry) {
        return (int) ((entry >>> 20) & 0xFF);
    }

    public static int frameOf(long entry) {
        return (int) (entry & 0xFFFFF);
    }
}
