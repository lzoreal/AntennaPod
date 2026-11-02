package de.danoeh.antennapod.playback.service.internal;

import java.util.ArrayList;
import java.util.List;

/** Pure skip state machine: entry detection, user-seek bypass, per-segment disable. */
public final class SkipEngine {
    private static final long TAIL_MS = 250;

    private final List<long[]> segments = new ArrayList<>();
    private final List<Long> bypass = new ArrayList<>();
    private long lastPosition = -1;

    public void setSegments(List<long[]> ranges) {
        segments.clear();
        segments.addAll(ranges);
        bypass.clear();
        lastPosition = -1;
    }

    public Long onPosition(long positionMs) {
        long previous = lastPosition;
        lastPosition = positionMs;
        if (previous < 0 || positionMs < previous) {
            return null;
        }
        for (long[] s : segments) {
            if (bypass.contains(s[0])) {
                continue;
            }
            if (previous < s[1] && positionMs >= s[1] && positionMs < s[2]) {
                return s[2] + TAIL_MS;
            }
        }
        return null;
    }

    public void onUserSeek(long positionMs) {
        lastPosition = positionMs;
        for (long[] s : segments) {
            if (positionMs >= s[1] && positionMs < s[2] && !bypass.contains(s[0])) {
                bypass.add(s[0]);
            }
        }
    }

    public void disable(long segmentId) {
        if (!bypass.contains(segmentId)) {
            bypass.add(segmentId);
        }
    }
}
