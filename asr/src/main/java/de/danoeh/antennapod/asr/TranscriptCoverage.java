package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Sorted, merged set of media time intervals that already have decoded audio. */
public final class TranscriptCoverage {
    private final List<MediaRange> ranges = new ArrayList<>();

    public synchronized void add(@NonNull MediaRange range) {
        int index = 0;
        while (index < ranges.size() && ranges.get(index).endMs() < range.startMs()) {
            index++;
        }
        long start = range.startMs();
        long end = range.endMs();
        while (index < ranges.size() && ranges.get(index).startMs() <= end) {
            start = Math.min(start, ranges.get(index).startMs());
            end = Math.max(end, ranges.get(index).endMs());
            ranges.remove(index);
        }
        ranges.add(index, new MediaRange(start, end));
    }

    public synchronized boolean covers(long startMs, long endMs) {
        if (endMs <= startMs) {
            return true;
        }
        for (MediaRange range : ranges) {
            if (range.startMs() <= startMs && range.endMs() >= endMs) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first millisecond in {@code [startMs, endMs)} that is not covered,
     * or {@code -1} when the whole interval is covered.
     */
    public synchronized long nextUncovered(long startMs, long endMs) {
        long cursor = startMs;
        for (MediaRange range : ranges) {
            if (range.endMs() <= cursor) {
                continue;
            }
            if (range.startMs() > cursor) {
                return cursor < endMs ? cursor : -1;
            }
            cursor = range.endMs();
            if (cursor >= endMs) {
                return -1;
            }
        }
        return cursor < endMs ? cursor : -1;
    }

    @NonNull
    public synchronized List<MediaRange> gaps(long startMs, long endMs) {
        if (endMs <= startMs) {
            return Collections.emptyList();
        }
        List<MediaRange> gaps = new ArrayList<>();
        long cursor = startMs;
        for (MediaRange range : ranges) {
            if (range.endMs() <= cursor) {
                continue;
            }
            if (range.startMs() >= endMs) {
                break;
            }
            if (range.startMs() > cursor) {
                gaps.add(new MediaRange(cursor, Math.min(range.startMs(), endMs)));
            }
            cursor = Math.max(cursor, range.endMs());
            if (cursor >= endMs) {
                return gaps;
            }
        }
        if (cursor < endMs) {
            gaps.add(new MediaRange(cursor, endMs));
        }
        return gaps;
    }

    @NonNull
    public synchronized List<MediaRange> ranges() {
        return Collections.unmodifiableList(new ArrayList<>(ranges));
    }

    public synchronized void clear() {
        ranges.clear();
    }
}
