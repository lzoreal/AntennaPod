package de.danoeh.antennapod.ui.screen.playback;

final class TranscriptFollowMotion {
    static final float ANCHOR_RATIO = 0.45f;
    static final float START_DP = 8f;
    static final float STOP_DP = 2f;
    static final float SNAP_RATIO = 0.75f;
    static final long MIN_DURATION_MS = 320L;
    static final long MAX_DURATION_MS = 520L;

    private TranscriptFollowMotion() {
    }

    static int anchorY(int viewportHeightPx) {
        return Math.round(viewportHeightPx * ANCHOR_RATIO);
    }

    static int errorPx(int targetY, int viewportHeightPx) {
        return targetY - anchorY(viewportHeightPx);
    }

    static boolean isWithinStopThreshold(int errorPx, float density) {
        return Math.abs(errorPx) <= STOP_DP * density;
    }

    static boolean shouldStart(int errorPx, float density) {
        return Math.abs(errorPx) >= START_DP * density;
    }

    static boolean shouldSnap(int errorPx, int viewportHeightPx) {
        return Math.abs(errorPx) > SNAP_RATIO * viewportHeightPx;
    }

    static long durationFor(int errorPx, float density) {
        long duration = 260L + Math.round(Math.abs(errorPx) / density * 2.5f);
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, duration));
    }

    static float easedProgress(long elapsedMs, long durationMs) {
        if (durationMs <= 0L) {
            return 1f;
        }
        float t = Math.max(0f, Math.min(1f, elapsedMs / (float) durationMs));
        float remaining = 1f - t;
        return 1f - remaining * remaining * remaining;
    }

    static int scrolledDistance(int startErrorPx, long elapsedMs, long durationMs) {
        return Math.round(startErrorPx * easedProgress(elapsedMs, durationMs));
    }

    static int nextScrollDelta(int startErrorPx, long elapsedMs, long durationMs,
                               int alreadyScrolledPx) {
        return scrolledDistance(startErrorPx, elapsedMs, durationMs) - alreadyScrolledPx;
    }
}
