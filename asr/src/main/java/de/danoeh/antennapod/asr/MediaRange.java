package de.danoeh.antennapod.asr;

/**
 * A half-open media time interval {@code [startMs, endMs)}.
 */
public final class MediaRange {
    private final long startMs;
    private final long endMs;

    public MediaRange(long startMs, long endMs) {
        if (startMs < 0) {
            throw new IllegalArgumentException("startMs is negative: " + startMs);
        }
        if (endMs <= startMs) {
            throw new IllegalArgumentException("empty range: " + startMs + ".." + endMs);
        }
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public long startMs() {
        return startMs;
    }

    public long endMs() {
        return endMs;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    public boolean contains(long timeMs) {
        return timeMs >= startMs && timeMs < endMs;
    }

    public boolean touches(MediaRange other) {
        return startMs <= other.endMs && other.startMs <= endMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MediaRange)) {
            return false;
        }
        MediaRange other = (MediaRange) o;
        return startMs == other.startMs && endMs == other.endMs;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(startMs) + Long.hashCode(endMs);
    }

    @Override
    public String toString() {
        return "[" + startMs + ", " + endMs + ")";
    }
}
