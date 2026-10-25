package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.playback.audio.AudioPlayerFragment;
import de.danoeh.antennapod.ui.transcript.TranscriptTextUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TranscriptPageFragment extends Fragment implements TranscriptAdapter.SegmentClickListener {
    public static final String TAG = "TranscriptPageFragment";
    private TranscriptPageFragmentBinding viewBinding;
    private TranscriptAdapter adapter;
    private Disposable disposable;
    private FeedMedia currentMedia;
    private String loadedTranscriptUrl;
    private String loadedTranscriptType;
    private boolean userScrolling = false;
    private boolean pendingSnapToHighlight = false;
    private int lastScrolledPosition = -1;
    private float followDensity = 1f;
    private boolean followListenerRegistered = false;
    private boolean followMoving = false;
    private long lastFollowFrameMs = 0;
    private final ViewTreeObserver.OnPreDrawListener followPreDrawListener = () -> {
        if (!canAutoFollow()) {
            stopFollowFrame();
            return true;
        }
        if (!scrollTowardsCurrentSentence()) {
            stopFollowFrame();
        }
        return true;
    };
    private long lastPositionMs = 0;
    private boolean controlsBarVisibleBeforeImmersive = false;
    private boolean immersiveMode = false;
    private int immersiveTopInset = 0;
    private static final float FOLLOW_START_DP = 64f;
    private static final float FOLLOW_STOP_DP = 2f;
    private static final float FOLLOW_MAX_SPEED_DP_PER_SEC = 500f;
    private static final float FOLLOW_TIME_CONSTANT_MS = 140f;
    private static final float FOLLOW_MAX_STEP_DP = 10f;
    private static final long HIGHLIGHT_UPDATE_INTERVAL_MS = 250;
    private static final int FONT_SIZE_MIN_STEP = UserPreferences.TRANSCRIPT_FONT_SIZE_MIN_STEP;
    private static final int FONT_SIZE_MAX_STEP = UserPreferences.TRANSCRIPT_FONT_SIZE_MAX_STEP;
    private static final int[] VERTICAL_SPACING_LABELS = {
            R.string.transcript_layout_compact,
            R.string.transcript_layout_standard,
            R.string.transcript_layout_loose};
    private boolean segmentLooping = false;
    private int loopSegmentIndex = -1;
    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController mediaController;
    private boolean highlightTickerActive = false;
    private boolean highlightTickerDriving = false;
    private final Runnable highlightTicker = new Runnable() {
        @Override
        public void run() {
            if (!highlightTickerActive) {
                return;
            }
            MediaController controller = mediaController;
            boolean playing = controller != null && controller.isConnected() && controller.isPlaying();
            if (playing) {
                long position = controller.getCurrentPosition();
                setHighlightTickerDriving(true);
                if (adapter != null) {
                    adapter.updatePlaybackPosition(position);
                }
                handlePlaybackPosition(position);
            } else if (highlightTickerDriving) {
                long position = controller != null && controller.isConnected()
                        ? controller.getCurrentPosition() : -1;
                setHighlightTickerDriving(false);
                if (position >= 0) {
                    if (adapter != null) {
                        adapter.updatePlaybackPosition(position);
                    }
                    handlePlaybackPosition(position);
                }
            }
            highlightHandler.postDelayed(this, HIGHLIGHT_UPDATE_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = TranscriptPageFragmentBinding.inflate(inflater, container, false);
        followDensity = getResources().getDisplayMetrics().density;

        adapter = new TranscriptAdapter(getContext(), this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        viewBinding.recyclerView.setLayoutManager(layoutManager);
        viewBinding.recyclerView.setAdapter(adapter);
        RecyclerView.ItemAnimator animator = viewBinding.recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                    pendingSnapToHighlight = false;
                    stopFollowFrame();
                    updateBackToCurrentVisibility();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleFollowFrame();
                }
            }
        });

        setupOffsetButtons();
        viewBinding.txtOffsetValue.setOnClickListener(v -> resetTimeOffset());
        viewBinding.btnOffsetToggle.setOnClickListener(v -> toggleOffsetBar());
        viewBinding.btnOffsetToggle.setContentDescription(
                getString(R.string.transcript_offset_toggle_description));
        viewBinding.btnTranslationToggle.setOnClickListener(v -> cycleLanguageMode());
        viewBinding.btnTranslationToggle.setContentDescription(getString(R.string.toggle_translation));
        viewBinding.btnLineBreakToggle.setOnClickListener(v -> toggleSentencePerLine());
        viewBinding.btnLayout.setOnClickListener(v -> toggleLayoutBar());
        viewBinding.btnLayout.setContentDescription(getString(R.string.transcript_layout_toggle_description));
        viewBinding.btnFontSizeDown.setOnClickListener(v -> adjustFontSize(-1));
        viewBinding.btnFontSizeUp.setOnClickListener(v -> adjustFontSize(1));
        viewBinding.btnFontSizeDown.setContentDescription(getString(R.string.transcript_font_smaller_description));
        viewBinding.btnFontSizeUp.setContentDescription(getString(R.string.transcript_font_larger_description));
        bindLayoutStepper(viewBinding.btnLineSpacingDown, viewBinding.btnLineSpacingUp,
                R.string.transcript_layout_line_spacing);
        bindLayoutStepper(viewBinding.btnSideMarginsDown, viewBinding.btnSideMarginsUp,
                R.string.transcript_layout_side_margins);
        bindLayoutStepper(viewBinding.btnVerticalSpacingDown, viewBinding.btnVerticalSpacingUp,
                R.string.transcript_layout_vertical_spacing);
        viewBinding.btnLineSpacingDown.setOnClickListener(v -> adjustLineSpacing(-1));
        viewBinding.btnLineSpacingUp.setOnClickListener(v -> adjustLineSpacing(1));
        viewBinding.btnSideMarginsDown.setOnClickListener(v -> adjustSideMargins(-1));
        viewBinding.btnSideMarginsUp.setOnClickListener(v -> adjustSideMargins(1));
        viewBinding.btnVerticalSpacingDown.setOnClickListener(v -> adjustVerticalSpacing(-1));
        viewBinding.btnVerticalSpacingUp.setOnClickListener(v -> adjustVerticalSpacing(1));
        viewBinding.btnLayoutReset.setOnClickListener(v -> resetTranscriptLayout());
        viewBinding.btnSegmentLoop.setOnClickListener(v -> toggleSegmentLoop());
        viewBinding.btnBackToCurrent.setOnClickListener(v -> returnToCurrentSentence());
        viewBinding.btnLineBreakToggle.setContentDescription(
                getString(R.string.transcript_line_break_description));
        viewBinding.btnImmersive.setOnClickListener(v -> {
            if (getParentFragment() instanceof AudioPlayerFragment) {
                ((AudioPlayerFragment) getParentFragment()).toggleImmersiveMode();
            }
        });
        updateOffsetLabel();
        updateTranslationToggle();
        updateSentenceToggle();
        updateOffsetToggle();
        updateLayoutControls();
        applyTranscriptLayout();
        updateSegmentLoopToggle();
        updateBackToCurrentVisibility();

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.getRoot(), (v, insets) -> {
            immersiveTopInset = Math.max(immersiveTopInset, Math.max(
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
                    insets.getInsets(WindowInsetsCompat.Type.statusBars()).top));
            if (immersiveMode) {
                v.setPadding(0, getImmersiveTopInset(), 0, 0);
            }
            return insets;
        });

        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        connectPlaybackController();
        if (adapter != null
                && adapter.getLanguageMode() != UserPreferences.getTranscriptLanguageMode()) {
            adapter.setLanguageMode(UserPreferences.getTranscriptLanguageMode());
        }
        updateOffsetLabel();
        loadTranscript();
    }

    @Override
    public void onResume() {
        super.onResume();
        highlightTickerActive = true;
        highlightHandler.post(highlightTicker);
        scheduleFollowFrame();
    }

    @Override
    public void onPause() {
        highlightTickerActive = false;
        setHighlightTickerDriving(false);
        highlightHandler.removeCallbacks(highlightTicker);
        stopFollowFrame();
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
        disconnectPlaybackController();
        EventBus.getDefault().unregister(this);
    }

    private void connectPlaybackController() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ListenableFuture<MediaController> future = PlaybackController.openMedia3Controller(context);
        controllerFuture = future;
        future.addListener(() -> {
            if (!isAdded() || future.isCancelled()) {
                return;
            }
            try {
                mediaController = future.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting media controller", e);
            }
        }, MoreExecutors.directExecutor());
    }

    private void setHighlightTickerDriving(boolean driving) {
        if (highlightTickerDriving == driving) {
            return;
        }
        highlightTickerDriving = driving;
        if (adapter != null) {
            adapter.setPositionEventsSuppressed(driving);
        }
    }

    private void disconnectPlaybackController() {
        highlightTickerActive = false;
        setHighlightTickerDriving(false);
        highlightHandler.removeCallbacks(highlightTicker);
        if (mediaController != null) {
            mediaController.release();
            mediaController = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopFollowFrame();
        if (viewBinding != null) {
            viewBinding.recyclerView.setAdapter(null);
        }
        viewBinding = null;
        adapter = null;
    }

    private void loadTranscript() {
        loadTranscript(false);
    }

    private void loadTranscript(boolean forceReload) {
        if (disposable != null) {
            disposable.dispose();
        }

        long currentMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (!forceReload && viewBinding != null && currentMedia != null
                && currentMedia.getId() == currentMediaId
                && adapter != null && adapter.getItemCount() > 0) {
            viewBinding.progLoading.setVisibility(View.GONE);
            viewBinding.emptyView.setVisibility(View.GONE);
            viewBinding.recyclerView.setVisibility(View.VISIBLE);
            if (!userScrolling) {
                long offset = UserPreferences.getTranscriptTimeOffset(currentMedia.getId());
                int pos = currentMedia.getTranscript().findSegmentIndexBefore(lastPositionMs - offset);
                adapter.setHighlightPosition(pos, lastPositionMs - offset);
                scrollToPosition(pos);
            }
            return;
        }

        if (viewBinding != null && !forceReload) {
            viewBinding.progLoading.setVisibility(View.VISIBLE);
            viewBinding.emptyView.setVisibility(View.GONE);
            viewBinding.recyclerView.setVisibility(View.GONE);
        }

        disposable = Maybe.create(emitter -> {
                    long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();

                    if (mediaId == 0) {
                        emitter.onComplete();
                        return;
                    }

                    Playable media = DBReader.getFeedMedia(mediaId);

                    if (media instanceof FeedMedia) {
                        FeedMedia feedMedia = (FeedMedia) media;

                        if (forceReload || !feedMedia.hasTranscript()
                                || feedMedia.getTranscript() == null) {
                            try {
                                feedMedia.setTranscript(TranscriptUtils.loadTranscript(feedMedia, false));
                            } catch (Exception e) {
                                Log.e(TAG, "TranscriptUtils.loadTranscript FAILED", e);
                            }
                        }

                        emitter.onSuccess(feedMedia);
                    } else {
                        emitter.onComplete();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        media -> onMediaLoaded((FeedMedia) media),
                        error -> {
                            Log.e(TAG, "subscribe onError", error);
                            showEmptyState();
                        },
                        this::showEmptyState
                );
    }

    private void onMediaLoaded(FeedMedia media) {
        if (viewBinding == null) {
            return;
        }
        if (currentMedia != null && currentMedia.getId() == media.getId()
                && media.getTranscript() != null && adapter != null
                && adapter.getItemCount() > 0
                && currentMedia.getTranscript() == media.getTranscript()) {
            currentMedia = media;
            updateLoadedTranscriptSource(media);
            viewBinding.progLoading.setVisibility(View.GONE);
            return;
        }
        currentMedia = media;
        updateLoadedTranscriptSource(media);
        viewBinding.progLoading.setVisibility(View.GONE);

        if (currentMedia.getTranscript() == null) {
            showEmptyState();
            return;
        }

        int segmentCount = currentMedia.getTranscript().getSegmentCount();

        if (segmentCount == 0) {
            showEmptyState();
            return;
        }

        viewBinding.emptyView.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.VISIBLE);

        adapter.setMedia(currentMedia);
        userScrolling = false;
        lastScrolledPosition = -1;
        segmentLooping = false;
        loopSegmentIndex = -1;
        lastPositionMs = Math.max(0, currentMedia.getPosition());
        long offset = UserPreferences.getTranscriptTimeOffset(currentMedia.getId());
        int initialPosition = currentMedia.getTranscript().findSegmentIndexBefore(lastPositionMs - offset);
        adapter.setHighlightPosition(initialPosition);
        scrollToPosition(initialPosition);
        updateBackToCurrentVisibility();
        updateOffsetLabel();
        updateTranslationToggle();
        updateSegmentLoopToggle();
    }

    private void showEmptyState() {
        if (viewBinding == null) {
            return;
        }
        if (adapter != null) {
            adapter.clear();
        }
        viewBinding.progLoading.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.GONE);
        viewBinding.emptyView.setVisibility(View.VISIBLE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (currentMedia == null || currentMedia.getId() != mediaId) {
            loadTranscript();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedItemEvent(FeedItemEvent event) {
        if (currentMedia == null) {
            return;
        }
        int index = FeedItemEvent.indexOfItemWithId(event.items, currentMedia.getItemId());
        if (index < 0) {
            return;
        }
        FeedItem updatedItem = event.items.get(index);
        boolean sourceChanged = !Objects.equals(updatedItem.getTranscriptUrl(), loadedTranscriptUrl)
                || !Objects.equals(updatedItem.getTranscriptType(), loadedTranscriptType);
        if (currentMedia.getTranscript() == null || sourceChanged) {
            loadTranscript(true);
        }
    }

    private void updateLoadedTranscriptSource(FeedMedia media) {
        FeedItem item = media.getItem();
        loadedTranscriptUrl = item == null ? null : item.getTranscriptUrl();
        loadedTranscriptType = item == null ? null : item.getTranscriptType();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListUpdateEvent(FeedListUpdateEvent event) {
        if (currentMedia == null || currentMedia.getItem() == null) {
            return;
        }
        if (event.contains(currentMedia.getItem().getFeedId())) {
            loadTranscript(true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackPositionEvent(PlaybackPositionEvent event) {
        if (highlightTickerDriving) {
            return;
        }
        handlePlaybackPosition(event.getPosition());
    }

    private void handlePlaybackPosition(long position) {
        if (position >= 0) {
            lastPositionMs = position;
        }
        if (currentMedia == null || currentMedia.getTranscript() == null || viewBinding == null) {
            return;
        }

        if (segmentLooping && position >= 0) {
            applySegmentLoop(position);
        }

        int pos = currentMedia.getTranscript().findSegmentIndexBefore(
                lastPositionMs - UserPreferences.getTranscriptTimeOffset(currentMedia.getId()));
        if (pos < 0) {
            return;
        }

        if (userScrolling) {
            return;
        }

        if (pos == lastScrolledPosition) {
            scheduleFollowFrame();
            return;
        }

        if (lastScrolledPosition >= 0 && Math.abs(pos - lastScrolledPosition) <= 1) {
            lastScrolledPosition = pos;
            pendingSnapToHighlight = false;
            scheduleFollowFrame();
            return;
        }

        scrollToPosition(pos);
    }

    private boolean canAutoFollow() {
        return isResumed() && viewBinding != null && adapter != null && !userScrolling
                && viewBinding.recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                && adapter.getHighlightPosition() >= 0;
    }

    private void scheduleFollowFrame() {
        if (viewBinding == null || followListenerRegistered || !canAutoFollow()) {
            return;
        }
        ViewTreeObserver observer = viewBinding.recyclerView.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        observer.addOnPreDrawListener(followPreDrawListener);
        followListenerRegistered = true;
        viewBinding.recyclerView.postInvalidateOnAnimation();
    }

    private void stopFollowFrame() {
        followMoving = false;
        lastFollowFrameMs = 0;
        if (!followListenerRegistered) {
            return;
        }
        followListenerRegistered = false;
        if (viewBinding == null) {
            return;
        }
        ViewTreeObserver observer = viewBinding.recyclerView.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(followPreDrawListener);
        }
    }

    private boolean scrollTowardsCurrentSentence() {
        int position = adapter.getHighlightPosition();
        RecyclerView recyclerView = viewBinding.recyclerView;
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (!(holder instanceof TranscriptViewholder)) {
            if (pendingSnapToHighlight) {
                // Keep the jump pending until the row is laid out (e.g. while the page is being
                // measured), then centre it on the next frame.
                recyclerView.scrollToPosition(position);
                return true;
            }
            return stepTowardsRow(recyclerView, position);
        }
        int[] bounds = adapter.getSentenceBounds((TranscriptViewholder) holder);
        boolean snap = pendingSnapToHighlight;
        pendingSnapToHighlight = false;
        if (bounds == null) {
            if (!snap) {
                return false;
            }
            bounds = new int[]{0, holder.itemView.getHeight()};
        }
        int target = holder.itemView.getTop() + (bounds[0] + bounds[1]) / 2;
        int error = target - recyclerView.getHeight() / 2;
        if (Math.abs(error) <= FOLLOW_STOP_DP * followDensity) {
            followMoving = false;
            return false;
        }
        if (snap) {
            followMoving = false;
            if (error < 0 && !recyclerView.canScrollVertically(-1)) {
                return false;
            }
            if (error > 0 && !recyclerView.canScrollVertically(1)) {
                return false;
            }
            recyclerView.scrollBy(0, error);
            return true;
        }
        if (!followMoving && Math.abs(error) < FOLLOW_START_DP * followDensity) {
            return false;
        }
        if (!followMoving) {
            followMoving = true;
            lastFollowFrameMs = 0;
        }
        long now = SystemClock.uptimeMillis();
        long dt = lastFollowFrameMs == 0
                ? 16L : Math.max(1L, Math.min(48L, now - lastFollowFrameMs));
        lastFollowFrameMs = now;
        float alpha = 1f - (float) Math.exp(-dt / FOLLOW_TIME_CONSTANT_MS);
        float maxStep = FOLLOW_MAX_SPEED_DP_PER_SEC * followDensity * dt / 1000f;
        float step = Math.max(-maxStep, Math.min(maxStep, error * alpha));
        int dy = Math.round(step);
        if (dy == 0) {
            dy = error > 0 ? 1 : -1;
        }
        if (dy < 0 && !recyclerView.canScrollVertically(-1)) {
            followMoving = false;
            return false;
        }
        if (dy > 0 && !recyclerView.canScrollVertically(1)) {
            followMoving = false;
            return false;
        }
        recyclerView.scrollBy(0, dy);
        return true;
    }

    private boolean stepTowardsRow(RecyclerView recyclerView, int position) {
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) {
            return false;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) {
            return false;
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
        recyclerView.scrollBy(0, direction * Math.round(FOLLOW_MAX_STEP_DP * followDensity));
        return true;
    }

    private void scrollToPosition(int pos) {
        scrollToPosition(pos, false);
    }

    private void scrollToPosition(int pos, boolean snap) {
        lastScrolledPosition = pos;
        if (viewBinding == null) {
            return;
        }
        RecyclerView recyclerView = viewBinding.recyclerView;
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) {
            return;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first != RecyclerView.NO_POSITION && pos >= first && pos <= last) {
            pendingSnapToHighlight = snap;
            followMoving = false;
            scheduleFollowFrame();
            return;
        }

        // The row is not laid out, so an animated seek would crawl over an unknown distance.
        // Jump there and centre the sentence in one step once the row has been measured.
        stopFollowFrame();
        recyclerView.stopScroll();
        pendingSnapToHighlight = true;
        recyclerView.scrollToPosition(pos);
        scheduleFollowFrame();
    }

    @Override
    public void onTranscriptClicked(int position, TranscriptSegment segment,
                                    long sentenceStartTime, long sentenceEndTime) {
        long mediaId = currentMedia == null ? 0 : currentMedia.getId();
        long offset = UserPreferences.getTranscriptTimeOffset(mediaId);
        long startTime = sentenceStartTime + offset;
        long endTime = sentenceEndTime + offset;

        PlaybackController.bindToMedia3Service(getContext(), controller -> {
            long currentPos = controller.getCurrentPosition();
            if (currentPos >= startTime && currentPos <= endTime) {
                if (controller.isPlaying()) {
                    controller.pause();
                } else {
                    controller.play();
                }
            } else {
                controller.seekTo(Math.max(0, startTime));
            }
        });

        userScrolling = false;
        updateBackToCurrentVisibility();
        if (segmentLooping) {
            loopSegmentIndex = position;
        }
    }

    private void returnToCurrentSentence() {
        userScrolling = false;
        updateBackToCurrentVisibility();
        if (adapter == null) {
            return;
        }
        int position = adapter.getHighlightPosition();
        if (position >= 0) {
            scrollToPosition(position, true);
        }
    }

    private void updateBackToCurrentVisibility() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.btnBackToCurrent.setVisibility(userScrolling ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onTranscriptWordSelected(int position, TranscriptSegment segment,
                                         String selectedText) {
        PlaybackController.bindToMedia3Service(getContext(), controller -> {
            if (controller.isPlaying()) {
                controller.pause();
            }
        });
    }
    private void toggleSegmentLoop() {
        if (segmentLooping) {
            stopSegmentLoop();
            return;
        }
        if (currentMedia == null || currentMedia.getTranscript() == null || adapter == null) {
            return;
        }
        int pos = adapter.getHighlightPosition();
        if (pos < 0) {
            return;
        }
        segmentLooping = true;
        loopSegmentIndex = pos;
        updateSegmentLoopToggle();
        if (getContext() != null) {
            Toast.makeText(getContext(), R.string.transcript_segment_loop_message,
                    Toast.LENGTH_SHORT).show();
        }
        applySegmentLoop(lastPositionMs);
    }

    private void stopSegmentLoop() {
        segmentLooping = false;
        loopSegmentIndex = -1;
        updateSegmentLoopToggle();
        if (getContext() != null) {
            Toast.makeText(getContext(), R.string.transcript_segment_loop_disabled_message,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSegmentLoopToggle() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.btnSegmentLoop.setAlpha(segmentLooping ? 1.0f : 0.5f);
        viewBinding.btnSegmentLoop.setSelected(segmentLooping);
    }

    private void applySegmentLoop(long position) {
        if (!segmentLooping) {
            return;
        }
        if (currentMedia == null || currentMedia.getTranscript() == null) {
            return;
        }
        long offset = UserPreferences.getTranscriptTimeOffset(currentMedia.getId());
        if (loopSegmentIndex < 0 || loopSegmentIndex >= currentMedia.getTranscript().getSegmentCount()) {
            stopSegmentLoop();
            return;
        }
        TranscriptSegment segment = currentMedia.getTranscript().getSegmentAt(loopSegmentIndex);
        long startTime = Math.max(0, segment.getStartTime() + offset);
        long endTime = segment.getEndTime() + offset;
        if (endTime <= startTime) {
            stopSegmentLoop();
            return;
        }
        if (position >= endTime) {
            PlaybackController.bindToMedia3Service(getContext(), controller ->
                    controller.seekTo((int) startTime));
        }
    }

    public void adjustTimeOffset(long deltaMs) {
        if (currentMedia == null) {
            return;
        }
        applyTimeOffset(UserPreferences.getTranscriptTimeOffset(currentMedia.getId()) + deltaMs);
    }

    private void resetTimeOffset() {
        if (currentMedia == null) {
            return;
        }
        applyTimeOffset(0);
    }

    private void applyTimeOffset(long offsetMs) {
        int duration = currentMedia.getDuration();
        if (duration > 0) {
            offsetMs = Math.max(-duration, Math.min(duration, offsetMs));
        }
        UserPreferences.setTranscriptTimeOffset(currentMedia.getId(), offsetMs);
        if (adapter != null && currentMedia.getTranscript() != null
                && currentMedia.getTranscript().getSegmentCount() > 0) {
            int pos = currentMedia.getTranscript().findSegmentIndexBefore(lastPositionMs - offsetMs);
            adapter.setHighlightPosition(pos, lastPositionMs - offsetMs);
            userScrolling = false;
            updateBackToCurrentVisibility();
            lastScrolledPosition = -1;
            scrollToPosition(pos, true);
        }
        updateOffsetLabel();
        if (getContext() != null) {
            Toast.makeText(getContext(),
                    getString(R.string.transcript_offset_message, formatTimeOffset(offsetMs)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateOffsetLabel() {
        if (viewBinding == null) {
            return;
        }
        long offsetMs = currentMedia == null
                ? 0 : UserPreferences.getTranscriptTimeOffset(currentMedia.getId());
        viewBinding.txtOffsetValue.setText(getString(R.string.transcript_offset_label, formatTimeOffset(offsetMs)));
        viewBinding.txtOffsetValue.setContentDescription(getString(R.string.transcript_offset_reset_description));
    }

    private void setupOffsetButtons() {
        bindFineOffsetButton(viewBinding.btnOffsetBack05, -500);
        bindFineOffsetButton(viewBinding.btnOffsetForward05, 500);
        bindOffsetButton(viewBinding.btnOffsetBack30, -30000);
        bindOffsetButton(viewBinding.btnOffsetBack10, -10000);
        bindOffsetButton(viewBinding.btnOffsetBack5, -5000);
        bindOffsetButton(viewBinding.btnOffsetForward5, 5000);
        bindOffsetButton(viewBinding.btnOffsetForward10, 10000);
        bindOffsetButton(viewBinding.btnOffsetForward30, 30000);
    }

    private void bindFineOffsetButton(TextView button, long deltaMs) {
        String seconds = String.format(Locale.getDefault(), "%.1f", Math.abs(deltaMs) / 1000.0);
        boolean backwards = deltaMs < 0;
        button.setText(getString(backwards
                ? R.string.transcript_offset_back_fine_format
                : R.string.transcript_offset_forward_fine_format, seconds));
        button.setContentDescription(getString(backwards
                ? R.string.transcript_offset_back_fine_description
                : R.string.transcript_offset_forward_fine_description, seconds));
        button.setOnClickListener(v -> adjustTimeOffset(deltaMs));
    }

    private void bindOffsetButton(TextView button, long deltaMs) {
        int seconds = (int) Math.abs(deltaMs / 1000);
        boolean backwards = deltaMs < 0;
        button.setText(getString(backwards
                ? R.string.transcript_offset_back_format
                : R.string.transcript_offset_forward_format, seconds));
        button.setContentDescription(getString(backwards
                ? R.string.transcript_offset_back_description
                : R.string.transcript_offset_forward_description, seconds));
        button.setOnClickListener(v -> adjustTimeOffset(deltaMs));
    }

    public void toggleOffsetBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.offsetControls.getVisibility() == View.VISIBLE;
        viewBinding.layoutControls.setVisibility(View.GONE);
        viewBinding.offsetControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateOffsetToggle();
        updateLayoutControls();
        updateOffsetLabel();
    }

    private void updateOffsetToggle() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.offsetControls.getVisibility() == View.VISIBLE;
        viewBinding.btnOffsetToggle.setAlpha(visible ? 1.0f : 0.5f);
    }

    private void toggleLayoutBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.layoutControls.getVisibility() == View.VISIBLE;
        viewBinding.offsetControls.setVisibility(View.GONE);
        viewBinding.layoutControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateOffsetToggle();
        updateLayoutControls();
    }

    private void updateLayoutControls() {
        if (viewBinding == null || adapter == null) {
            return;
        }
        int fontStep = adapter.getFontSizeStep();
        viewBinding.txtFontSizeValue.setText(getString(
                R.string.transcript_layout_font_size_value, 16 + fontStep * 2));
        int lineStep = UserPreferences.getTranscriptLineSpacingStep();
        viewBinding.txtLineSpacingValue.setText(getString(
                R.string.transcript_layout_line_spacing_value,
                new DecimalFormat("0.##").format(
                        UserPreferences.getTranscriptLineSpacingMultiplier())));
        int marginStep = UserPreferences.getTranscriptSideMarginsStep();
        viewBinding.txtSideMarginsValue.setText(getString(
                R.string.transcript_layout_margin_value,
                UserPreferences.getTranscriptSideMarginsDp()));
        int verticalStep = UserPreferences.getTranscriptVerticalSpacingStep();
        viewBinding.txtVerticalSpacingValue.setText(VERTICAL_SPACING_LABELS[verticalStep]);

        viewBinding.btnFontSizeDown.setAlpha(fontStep > FONT_SIZE_MIN_STEP ? 1.0f : 0.5f);
        viewBinding.btnFontSizeUp.setAlpha(fontStep < FONT_SIZE_MAX_STEP ? 1.0f : 0.5f);
        viewBinding.btnLineSpacingDown.setAlpha(
                lineStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        viewBinding.btnLineSpacingUp.setAlpha(
                lineStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        viewBinding.btnSideMarginsDown.setAlpha(
                marginStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        viewBinding.btnSideMarginsUp.setAlpha(
                marginStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        viewBinding.btnVerticalSpacingDown.setAlpha(
                verticalStep > UserPreferences.TRANSCRIPT_LAYOUT_MIN_STEP ? 1.0f : 0.5f);
        viewBinding.btnVerticalSpacingUp.setAlpha(
                verticalStep < UserPreferences.TRANSCRIPT_LAYOUT_MAX_STEP ? 1.0f : 0.5f);
        viewBinding.btnLayout.setAlpha(
                viewBinding.layoutControls.getVisibility() == View.VISIBLE ? 1.0f : 0.5f);
    }

    private void applyTranscriptLayout() {
        if (viewBinding == null) {
            return;
        }
        int padding = Math.round(UserPreferences.getTranscriptVerticalSpacingDp()
                * getResources().getDisplayMetrics().density);
        viewBinding.recyclerView.setPadding(0, padding, 0, padding);
        if (adapter != null) {
            adapter.refreshLayout();
        }
    }

    private void adjustLineSpacing(int delta) {
        UserPreferences.setTranscriptLineSpacingStep(
                UserPreferences.getTranscriptLineSpacingStep() + delta);
        applyTranscriptLayout();
        updateLayoutControls();
    }

    private void adjustSideMargins(int delta) {
        UserPreferences.setTranscriptSideMarginsStep(
                UserPreferences.getTranscriptSideMarginsStep() + delta);
        applyTranscriptLayout();
        updateLayoutControls();
    }

    private void adjustVerticalSpacing(int delta) {
        UserPreferences.setTranscriptVerticalSpacingStep(
                UserPreferences.getTranscriptVerticalSpacingStep() + delta);
        applyTranscriptLayout();
        updateLayoutControls();
    }

    private void resetTranscriptLayout() {
        if (adapter == null) {
            return;
        }
        adapter.setFontSizeStep(0);
        UserPreferences.setTranscriptLineSpacingStep(UserPreferences.TRANSCRIPT_LINE_SPACING_DEFAULT_STEP);
        UserPreferences.setTranscriptSideMarginsStep(UserPreferences.TRANSCRIPT_SIDE_MARGINS_DEFAULT_STEP);
        UserPreferences.setTranscriptVerticalSpacingStep(
                UserPreferences.TRANSCRIPT_VERTICAL_SPACING_DEFAULT_STEP);
        applyTranscriptLayout();
        updateLayoutControls();
    }

    private void bindLayoutStepper(TextView decrease, TextView increase, int labelRes) {
        String label = getString(labelRes);
        decrease.setContentDescription(getString(R.string.transcript_layout_decrease) + " " + label);
        increase.setContentDescription(getString(R.string.transcript_layout_increase) + " " + label);
    }

    private String formatTimeOffset(long offsetMs) {
        double seconds = offsetMs / 1000.0;
        String value = seconds == Math.rint(seconds)
                ? String.valueOf((long) seconds)
                : String.format(Locale.getDefault(), "%.1f", seconds);
        return getString(R.string.transcript_offset_value_format, (seconds > 0 ? "+" : "") + value);
    }

    // ===== Public methods called by AudioPlayerFragment =====

    public boolean isBilingual() {
        return currentMedia != null && currentMedia.getTranscript() != null
                && currentMedia.getTranscript().isBilingual();
    }

    public int getLanguageMode() {
        return adapter == null ? TranscriptAdapter.LANGUAGE_MODE_BOTH : adapter.getLanguageMode();
    }

    public void setLanguageMode(int mode) {
        if (adapter != null) {
            adapter.setLanguageMode(mode);
            updateTranslationToggle();
        }
    }

    public void cycleLanguageMode() {
        int next = (getLanguageMode() + 1) % 3;
        setLanguageMode(next);
    }

    public void toggleSentencePerLine() {
        if (adapter != null) {
            adapter.setSentencePerLine(!adapter.isSentencePerLine());
            updateSentenceToggle();
        }
    }

    private void adjustFontSize(int delta) {
        if (adapter == null) {
            return;
        }
        int step = adapter.getFontSizeStep();
        int next = Math.max(FONT_SIZE_MIN_STEP, Math.min(FONT_SIZE_MAX_STEP, step + delta));
        if (next != step) {
            adapter.setFontSizeStep(next);
            updateLayoutControls();
        }
    }

    private void updateSentenceToggle() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.btnLineBreakToggle.setAlpha(adapter != null && adapter.isSentencePerLine() ? 1.0f : 0.5f);
    }

    public void toggleControlsBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.controlsBar.getVisibility() == View.VISIBLE;
        if (visible) {
            hideControlPanels();
        }
        viewBinding.controlsBar.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void hideControlPanels() {
        viewBinding.offsetControls.setVisibility(View.GONE);
        viewBinding.layoutControls.setVisibility(View.GONE);
        updateOffsetToggle();
        updateLayoutControls();
    }

    public boolean isControlsBarVisible() {
        return viewBinding != null && viewBinding.controlsBar.getVisibility() == View.VISIBLE;
    }

    public void setImmersiveMode(boolean immersive) {
        if (viewBinding == null) {
            return;
        }
        immersiveMode = immersive;
        if (immersive) {
            controlsBarVisibleBeforeImmersive = viewBinding.controlsBar.getVisibility() == View.VISIBLE;
            hideControlPanels();
            viewBinding.controlsBar.setVisibility(View.GONE);
        } else {
            viewBinding.controlsBar.setVisibility(
                    controlsBarVisibleBeforeImmersive ? View.VISIBLE : View.GONE);
        }
        viewBinding.getRoot().setPadding(0, immersive ? getImmersiveTopInset() : 0, 0, 0);
        viewBinding.btnImmersive.setImageResource(
                immersive ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    private int getImmersiveTopInset() {
        if (immersiveTopInset == 0) {
            WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(viewBinding.getRoot());
            if (windowInsets != null) {
                immersiveTopInset = Math.max(
                        windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
                        windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top);
            }
        }
        return immersiveTopInset;
    }

    private void updateTranslationToggle() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.btnTranslationToggle.setVisibility(isBilingual() ? View.VISIBLE : View.GONE);
        viewBinding.btnTranslationToggle.setAlpha(getLanguageMode() == TranscriptAdapter.LANGUAGE_MODE_BOTH
                ? 1.0f : 0.5f);
    }
}
