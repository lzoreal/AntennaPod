package de.danoeh.antennapod.ui.screen.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.DeviceInfo;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.playback.audio.AudioPlayerFragment;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptTiming;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
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
    private int lastListScreenY = -1;
    private long lastScrollInteractionMs;
    private int loadedSegmentCount;

    private final TranscriptFollowController followController =
            new TranscriptFollowController(new TranscriptFollowController.Host() {
                @Override
                public Context getContext() {
                    return TranscriptPageFragment.this.getContext();
                }

                @Override
                public boolean isAdded() {
                    return TranscriptPageFragment.this.isAdded();
                }

                @Override
                public Lifecycle.State getLifecycleState() {
                    return getLifecycle().getCurrentState();
                }

                @Override
                public TranscriptAdapter getAdapter() {
                    return adapter;
                }

                @Override
                public long getLastPositionMs() {
                    return lastPositionMs;
                }

                @Override
                public boolean isUserScrolling() {
                    return userScrolling;
                }

                @Override
                public void setUserScrolling(boolean scrolling) {
                    userScrolling = scrolling;
                }

                @Override
                public boolean isPendingSnapToHighlight() {
                    return pendingSnapToHighlight;
                }

                @Override
                public void setPendingSnapToHighlight(boolean pending) {
                    pendingSnapToHighlight = pending;
                }

                @Override
                public void setLastScrolledPosition(int position) {
                    lastScrolledPosition = position;
                }

                @Override
                public void onFollowSnap() {
                    jitterMonitor.onSnap();
                }
            });

    private final TranscriptJitterMonitor jitterMonitor =
            new TranscriptJitterMonitor(this::describeJitterState);

    private long lastPositionMs = 0;
    private volatile long lastRawPlayerPosition = -1;
    private long lastSyncLogMs;
    private int lastSyncLogSegment = -2;
    private boolean controlsBarVisibleBeforeImmersive = false;
    private boolean immersiveMode = false;
    private int immersiveTopInset = 0;
    private static final long HIGHLIGHT_UPDATE_INTERVAL_MS = 250;
    private static final long TICKER_STALE_MS = 1500;
    private static final long POSITION_BACKSTEP_TOLERANCE_MS = 1200;
    private long lastBackstepLogMs;
    private long lastTickLogMs;
    private long lastLoggedPosition = Long.MIN_VALUE;
    private int lastLoggedSegment = Integer.MIN_VALUE;
    private static final long WORD_HIGHLIGHT_UPDATE_INTERVAL_MS = 100;
    private static final String STATE_SEARCH_ACTIVE = "transcript_search_active";
    private static final String STATE_SEARCH_QUERY = "transcript_search_query";
    private static final String STATE_SEARCH_HIT = "transcript_search_hit";
    private static final String STATE_SEGMENT_LOOPING = "transcript_segment_looping";
    private static final String STATE_SYNC_PICK_MODE = "transcript_sync_pick_mode";
    private static final String STATE_IMMERSIVE = "transcript_immersive";
    private static final String STATE_USER_SCROLLING = "transcript_user_scrolling";
    private boolean segmentLooping = false;
    private int loopSegmentIndex = -1;
    private Bundle restoredState;
    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController mediaController;
    private boolean highlightTickerActive = false;
    private boolean highlightTickerDriving = false;
    private long lastTickerMs = 0;
    private final TranscriptAsrCoordinator asrCoordinator =
            new TranscriptAsrCoordinator(new TranscriptAsrCoordinator.Host() {
                @Override
                public Context getContext() {
                    return TranscriptPageFragment.this.getContext();
                }

                @Override
                public FeedMedia getCurrentMedia() {
                    return currentMedia;
                }

                @Override
                public TranscriptAdapter getAdapter() {
                    return adapter;
                }

                @Override
                public boolean isCasting() {
                    return TranscriptPageFragment.this.isCasting();
                }

                @Override
                public void reloadTranscript() {
                    loadTranscript(true);
                }

                @Override
                public void showToast(int resId) {
                    TranscriptPageFragment.this.showToast(resId);
                }

                @Override
                public void onGeneratedSegmentsAppended() {
                    followController.scheduleFrame();
                }
            });

    private final TranscriptLayoutController layoutController =
            new TranscriptLayoutController(new TranscriptLayoutController.Host() {
                @Override
                public Context getContext() {
                    return TranscriptPageFragment.this.getContext();
                }

                @Override
                public TranscriptAdapter getAdapter() {
                    return adapter;
                }
            });

    private final TranscriptSearchController searchController =
            new TranscriptSearchController(new TranscriptSearchController.Host() {
                @Override
                public Context getContext() {
                    return TranscriptPageFragment.this.getContext();
                }

                @Override
                public TranscriptAdapter getAdapter() {
                    return adapter;
                }

                @Override
                public void hideControlPanels() {
                    TranscriptPageFragment.this.hideControlPanels();
                }

                @Override
                public void stopFollowFrame() {
                    followController.stopFrame();
                }

                @Override
                public void scheduleFollowFrame() {
                    followController.scheduleFrame();
                }

                @Override
                public void setUserScrolling(boolean scrolling) {
                    userScrolling = scrolling;
                }

                @Override
                public void setPendingSnapToHighlight(boolean pending) {
                    pendingSnapToHighlight = pending;
                }

                @Override
                public void updateBackToCurrentVisibility() {
                    followController.updateBackToCurrentVisibility();
                }

                @Override
                public void scrollToPosition(int position, boolean snap) {
                    followController.scrollToPosition(position, snap);
                }

                @Override
                public int readingLinePx(RecyclerView recyclerView) {
                    return followController.readingLinePx(recyclerView);
                }
            });

    private final TranscriptPositionSource positionSource =
            new TranscriptPositionSource(new TranscriptPositionSource.Host() {
                @Override
                public boolean isAdded() {
                    return TranscriptPageFragment.this.isAdded();
                }

                @Override
                public FeedMedia getMedia() {
                    return currentMedia;
                }

                @Override
                public long getLastPositionMs() {
                    return lastPositionMs;
                }

                @Override
                public long getPlayerPositionOrNegative() {
                    MediaController controller = mediaController;
                    return controller != null && controller.isConnected()
                            ? controller.getCurrentPosition() : -1;
                }

                @Override
                public void post(Runnable runnable) {
                    highlightHandler.post(runnable);
                }

                @Override
                public void onScaleResolved(long mediaId) {
                    refreshPositionForTimeScale();
                }
            });

    private final TranscriptOffsetController offsetController =
            new TranscriptOffsetController(new TranscriptOffsetController.Host() {
                @Override
                public Context getContext() {
                    return TranscriptPageFragment.this.getContext();
                }

                @Override
                public FeedMedia getMedia() {
                    return currentMedia;
                }

                @Override
                public TranscriptAdapter getAdapter() {
                    return adapter;
                }

                @Override
                public long getLastPositionMs() {
                    return lastPositionMs;
                }

                @Override
                public void setLastPositionMs(long positionMs) {
                    lastPositionMs = positionMs;
                }

                @Override
                public long currentPlaybackPosition() {
                    return positionSource.currentPlaybackPosition();
                }

                @Override
                public void setUserScrolling(boolean scrolling) {
                    userScrolling = scrolling;
                }

                @Override
                public void updateBackToCurrentVisibility() {
                    followController.updateBackToCurrentVisibility();
                }

                @Override
                public void resetScrolledPosition() {
                    lastScrolledPosition = -1;
                }

                @Override
                public void scrollToPosition(int position, boolean snap) {
                    followController.scrollToPosition(position, snap);
                }

                @Override
                public void showToast(int resId) {
                    TranscriptPageFragment.this.showToast(resId);
                }

                @Override
                public void setToggleState(View view, int descriptionRes, boolean on) {
                    TranscriptPageFragment.this.setToggleState(view, descriptionRes, on);
                }

                @Override
                public void onLayoutPanelStateChanged() {
                    layoutController.updateControls();
                }
            });

    private final Runnable highlightTicker = new Runnable() {
        @Override
        public void run() {
            if (!highlightTickerActive) {
                return;
            }
            lastTickerMs = SystemClock.elapsedRealtime();
            MediaController controller = mediaController;
            boolean playing = controller != null && controller.isConnected() && controller.isPlaying();
            if (controller != null && controller.isConnected()) {
                // Polled here instead of on PlayerStatusEvent: the controller applies the new
                // state after that event is posted, so reading it there showed the opposite label.
                asrCoordinator.setPlaybackPaused(!playing);
            }
            if (SystemClock.elapsedRealtime() - lastTickLogMs >= 1000) {
                lastTickLogMs = SystemClock.elapsedRealtime();
                jitterMonitor.onSecondTick(lastTickLogMs);
                Log.d("AsrJitter", "tick playing=" + playing
                        + " rawLast=" + lastRawPlayerPosition
                        + " last=" + lastPositionMs
                        + " pos=" + (adapter == null ? -1 : adapter.getHighlightPosition())
                        + " userScrolling=" + userScrolling
                        + " tickerDriving=" + highlightTickerDriving
                        + " pageVisible=" + isResumed());
            }
            if (playing) {
                long rawPosition = smoothPlayingPosition(controller.getCurrentPosition());
                if (!isTransientZeroPosition(rawPosition)) {
                    lastRawPlayerPosition = rawPosition;
                    long position = positionSource.toTranscriptTime(rawPosition);
                    setHighlightTickerDriving(true);
                    if (adapter != null) {
                        adapter.updatePlaybackPosition(position);
                    }
                    handlePlaybackPosition(position);
                }
            } else {
                long playerPosition = controller != null && controller.isConnected()
                        ? controller.getCurrentPosition() : -1;
                if (playerPosition >= 0 && playerPosition != lastRawPlayerPosition
                        && !isTransientZeroPosition(playerPosition)) {
                    lastRawPlayerPosition = playerPosition;
                    long position = positionSource.toTranscriptTime(playerPosition);
                    setHighlightTickerDriving(true);
                    if (adapter != null) {
                        adapter.updatePlaybackPosition(position);
                    }
                    handlePlaybackPosition(position);
                }
                setHighlightTickerDriving(controller != null && controller.isConnected());
            }
            // Keep the follow frame alive: the list can be static while the current row moved,
            // and a re-attached view may have replaced the observer.
            followController.scheduleFrame();
            highlightHandler.postDelayed(this, highlightUpdateInterval());
        }
    };

    /**
     * MediaController interpolates between session updates; at 1.5x a late update can step the
     * reported position back by up to a second, which flips the highlighted sentence back a line
     * and then forward again. Ignore those small back-steps while playing.
     */
    private long smoothPlayingPosition(long position) {
        long previous = lastRawPlayerPosition;
        if (position < 0 || previous < 0 || position >= previous
                || previous - position > POSITION_BACKSTEP_TOLERANCE_MS) {
            return position;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackstepLogMs > 2000) {
            lastBackstepLogMs = now;
            Log.d("AsrSync", "ignored position back-step " + (previous - position) + "ms");
        }
        return previous;
    }

    /**
     * A freshly connected MediaController reports 0 until the session state arrives. Treating
     * that as a real position sent the highlight to the first segment and back a few seconds
     * later, which looked like the list shaking when the transcript page was opened.
     */
    private boolean isTransientZeroPosition(long playerPosition) {
        boolean transientZero = playerPosition == 0
                && lastPositionMs > POSITION_BACKSTEP_TOLERANCE_MS;
        if (transientZero) {
            Log.d("AsrJitter", "ignore transient zero, last=" + lastPositionMs
                    + " rawLast=" + lastRawPlayerPosition);
        }
        return transientZero;
    }

    private long highlightUpdateInterval() {
        return currentMedia != null && currentMedia.getTranscript() != null
                && currentMedia.getTranscript().hasWordTimings()
                ? WORD_HIGHLIGHT_UPDATE_INTERVAL_MS : HIGHLIGHT_UPDATE_INTERVAL_MS;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = TranscriptPageFragmentBinding.inflate(inflater, container, false);
        asrCoordinator.attach(viewBinding);
        layoutController.attach(viewBinding);
        offsetController.attach(viewBinding);
        searchController.attach(viewBinding);
        restoredState = savedInstanceState;
        followController.attach(viewBinding);

        adapter = new TranscriptAdapter(getContext(), this);
        adapter.setFollowRequester(followController::scheduleFrame);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        viewBinding.recyclerView.setLayoutManager(layoutManager);
        viewBinding.recyclerView.setAdapter(adapter);
        viewBinding.recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                // Only the last row carries the gap; TranscriptAdapter must invalidate the
                // decorations after every insert or the old last row keeps a blank gap.
                int position = parent.getChildAdapterPosition(view);
                if (adapter != null && position == adapter.getItemCount() - 1) {
                    outRect.bottom = layoutController.getFollowBottomSpace();
                }
            }
        });
        // Do not put an ItemAnimator back: with the last-row footer gap above, a data-set change
        // makes RecyclerView lay every visible row out one footer gap lower and then translate it
        // back, so appending a segment visibly shakes the list (see TranscriptInsertStabilityTest).
        viewBinding.recyclerView.setItemAnimator(null);

        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    lastScrollInteractionMs = SystemClock.elapsedRealtime();
                }
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                    pendingSnapToHighlight = false;
                    followController.stopFrame();
                    searchController.stopScroll();
                    followController.updateBackToCurrentVisibility();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (userScrolling && adapter != null
                            && viewBinding.recyclerView.getLayoutManager()
                            instanceof LinearLayoutManager) {
                        LinearLayoutManager lm = (LinearLayoutManager)
                                viewBinding.recyclerView.getLayoutManager();
                        int first = lm.findFirstVisibleItemPosition();
                        int last = lm.findLastVisibleItemPosition();
                        int pos = adapter.getHighlightPosition();
                        if (first != RecyclerView.NO_POSITION && pos >= first && pos <= last) {
                            // A page swipe can drag the list a little; keep following while the
                            // highlighted row is still on screen.
                            userScrolling = false;
                            followController.updateBackToCurrentVisibility();
                        }
                    }
                    followController.scheduleFrame();
                }
            }
        });

        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_DRAGGING) {
                    jitterMonitor.onScroll(dy, SystemClock.elapsedRealtime());
                }
            }
        });

        viewBinding.pickModeBanner.setOnClickListener(
                v -> offsetController.setSyncPickMode(false));
        viewBinding.txtOffsetValue.setOnClickListener(v -> offsetController.reset());
        viewBinding.btnOffsetReset.setOnClickListener(v -> offsetController.reset());
        viewBinding.btnOffsetToggle.setOnClickListener(v -> offsetController.toggleBar());
        viewBinding.btnOffsetToggle.setContentDescription(
                getString(R.string.transcript_offset_toggle_description));
        viewBinding.btnLayout.setOnClickListener(v -> toggleLayoutBar());
        viewBinding.btnLayout.setContentDescription(getString(R.string.transcript_layout_toggle_description));
        viewBinding.btnImmersive.setOnClickListener(v -> {
            if (getParentFragment() instanceof AudioPlayerFragment) {
                ((AudioPlayerFragment) getParentFragment()).toggleImmersiveMode();
            }
        });
        viewBinding.btnMore.setOnClickListener(v -> toggleMoreBar());
        viewBinding.languageGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                int mode = languageModeForButton(checkedId);
                if (mode != getLanguageMode()) {
                    setLanguageMode(mode);
                }
            }
        });
        viewBinding.switchSentencePerLine.setOnCheckedChangeListener((button, checked) -> {
            if (adapter != null && adapter.isSentencePerLine() != checked) {
                toggleSentencePerLine();
            }
        });
        viewBinding.switchSegmentLoop.setOnCheckedChangeListener((button, checked) -> {
            if (segmentLooping != checked) {
                toggleSegmentLoop();
            }
        });
        viewBinding.btnBackToCurrent.setOnClickListener(v -> followController.returnToCurrentSentence());
        viewBinding.btnRetryTranscript.setOnClickListener(v -> {
            viewBinding.progLoading.setVisibility(View.VISIBLE);
            viewBinding.emptyState.setVisibility(View.GONE);
            loadTranscript(true);
        });
        viewBinding.btnGenerateTranscript.setOnClickListener(v -> asrCoordinator.startGeneration());
        viewBinding.btnContinueTranscript.setOnClickListener(v -> asrCoordinator.startGeneration());
        viewBinding.btnCancelTranscript.setOnClickListener(v -> asrCoordinator.cancelGeneration());
        offsetController.updateLabel();
        updateTranslationToggle();
        updateSentenceToggle();
        offsetController.updateToggle();
        layoutController.updateControls();
        layoutController.applyLayout();
        viewBinding.btnMore.setAlpha(0.5f);
        viewBinding.btnImmersive.setAlpha(0.5f);
        viewBinding.recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            // A header (controls bar, search bar) or the whole page (immersive mode) moving
            // shifts the text on screen. Scroll by the same distance so the current line stays
            // where it is instead of being re-centred by the follow.
            int[] screenLocation = new int[2];
            v.getLocationOnScreen(screenLocation);
            int shift = lastListScreenY < 0 ? 0 : screenLocation[1] - lastListScreenY;
            lastListScreenY = screenLocation[1];
            if (Math.abs(shift) >= 2) {
                followController.stopFrame();
                TranscriptFollowController.scrollWithoutNestedScroll(
                        (RecyclerView) v, shift);
                followController.scheduleFrame();
            }
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) {
                ViewParent parent = viewBinding.getRoot().getParent();
                Log.d("AsrJitter", "list layout " + (oldRight - oldLeft) + "x"
                        + (oldBottom - oldTop) + " -> " + (right - left) + "x"
                        + (bottom - top) + " page=" + viewBinding.getRoot().getHeight()
                        + " pageParent=" + (parent instanceof View ? ((View) parent).getHeight() : -1)
                        + " shift=" + shift);
                layoutController.applyLayout();
            }
        });
        updateSegmentLoopToggle();
        followController.updateBackToCurrentVisibility();

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
        Log.d("AsrJitter", "onStart hash=" + hashCode());
        EventBus.getDefault().register(this);
        connectPlaybackController();
        if (adapter != null
                && adapter.getLanguageMode() != UserPreferences.getTranscriptLanguageMode()) {
            adapter.setLanguageMode(UserPreferences.getTranscriptLanguageMode());
        }
        offsetController.updateLabel();
        loadTranscript();
        asrCoordinator.syncRunningState();
        followController.startClock();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d("AsrJitter", "onResume hash=" + hashCode());
        highlightTickerActive = true;
        highlightHandler.post(highlightTicker);
        followController.startClock();
        followController.scheduleFrame();
    }

    @Override
    public void onPause() {
        Log.d("AsrJitter", "onPause hash=" + hashCode());
        highlightTickerActive = false;
        setHighlightTickerDriving(false);
        highlightHandler.removeCallbacks(highlightTicker);
        searchController.pause();
        followController.stopFrame();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.d("AsrJitter", "onStop hash=" + hashCode());
        super.onStop();
        followController.stopClock();
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
                Log.d("AsrJitter", "controller connected hash=" + hashCode());
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
        Log.d("AsrJitter", "controller released hash=" + hashCode());
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
    public void onDestroy() {
        positionSource.shutdown();
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        followController.stopClock();
        followController.stopFrame();
        asrCoordinator.detach();
        layoutController.detach();
        offsetController.detach();
        followController.detach();
        if (viewBinding != null) {
            viewBinding.recyclerView.setAdapter(null);
        }
        searchController.detach();
        viewBinding = null;
        adapter = null;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SEARCH_ACTIVE, searchController.isActive());
        outState.putString(STATE_SEARCH_QUERY, searchController.getQuery());
        outState.putInt(STATE_SEARCH_HIT, adapter == null ? 0 : adapter.getCurrentSearchHit());
        outState.putBoolean(STATE_SEGMENT_LOOPING, segmentLooping);
        outState.putBoolean(STATE_SYNC_PICK_MODE, offsetController.isSyncPickMode());
        outState.putBoolean(STATE_IMMERSIVE, immersiveMode);
        outState.putBoolean(STATE_USER_SCROLLING, userScrolling);
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
            Log.d("AsrJitter", "loadTranscript early return media=" + currentMediaId
                    + " last=" + lastPositionMs + " userScrolling=" + userScrolling);
            viewBinding.progLoading.setVisibility(View.GONE);
            viewBinding.emptyState.setVisibility(View.GONE);
            viewBinding.recyclerView.setVisibility(View.VISIBLE);
            if (!userScrolling) {
                long offset = positionSource.offsetAt(lastPositionMs);
                int pos = currentSegmentIndex(lastPositionMs - offset);
                adapter.setHighlightPosition(pos, lastPositionMs - offset);
                followController.scrollToPosition(pos);
            }
            offsetController.updateAvailability();
            return;
        }

        if (viewBinding != null && !forceReload) {
            viewBinding.progLoading.setVisibility(View.VISIBLE);
            viewBinding.emptyState.setVisibility(View.GONE);
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
                            feedMedia.setTranscript(TranscriptUtils.loadTranscript(feedMedia, false));
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
                            showEmptyState(R.string.transcript_load_failed, true);
                        },
                        this::showEmptyState
                );
    }

    private void onMediaLoaded(FeedMedia media) {
        if (viewBinding == null) {
            return;
        }
        asrCoordinator.onMediaChanged(currentMedia, media);
        if (currentMedia != null && currentMedia.getId() == media.getId()
                && media.getTranscript() != null && adapter != null
                && adapter.getItemCount() > 0
                && currentMedia.getTranscript() == media.getTranscript()) {
            currentMedia = media;
            int newCount = media.getTranscript().getSegmentCount();
            if (newCount != loadedSegmentCount) {
                Log.d("AsrJitter", "mediaLoaded count " + loadedSegmentCount + " -> " + newCount);
                // Segments may be inserted anywhere while gaps are filled, so a full refresh is
                // the only safe notification; the highlighted row is restored right after.
                // Re-centre immediately instead of slowly catching up from below the viewport.
                if (userScrolling
                        && SystemClock.elapsedRealtime() - lastScrollInteractionMs > 2000) {
                    // A swipe or an initial settle can leave this flag set while the user is
                    // just listening; otherwise following would stay off across the whole gap.
                    userScrolling = false;
                }
                pendingSnapToHighlight = true;
                adapter.notifyDataSetChanged();
            }
            loadedSegmentCount = newCount;
            adapter.updatePlaybackPosition(lastPositionMs);
            asrCoordinator.syncRunningState();
            updateLoadedTranscriptSource(media);
            viewBinding.progLoading.setVisibility(View.GONE);
            offsetController.updateAvailability();
            asrCoordinator.updateVisibility();
            followController.scheduleFrame();
            return;
        }
        if (searchController.isActive()) {
            searchController.close();
        }
        currentMedia = media;
        asrCoordinator.syncRunningState();
        positionSource.load(media);
        updateLoadedTranscriptSource(media);
        offsetController.updateAvailability();
        asrCoordinator.updateVisibility();
        viewBinding.progLoading.setVisibility(View.GONE);

        if (currentMedia.getTranscript() == null) {
            showEmptyState();
            return;
        }

        int segmentCount = currentMedia.getTranscript().getSegmentCount();
        loadedSegmentCount = segmentCount;

        if (segmentCount == 0) {
            showEmptyState();
            return;
        }

        viewBinding.emptyState.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.VISIBLE);
        // The continue button is only shown once the list (not the empty state) is visible.
        asrCoordinator.updateVisibility();

        adapter.setMedia(currentMedia);
        offsetController.setSyncPickMode(false);
        userScrolling = false;
        lastScrolledPosition = -1;
        segmentLooping = false;
        loopSegmentIndex = -1;
        lastPositionMs = Math.max(0, positionSource.toTranscriptTime(currentMedia.getPosition()));
        MediaController controller = mediaController;
        if (controller != null && controller.isConnected()) {
            long playerPosition = controller.getCurrentPosition();
            if (playerPosition >= 0) {
                lastPositionMs = positionSource.toTranscriptTime(playerPosition);
                lastRawPlayerPosition = playerPosition;
            }
        }
        long offset = positionSource.offsetAt(lastPositionMs);
        int initialPosition = currentSegmentIndex(lastPositionMs - offset);
        adapter.setHighlightPosition(initialPosition);
        followController.scrollToPosition(initialPosition);
        pendingSnapToHighlight = true;
        followController.scheduleFrame();
        followController.updateBackToCurrentVisibility();
        offsetController.updateLabel();
        updateTranslationToggle();
        updateSegmentLoopToggle();
        restoreViewState();
    }

    private void restoreViewState() {
        Bundle state = restoredState;
        restoredState = null;
        if (state == null || viewBinding == null || adapter == null) {
            return;
        }
        searchController.restoreState(state.getString(STATE_SEARCH_QUERY, ""),
                state.getInt(STATE_SEARCH_HIT, 0));
        if (state.getBoolean(STATE_SYNC_PICK_MODE, false)) {
            offsetController.setSyncPickMode(true);
        }
        if (state.getBoolean(STATE_SEGMENT_LOOPING, false)) {
            int position = adapter.getHighlightPosition();
            if (position >= 0) {
                segmentLooping = true;
                loopSegmentIndex = position;
                updateSegmentLoopToggle();
            }
        }
        userScrolling = state.getBoolean(STATE_USER_SCROLLING, false);
        followController.updateBackToCurrentVisibility();
        if (!userScrolling) {
            pendingSnapToHighlight = true;
            followController.scheduleFrame();
        }
        if (state.getBoolean(STATE_IMMERSIVE, false)
                && getParentFragment() instanceof AudioPlayerFragment) {
            AudioPlayerFragment parent = (AudioPlayerFragment) getParentFragment();
            if (!parent.isImmersiveMode()) {
                viewBinding.recyclerView.post(parent::toggleImmersiveMode);
            }
        }
    }

    private void showEmptyState() {
        showEmptyState(R.string.no_transcript_label, false);
    }

    private void showEmptyState(int messageRes, boolean retry) {
        if (viewBinding == null) {
            return;
        }
        loadedSegmentCount = 0;
        if (searchController.isActive()) {
            searchController.close();
        }
        if (adapter != null) {
            adapter.clear();
        }
        viewBinding.progLoading.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.GONE);
        viewBinding.emptyView.setText(messageRes);
        viewBinding.btnRetryTranscript.setVisibility(retry ? View.VISIBLE : View.GONE);
        viewBinding.emptyState.setVisibility(View.VISIBLE);
        asrCoordinator.updateVisibility();
        offsetController.updateAvailability();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (currentMedia == null || currentMedia.getId() != mediaId) {
            loadTranscript();
        } else {
            asrCoordinator.updateVisibility();
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
            long now = SystemClock.elapsedRealtime();
            if (now - lastTickerMs <= TICKER_STALE_MS) {
                return;
            }
            // The ticker stopped without clearing its driving flag (process/session hiccup):
            // hand the position back to the event path and restart it if it should be running.
            Log.d("AsrFollow", "ticker stale " + (now - lastTickerMs) + "ms, restarting");
            setHighlightTickerDriving(false);
            if (highlightTickerActive) {
                highlightHandler.removeCallbacks(highlightTicker);
                highlightHandler.post(highlightTicker);
            }
        }
        handlePlaybackPosition(positionSource.toTranscriptTime(event.getPosition()));
    }

    private String describeJitterState() {
        if (viewBinding == null) {
            return "noBinding";
        }
        boolean playing = mediaController != null && mediaController.isConnected()
                && mediaController.isPlaying();
        RecyclerView list = viewBinding.recyclerView;
        String visibleRange = "";
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            LinearLayoutManager linear = (LinearLayoutManager) lm;
            View firstChild = list.getChildAt(0);
            StringBuilder children = new StringBuilder();
            for (int i = 0; i < Math.min(list.getChildCount(), 4); i++) {
                View child = list.getChildAt(i);
                children.append(children.length() == 0 ? "" : ",").append('(')
                        .append(list.getChildAdapterPosition(child)).append(',')
                        .append(child.getTop()).append("..").append(child.getBottom()).append(')');
            }
            visibleRange = " kids=" + list.getChildCount() + children
                    + " first=" + linear.findFirstVisibleItemPosition()
                    + " firstTop=" + (firstChild == null ? -1 : firstChild.getTop())
                    + " last=" + linear.findLastVisibleItemPosition()
                    + " listH=" + list.getHeight();
        }
        return "scroll=" + list.computeVerticalScrollOffset()
                + " n=" + (adapter == null ? -1 : adapter.getItemCount())
                + visibleRange
                + " pos=" + (adapter == null ? -1 : adapter.getHighlightPosition())
                + " last=" + lastPositionMs
                + " playing=" + playing
                + " userScrolling=" + userScrolling
                + " pendingSnap=" + pendingSnapToHighlight
                + " panels=" + viewBinding.moreControls.getVisibility()
                + "/" + viewBinding.layoutControls.getVisibility()
                + "/" + viewBinding.offsetControls.getVisibility()
                + " chip=" + viewBinding.statusChip.getVisibility()
                + " gen=" + (currentMedia != null && currentMedia.getTranscript() != null
                    && currentMedia.getTranscript().isGenerated())
                + " asrRunning=" + (currentMedia != null
                    && AsrGenerationManager.isRunning(currentMedia.getId()));
    }

    private void logSyncState(long position, int pos) {
        long now = SystemClock.elapsedRealtime();
        if (pos == lastSyncLogSegment && now - lastSyncLogMs < 2000) {
            return;
        }
        lastSyncLogSegment = pos;
        lastSyncLogMs = now;
        if (currentMedia == null || currentMedia.getTranscript() == null) {
            return;
        }
        Transcript transcript = currentMedia.getTranscript();
        long offset = positionSource.offsetAt(position);
        TranscriptSegment segment = pos >= 0 && pos < transcript.getSegmentCount()
                ? transcript.getSegmentAt(pos) : null;
        long segmentStart = segment == null ? -1 : segment.getStartTime();
        long segmentEnd = segment == null ? -1 : segment.getEndTime();
        int wordIndex = segment == null ? -1 : segment.getWordIndexAt(position - offset);
        long wordStart = -1;
        long wordEnd = -1;
        if (segment != null && wordIndex >= 0 && wordIndex < segment.getWordTimings().size()) {
            wordStart = segment.getWordTimings().get(wordIndex).getStartTime();
            wordEnd = segment.getWordTimings().get(wordIndex).getEndTime();
        }
        long lastEnd = transcript.getSegmentCount() == 0 ? -1
                : transcript.getSegmentAt(transcript.getSegmentCount() - 1).getEndTime();
        Log.d("AsrSync", "raw=" + lastRawPlayerPosition
                + " tx=" + position
                + " scale=" + positionSource.scale()
                + " off=" + offset
                + " gen=" + transcript.isGenerated()
                + " seg=" + pos + "/" + transcript.getSegmentCount()
                + " segStart=" + segmentStart
                + " segEnd=" + segmentEnd
                + " word=" + wordIndex
                + " wordStart=" + wordStart
                + " wordEnd=" + wordEnd
                + " lastEnd=" + lastEnd);
    }

    private void handlePlaybackPosition(long position) {
        if (position >= 0) {
            lastPositionMs = position;
        }
        if (currentMedia == null || currentMedia.getTranscript() == null || viewBinding == null) {
            return;
        }
        if (adapter != null && position >= 0) {
            // Keep the highlight in step whichever source drives us (ticker or 1 s events).
            adapter.updatePlaybackPosition(position);
        }

        if (segmentLooping && position >= 0) {
            applySegmentLoop(position);
        }

        int pos = currentSegmentIndex(lastPositionMs - positionSource.offsetAt(lastPositionMs));
        logSyncState(lastPositionMs, pos);
        if (position != lastLoggedPosition || pos != lastLoggedSegment) {
            lastLoggedPosition = position;
            lastLoggedSegment = pos;
            Log.d("AsrJitter", "handlePos pos=" + position + " seg=" + pos
                    + " lastScrolled=" + lastScrolledPosition
                    + " userScrolling=" + userScrolling
                    + " pendingSnap=" + pendingSnapToHighlight
                    + " adapterPos=" + (adapter == null ? -1 : adapter.getHighlightPosition()));
        }
        if (pos < 0) {
            return;
        }
        offsetController.refreshLabelIfVisible();

        if (userScrolling) {
            return;
        }

        if (pos == lastScrolledPosition) {
            followController.scheduleFrame();
            return;
        }

        if (lastScrolledPosition >= 0 && Math.abs(pos - lastScrolledPosition) <= 1) {
            lastScrolledPosition = pos;
            // Do not clear pendingSnapToHighlight here: a load/reload asked for a jump to the
            // current row, and the row may still be far off screen. Dropping the flag made the
            // follow crawl at 10px per frame (and only when a frame happened at all).
            followController.scheduleFrame();
            return;
        }

        followController.scrollToPosition(pos);
    }

    @Override
    public void onTranscriptClicked(int position, TranscriptSegment segment,
                                    long sentenceStartTime, long sentenceEndTime) {
        Log.i("AsrSync", "click pos=" + position + " cue=" + sentenceStartTime + ".."
                + sentenceEndTime + " raw=" + lastRawPlayerPosition);
        if (offsetController.isSyncPickMode()) {
            offsetController.onPickedSentence(position, sentenceStartTime, sentenceEndTime);
            return;
        }
        long mediaId = currentMedia == null ? 0 : currentMedia.getId();
        long offset = positionSource.offsetAtCue(sentenceStartTime);
        long startTime = sentenceStartTime + offset;
        long endTime = sentenceEndTime + offset;

        PlaybackController.bindToMedia3Service(getContext(), controller -> {
            long currentPos = positionSource.toTranscriptTime(controller.getCurrentPosition());
            if (currentPos >= startTime && currentPos <= endTime) {
                if (controller.isPlaying()) {
                    controller.pause();
                } else {
                    controller.play();
                }
            } else {
                controller.seekTo(Math.max(0, positionSource.toPlayerTime(startTime)));
            }
        });

        userScrolling = false;
        followController.updateBackToCurrentVisibility();
        if (segmentLooping) {
            loopSegmentIndex = position;
        }
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
        viewBinding.switchSegmentLoop.setChecked(segmentLooping);
    }

    private void setToggleState(View view, int descriptionRes, boolean on) {
        view.setSelected(on);
        view.setContentDescription(getString(descriptionRes) + ", "
                + getString(on ? R.string.transcript_state_on : R.string.transcript_state_off));
    }

    private void applySegmentLoop(long position) {
        if (!segmentLooping) {
            return;
        }
        if (currentMedia == null || currentMedia.getTranscript() == null) {
            return;
        }
        if (loopSegmentIndex < 0 || loopSegmentIndex >= currentMedia.getTranscript().getSegmentCount()) {
            stopSegmentLoop();
            return;
        }
        TranscriptSegment segment = currentMedia.getTranscript().getSegmentAt(loopSegmentIndex);
        long offset = positionSource.offsetAtCue(segment.getStartTime());
        long startTime = Math.max(0, segment.getStartTime() + offset);
        long endTime = segment.getEndTime() + offset;
        if (endTime <= startTime) {
            stopSegmentLoop();
            return;
        }
        if (position >= endTime) {
            PlaybackController.bindToMedia3Service(getContext(), controller ->
                    controller.seekTo((int) positionSource.toPlayerTime(startTime)));
        }
    }

    private void refreshPositionForTimeScale() {
        if (currentMedia == null) {
            return;
        }
        MediaController controller = mediaController;
        long playerPosition = controller != null && controller.isConnected()
                ? controller.getCurrentPosition() : currentMedia.getPosition();
        long position = positionSource.toTranscriptTime(Math.max(0, playerPosition));
        lastPositionMs = position;
        if (adapter != null) {
            adapter.updatePlaybackPosition(position);
        }
        handlePlaybackPosition(position);
    }

    private void toggleLayoutBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.layoutControls.getVisibility() == View.VISIBLE;
        Log.d("AsrJitter", "toggleLayoutBar open=" + !visible);
        offsetController.closePanels();
        viewBinding.moreControls.setVisibility(View.GONE);
        viewBinding.btnMore.setAlpha(0.5f);
        viewBinding.layoutControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        layoutController.updateControls();
    }

    private void toggleMoreBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.moreControls.getVisibility() == View.VISIBLE;
        Log.d("AsrJitter", "toggleMoreBar open=" + !visible);
        offsetController.closePanels();
        viewBinding.layoutControls.setVisibility(View.GONE);
        viewBinding.moreControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        viewBinding.btnMore.setAlpha(visible ? 0.5f : 1.0f);
        updateTranslationToggle();
        updateSentenceToggle();
        updateSegmentLoopToggle();
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
            searchController.refreshAfterDisplayChange();
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
            searchController.refreshAfterDisplayChange();
        }
    }

    private void updateSentenceToggle() {
        if (viewBinding == null) {
            return;
        }
        boolean enabled = adapter != null && adapter.isSentencePerLine();
        viewBinding.switchSentencePerLine.setChecked(enabled);
    }

    public void toggleControlsBar() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.controlsBar.getVisibility() == View.VISIBLE;
        Log.d("AsrJitter", "toggleControlsBar visible=" + visible
                + " searchActive=" + searchController.isActive());
        if (visible) {
            hideControlPanels();
            if (searchController.isActive()) {
                searchController.close();
            }
        }
        viewBinding.controlsBar.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void hideControlPanels() {
        Log.d("AsrJitter", "hideControlPanels");
        offsetController.closePanels();
        viewBinding.layoutControls.setVisibility(View.GONE);
        viewBinding.moreControls.setVisibility(View.GONE);
        viewBinding.btnMore.setAlpha(0.5f);
        layoutController.updateControls();
    }

    public boolean isControlsBarVisible() {
        return viewBinding != null && viewBinding.controlsBar.getVisibility() == View.VISIBLE;
    }

    public void setImmersiveMode(boolean immersive) {
        if (viewBinding == null) {
            return;
        }
        immersiveMode = immersive;
        Log.d("AsrJitter", "setImmersiveMode immersive=" + immersive);
        if (immersive) {
            controlsBarVisibleBeforeImmersive = viewBinding.controlsBar.getVisibility() == View.VISIBLE;
            searchController.close();
            hideControlPanels();
            viewBinding.controlsBar.setVisibility(View.GONE);
        } else {
            viewBinding.controlsBar.setVisibility(
                    controlsBarVisibleBeforeImmersive ? View.VISIBLE : View.GONE);
        }
        viewBinding.getRoot().setPadding(0, immersive ? getImmersiveTopInset() : 0, 0, 0);
        viewBinding.btnImmersive.setAlpha(immersive ? 1.0f : 0.5f);
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
        viewBinding.languageRow.setVisibility(isBilingual() ? View.VISIBLE : View.GONE);
        int checkedId = languageButtonForMode(getLanguageMode());
        if (viewBinding.languageGroup.getCheckedButtonId() != checkedId) {
            viewBinding.languageGroup.check(checkedId);
        }
    }

    private int languageButtonForMode(int mode) {
        if (mode == UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY) {
            return R.id.btnLanguagePrimary;
        }
        if (mode == UserPreferences.TRANSCRIPT_LANGUAGE_MODE_SECONDARY_ONLY) {
            return R.id.btnLanguageSecondary;
        }
        return R.id.btnLanguageBoth;
    }

    private int languageModeForButton(int checkedId) {
        if (checkedId == R.id.btnLanguagePrimary) {
            return TranscriptAdapter.LANGUAGE_MODE_PRIMARY_ONLY;
        }
        if (checkedId == R.id.btnLanguageSecondary) {
            return TranscriptAdapter.LANGUAGE_MODE_SECONDARY_ONLY;
        }
        return TranscriptAdapter.LANGUAGE_MODE_BOTH;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAsrGenerationEvent(AsrGenerationEvent event) {
        asrCoordinator.onEvent(event);
    }

    private boolean isCasting() {
        MediaController controller = mediaController;
        return controller != null && controller.isConnected()
                && controller.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE;
    }

    private int currentSegmentIndex(long time) {
        if (currentMedia == null || currentMedia.getTranscript() == null) {
            return -1;
        }
        Transcript transcript = currentMedia.getTranscript();
        int index = transcript.findSegmentIndexBefore(time);
        if (index >= 0 && transcript.getSegmentAt(index).getEndTime() < time
                && index + 1 < transcript.getSegmentCount()) {
            return index + 1;
        }
        return index;
    }

    private void showToast(int resId) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
        }
    }
}
