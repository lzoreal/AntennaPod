package de.danoeh.antennapod.ui.screen.playback;

import android.util.Log;
import java.util.ArrayDeque;

import de.danoeh.antennapod.BuildConfig;

/**
 * Flags a transcript list that oscillates instead of settling. A healthy follow moves in one
 * direction per sentence; two writers fighting over the scroll offset show up as many direction
 * flips (or many large jumps) inside a short window, which is what made the page look like it
 * was shaking. The report is throttled and carries enough state to identify the writer.
 */
final class TranscriptJitterMonitor {
    private static final String TAG = "AsrJitter";
    private static final long WINDOW_MS = 2000;
    private static final int MIN_FLIPS = 4;
    private static final int MIN_FLIPS_TOTAL_PX = 200;
    private static final int MIN_LARGE_MOVES = 4;
    private static final int LARGE_MOVE_PX = 200;
    private static final long REPORT_INTERVAL_MS = 3000;
    private static final long EVALUATE_INTERVAL_MS = 100;
    private static final boolean DEBUG = BuildConfig.DEBUG;

    interface Host {
        String describeJitterState();
    }

    private static final class Move {
        final long timeMs;
        final int dy;

        Move(long timeMs, int dy) {
            this.timeMs = timeMs;
            this.dy = dy;
        }
    }

    private final Host host;
    private final ArrayDeque<Move> moves = new ArrayDeque<>();
    private long lastReportMs;
    private long lastEvaluateMs;
    private int scrollCount;
    private int snapCount;
    private int lastSecondScrollCount;
    private int lastSecondSnapCount;
    private long lastRateBucketMs;

    TranscriptJitterMonitor(Host host) {
        this.host = host;
    }

    void onScroll(int dy, long nowMs) {
        if (dy == 0) {
            return;
        }
        scrollCount++;
        if (!DEBUG) {
            return;
        }
        moves.addLast(new Move(nowMs, dy));
        while (!moves.isEmpty() && nowMs - moves.peekFirst().timeMs > WINDOW_MS) {
            moves.removeFirst();
        }
        evaluate(nowMs);
    }

    void onSnap() {
        snapCount++;
    }

    /** Logs the movement rate once per second so a quiet page can be told from a shaking one. */
    void onSecondTick(long nowMs) {
        if (!DEBUG || nowMs - lastRateBucketMs < 1000) {
            return;
        }
        lastRateBucketMs = nowMs;
        int scrolls = scrollCount - lastSecondScrollCount;
        int snaps = snapCount - lastSecondSnapCount;
        lastSecondScrollCount = scrollCount;
        lastSecondSnapCount = snapCount;
        Log.d(TAG, "movement/s scrolls=" + scrolls + " snaps=" + snaps + " "
                + host.describeJitterState());
    }

    private void evaluate(long nowMs) {
        if (moves.size() < 2 || nowMs - lastEvaluateMs < EVALUATE_INTERVAL_MS) {
            return;
        }
        lastEvaluateMs = nowMs;
        int flips = 0;
        int total = 0;
        int max = 0;
        int large = 0;
        int previousSign = 0;
        StringBuilder deltas = new StringBuilder();
        for (Move move : moves) {
            int sign = Integer.signum(move.dy);
            if (previousSign != 0 && sign != previousSign) {
                flips++;
            }
            previousSign = sign;
            int abs = Math.abs(move.dy);
            total += abs;
            max = Math.max(max, abs);
            if (abs >= LARGE_MOVE_PX) {
                large++;
            }
            if (deltas.length() < 160) {
                deltas.append(move.dy).append(',');
            }
        }
        boolean oscillating = flips >= MIN_FLIPS && total >= MIN_FLIPS_TOTAL_PX;
        boolean jumping = large >= MIN_LARGE_MOVES;
        if (!oscillating && !jumping) {
            return;
        }
        if (nowMs - lastReportMs < REPORT_INTERVAL_MS) {
            moves.clear();
            return;
        }
        lastReportMs = nowMs;
        Log.d(TAG, "JITTER flips=" + flips + " large=" + large + " total=" + total
                + "px max=" + max + "px window=" + (nowMs - moves.peekFirst().timeMs) + "ms"
                + " moves=" + moves.size() + " deltas=" + deltas
                + " | " + host.describeJitterState());
        moves.clear();
    }
}
