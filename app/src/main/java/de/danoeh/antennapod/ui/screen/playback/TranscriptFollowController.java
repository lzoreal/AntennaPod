package de.danoeh.antennapod.ui.screen.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;

/** Keeps the current sentence on the reading line, animating or snapping as needed. */
final class TranscriptFollowController {
    private static final String TAG = "AsrJitter";
    private static final long FOLLOW_CLOCK_INTERVAL_MS = 250;
    private static final int MAX_NO_PROGRESS_FRAMES = 3;

    interface Host {
        @Nullable
        Context getContext();

        boolean isAdded();

        Lifecycle.State getLifecycleState();

        @Nullable
        TranscriptAdapter getAdapter();

        long getLastPositionMs();

        boolean isUserScrolling();

        void setUserScrolling(boolean scrolling);

        boolean isPendingSnapToHighlight();

        void setPendingSnapToHighlight(boolean pending);

        void setLastScrolledPosition(int position);

        void onFollowSnap();
    }

    private final Host host;
    @Nullable
    private TranscriptPageFragmentBinding binding;
    private float followDensity = 1f;
    private boolean followListenerRegistered = false;
    private boolean followAnimating = false;
    private long followStartMs = 0;
    private long followDurationMs = 0;
    private int followStartError = 0;
    private int followScrolled = 0;
    private int followNoProgressFrames = 0;
    private int followTargetPosition = -1;
    private int followTargetStart = -1;
    private int followTargetEnd = -1;
    private int animDySign;
    private int frameCount;
    private long lastFrameLogMs;
    private long lastLayoutRecoveryMs;
    private long lastFollowSkipLogMs;
    private ViewTreeObserver followObserver;
    private final ViewTreeObserver.OnPreDrawListener followPreDrawListener = () -> {
        if (!canAutoFollow()) {
            stopFrame();
            return true;
        }
        long frameNow = SystemClock.elapsedRealtime();
        frameCount++;
        if (frameNow - lastFrameLogMs >= 1000) {
            Log.d(TAG, "follow frames/s=" + frameCount + " animating=" + followAnimating
                    + " target=" + followTargetPosition + " scrolled=" + followScrolled);
            frameCount = 0;
            lastFrameLogMs = frameNow;
        }
        if (!scrollTowardsCurrentSentence()) {
            stopFrame();
        }
        return true;
    };
    private final Rect followVisibleRect = new Rect();
    private boolean followClockActive = false;
    /**
     * The pre-draw listener only runs when the list is drawn, so the follow needs a frame source
     * of its own. The highlight ticker provides one only while the fragment is resumed; the page
     * can be visible and drawing while it is merely started (ViewPager2 page behind an overlay),
     * which used to freeze the follow until an ASR reload forced a layout.
     */
    private final Runnable followClock = new Runnable() {
        @Override
        public void run() {
            if (binding == null || !followClockActive) {
                followClockActive = false;
                return;
            }
            RecyclerView recyclerView = binding.recyclerView;
            if (!isFollowViewVisible(recyclerView)) {
                followClockActive = false;
                return;
            }
            scheduleFrame();
            recyclerView.postDelayed(this, FOLLOW_CLOCK_INTERVAL_MS);
        }
    };

    private final int[] rootLocation = new int[2];
    private final int[] listLocation = new int[2];

    TranscriptFollowController(Host host) {
        this.host = host;
    }

    void attach(TranscriptPageFragmentBinding viewBinding) {
        binding = viewBinding;
        Context context = host.getContext();
        followDensity = context == null ? 1f
                : context.getResources().getDisplayMetrics().density;
    }

    void detach() {
        stopFrame();
        stopClock();
        binding = null;
    }

    void startClock() {
        if (followClockActive || binding == null) {
            return;
        }
        followClockActive = true;
        binding.recyclerView.post(followClock);
    }

    void stopClock() {
        followClockActive = false;
        if (binding != null) {
            binding.recyclerView.removeCallbacks(followClock);
        }
    }

    void scheduleFrame() {
        if (binding == null || !canAutoFollow()) {
            return;
        }
        Log.d(TAG, "scheduleFrame clock=" + followClockActive
                + " pendingSnap=" + host.isPendingSnapToHighlight()
                + " scrollState=" + binding.recyclerView.getScrollState());
        ViewTreeObserver observer = binding.recyclerView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        if (followListenerRegistered) {
            // Re-register every time: a re-attached view can hand out a new observer without
            // the old listener following, and there is no way to query the observer for it.
            if (followObserver != null && followObserver.isAlive()) {
                followObserver.removeOnPreDrawListener(followPreDrawListener);
            }
            followListenerRegistered = false;
        }
        observer.addOnPreDrawListener(followPreDrawListener);
        followObserver = observer;
        followListenerRegistered = true;
        binding.recyclerView.postInvalidateOnAnimation();
        if (!followClockActive) {
            // Self-heal: the clock stops itself when the page goes off screen, so restart it
            // whenever a new follow target needs it.
            startClock();
        }
    }

    void stopFrame() {
        followAnimating = false;
        if (!followListenerRegistered) {
            return;
        }
        followListenerRegistered = false;
        if (followObserver != null && followObserver.isAlive()) {
            followObserver.removeOnPreDrawListener(followPreDrawListener);
        }
        followObserver = null;
    }

    void scrollToPosition(int position) {
        scrollToPosition(position, false);
    }

    void scrollToPosition(int position, boolean snap) {
        host.setLastScrolledPosition(position);
        if (binding == null) {
            return;
        }
        RecyclerView recyclerView = binding.recyclerView;
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) {
            return;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        Log.d("AsrSync", "scrollTo pos=" + position + " snap=" + snap + " first=" + first
                + " last=" + last);
        if (first != RecyclerView.NO_POSITION && position >= first && position <= last) {
            host.setPendingSnapToHighlight(snap);
            followAnimating = false;
            scheduleFrame();
            return;
        }

        // The row is not laid out, so an animated seek would crawl over an unknown distance.
        // Jump there and centre the sentence in one step once the row has been measured.
        stopFrame();
        recyclerView.stopScroll();
        host.setPendingSnapToHighlight(true);
        host.onFollowSnap();
        recyclerView.scrollToPosition(position);
        scheduleFrame();
    }

    void returnToCurrentSentence() {
        host.setUserScrolling(false);
        updateBackToCurrentVisibility();
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null) {
            return;
        }
        int position = adapter.getHighlightPosition();
        StackTraceElement caller = new Throwable().getStackTrace()[1];
        Log.d(TAG, "returnToCurrent pos=" + position + " caller=."
                + caller.getMethodName() + ":" + caller.getLineNumber()
                + " pendingSnap=" + host.isPendingSnapToHighlight());
        if (position >= 0) {
            scrollToPosition(position, true);
        }
    }

    void updateBackToCurrentVisibility() {
        if (binding == null) {
            return;
        }
        binding.btnBackToCurrent.setVisibility(
                host.isUserScrolling() ? View.VISIBLE : View.GONE);
    }

    /** Vertical centre of the window, expressed in RecyclerView coordinates. */
    int readingLinePx(RecyclerView recyclerView) {
        View root = recyclerView.getRootView();
        root.getLocationOnScreen(rootLocation);
        recyclerView.getLocationOnScreen(listLocation);
        return rootLocation[1] + Math.round(
                root.getHeight() * TranscriptFollowMotion.ANCHOR_RATIO) - listLocation[1];
    }

    private boolean canAutoFollow() {
        boolean visible = binding != null && binding.getRoot().isShown();
        TranscriptAdapter adapter = host.getAdapter();
        boolean can = host.isAdded() && visible && adapter != null
                && !host.isUserScrolling()
                && binding != null && isFollowViewVisible(binding.recyclerView)
                && binding.recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                && adapter.getHighlightPosition() >= 0
                && !adapter.hasTextSelection();
        if (!can) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastFollowSkipLogMs > 2000) {
                lastFollowSkipLogMs = now;
                Log.d("AsrFollow", "skip added=" + host.isAdded() + " visible=" + visible
                        + " life=" + host.getLifecycleState()
                        + " binding=" + (binding != null) + " adapter=" + (adapter != null)
                        + " userScrolling=" + host.isUserScrolling()
                        + " state=" + (binding == null ? -1
                        : binding.recyclerView.getScrollState())
                        + " pos=" + (adapter == null ? -1 : adapter.getHighlightPosition()));
            }
        }
        return can;
    }

    /** True only while this pager page is actually on screen, not just attached. */
    private boolean isFollowViewVisible(RecyclerView recyclerView) {
        if (!recyclerView.isAttachedToWindow()
                || recyclerView.getWindowVisibility() != View.VISIBLE
                || !recyclerView.isShown()) {
            return false;
        }
        if (!recyclerView.getGlobalVisibleRect(followVisibleRect)
                || followVisibleRect.isEmpty()) {
            return false;
        }
        int height = recyclerView.getHeight();
        return height <= 0 || followVisibleRect.height() >= height / 2;
    }

    private boolean scrollTowardsCurrentSentence() {
        TranscriptAdapter adapter = host.getAdapter();
        if (adapter == null || binding == null) {
            return false;
        }
        int position = adapter.getHighlightPosition();
        RecyclerView recyclerView = binding.recyclerView;
        int viewportHeight = recyclerView.getHeight();
        if (viewportHeight <= 0) {
            return false;
        }
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (!(holder instanceof TranscriptViewholder)) {
            followAnimating = false;
            LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
            Log.d(TAG, "no holder pos=" + position + " kids=" + recyclerView.getChildCount()
                    + " first=" + (lm == null ? -1 : lm.findFirstVisibleItemPosition())
                    + " last=" + (lm == null ? -1 : lm.findLastVisibleItemPosition())
                    + " n=" + adapter.getItemCount()
                    + " scroll=" + recyclerView.computeVerticalScrollOffset());
            // scrollToPosition() issued from a pre-draw pass only sets a pending position that
            // waits for the next unrelated layout (an ASR reload), so the view froze for tens of
            // seconds while the highlight row sat off screen. scrollBy() is applied immediately,
            // so step a whole viewport per frame until the row is laid out, then snap onto it.
            // Half the viewport cannot skip past an adjacent row (tallest generated row is
            // under the viewport height), so this converges without oscillating.
            int step = viewportHeight / 2;
            boolean stepped = stepTowardsRow(recyclerView, adapter, position, step);
            if (!stepped) {
                // The row is reported as visible by the layout manager but has no holder (its
                // absolute adapter position was invalidated by a pending update that was never
                // applied). Refresh so the next layout re-binds everything; do not give up.
                recoverStaleLayout(adapter);
            }
            return true;
        }
        int[] bounds = adapter.getSentenceBounds((TranscriptViewholder) holder);
        boolean snap = host.isPendingSnapToHighlight();
        host.setPendingSnapToHighlight(false);
        if (bounds == null) {
            if (!snap) {
                return false;
            }
            bounds = new int[]{0, holder.itemView.getHeight()};
        }
        int target = holder.itemView.getTop() + (bounds[0] + bounds[1]) / 2;
        int anchor = readingLinePx(recyclerView);
        int error = target - anchor;
        boolean rowChanged = position != followTargetPosition;
        boolean targetChanged = rowChanged
                || bounds[0] != followTargetStart
                || bounds[1] != followTargetEnd;
        if (targetChanged) {
            Log.d("AsrFollow", "target change pos=" + position
                    + " top=" + holder.itemView.getTop() + " h=" + holder.itemView.getHeight()
                    + " bounds=" + bounds[0] + "-" + bounds[1] + " target=" + target
                    + " anchor=" + anchor
                    + " err=" + error + " last=" + host.getLastPositionMs()
                    + " scroll=" + recyclerView.computeVerticalScrollOffset()
                    + " canDown=" + recyclerView.canScrollVertically(1)
                    + " n=" + adapter.getItemCount());
            followTargetPosition = position;
            followTargetStart = bounds[0];
            followTargetEnd = bounds[1];
            followAnimating = false;
            followNoProgressFrames = 0;
        }
        if (snap || rowChanged) {
            // A new row is read (or the user jumped): land straight on the reading line.
            // A new sentence inside the same row is only about a line height away, so it is
            // animated below; snapping it made the list jump once per sentence.
            followAnimating = false;
            if (!TranscriptFollowMotion.isWithinStopThreshold(error, followDensity)) {
                snapToError(recyclerView, error);
            }
            return false;
        }
        if (TranscriptFollowMotion.isWithinStopThreshold(error, followDensity)) {
            followAnimating = false;
            return false;
        }
        if (TranscriptFollowMotion.shouldSnap(error, viewportHeight)) {
            followAnimating = false;
            snapToError(recyclerView, error);
            return false;
        }
        if (!followAnimating && !TranscriptFollowMotion.shouldStart(error, followDensity)) {
            return false;
        }
        if (!areFollowAnimationsEnabled()) {
            followAnimating = false;
            snapToError(recyclerView, error);
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (!followAnimating) {
            followAnimating = true;
            followStartMs = now;
            followDurationMs = TranscriptFollowMotion.durationFor(error, followDensity);
            followStartError = error;
            followScrolled = 0;
            followNoProgressFrames = 0;
            animDySign = 0;
            Log.d(TAG, "anim start err=" + error + " dur=" + followDurationMs
                    + " viewport=" + viewportHeight);
        }
        int dy = TranscriptFollowMotion.nextScrollDelta(
                followStartError, now - followStartMs, followDurationMs, followScrolled);
        if (dy != 0) {
            if (dy < 0 && !recyclerView.canScrollVertically(-1)) {
                followAnimating = false;
                return false;
            }
            if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                followAnimating = false;
                return false;
            }
            if (Integer.signum(dy) != animDySign) {
                animDySign = Integer.signum(dy);
                Log.d(TAG, "anim dir flip dy=" + dy + " scrolled=" + followScrolled
                        + " err=" + error + " scroll="
                        + recyclerView.computeVerticalScrollOffset());
            }
            int applied = scrollWithoutNestedScroll(recyclerView, dy);
            if (applied == 0) {
                // The list did not move at all (for example because the scroll was handed to the
                // player's bottom sheet instead). Restarting the animation then spins at frame
                // rate without ever reaching the target, which is the visible shaking.
                if (++followNoProgressFrames >= MAX_NO_PROGRESS_FRAMES) {
                    Log.d(TAG, "anim stalled dy=" + dy + " err=" + error
                            + " scroll=" + recyclerView.computeVerticalScrollOffset());
                    followAnimating = false;
                    return false;
                }
            } else {
                followNoProgressFrames = 0;
            }
            followScrolled += dy;
        }
        if (now - followStartMs >= followDurationMs) {
            followAnimating = false;
            Log.d(TAG, "anim end scrolled=" + followScrolled + " err=" + error
                    + " scroll=" + recyclerView.computeVerticalScrollOffset());
            return false;
        }
        recyclerView.postInvalidateOnAnimation();
        return true;
    }

    private void snapToError(RecyclerView recyclerView, int error) {
        if (error != 0) {
            host.onFollowSnap();
        }
        if (error < 0 && !recyclerView.canScrollVertically(-1)) {
            return;
        }
        if (error > 0 && !recyclerView.canScrollVertically(1)) {
            return;
        }
        if (error != 0) {
            Log.d("AsrSync", "snap error=" + error + " scroll="
                    + recyclerView.computeVerticalScrollOffset());
            scrollWithoutNestedScroll(recyclerView, error);
        }
    }

    /**
     * The list lives inside the player's bottom sheet, which also implements nested scrolling.
     * A programmatic {@link RecyclerView#scrollBy} dispatches a TYPE_TOUCH nested scroll, so the
     * sheet can swallow the distance and slide the player instead of moving the transcript.
     *
     * @return the scroll offset distance that was actually applied
     */
    static int scrollWithoutNestedScroll(RecyclerView recyclerView, int dy) {
        final int before = recyclerView.computeVerticalScrollOffset();
        boolean nestedScrolling = recyclerView.isNestedScrollingEnabled();
        if (nestedScrolling) {
            recyclerView.setNestedScrollingEnabled(false);
        }
        recyclerView.scrollBy(0, dy);
        if (nestedScrolling) {
            recyclerView.setNestedScrollingEnabled(true);
        }
        return recyclerView.computeVerticalScrollOffset() - before;
    }

    private boolean areFollowAnimationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return true;
    }

    private void recoverStaleLayout(TranscriptAdapter adapter) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLayoutRecoveryMs < 1000) {
            return;
        }
        lastLayoutRecoveryMs = now;
        if (binding == null) {
            return;
        }
        // notifyDataSetChanged() is not enough here: the RecyclerView keeps the old children
        // with invalidated adapter positions and never runs the layout that would re-bind them,
        // so touch scrolling no longer works either. Re-attach the adapter to force a full
        // detach/re-bind and a real layout pass.
        Log.d("AsrFollow", "recover stale layout: reattach adapter");
        binding.recyclerView.setAdapter(null);
        binding.recyclerView.setAdapter(adapter);
        host.setPendingSnapToHighlight(true);
    }

    private boolean stepTowardsRow(RecyclerView recyclerView, TranscriptAdapter adapter,
                                   int position, int stepPx) {
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) {
            return false;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) {
            // The children are still drawn but their adapter positions were invalidated by a
            // data-set change that never got laid out; scrollBy and scrollToPosition both do
            // nothing in this state. Refresh the adapter so the next layout re-binds them.
            recoverStaleLayout(adapter);
            return true;
        }
        int direction = position < first ? -1 : position > last ? 1 : 0;
        if (direction == 0) {
            return false;
        }
        if (direction < 0 && !recyclerView.canScrollVertically(-1)) {
            return false;
        }
        if (direction > 0 && !recyclerView.canScrollVertically(1)) {
            return false;
        }
        Log.d(TAG, "step dir=" + direction + " by=" + stepPx + " pos=" + position
                + " first=" + first + " last=" + last + " scroll="
                + recyclerView.computeVerticalScrollOffset());
        scrollWithoutNestedScroll(recyclerView, direction * stepPx);
        return true;
    }
}
