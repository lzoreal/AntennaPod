package de.danoeh.antennapod.ui.screen.playback;

import android.os.Bundle;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptPageFragmentBinding;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
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
import java.util.Locale;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TranscriptPageFragment extends Fragment implements TranscriptAdapter.SegmentClickListener {
    public static final String TAG = "TranscriptPageFragment";
    private TranscriptPageFragmentBinding viewBinding;
    private TranscriptAdapter adapter;
    private Disposable disposable;
    private FeedMedia currentMedia;
    private boolean doInitialScroll = true;
    private boolean userScrolling = false;
    private int lastScrolledPosition = -1;
    private long lastPositionMs = 0;
    private boolean controlsBarVisibleBeforeImmersive = false;
    private boolean immersiveMode = false;
    private int immersiveTopInset = 0;
    private static final long FOLLOW_SCROLL_INTERVAL_MS = 300;
    private static final int FONT_SIZE_MIN_STEP = -2;
    private static final int FONT_SIZE_MAX_STEP = 3;
    private long lastFollowScrollTime = 0;
    private boolean segmentLooping = false;
    private int loopSegmentIndex = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = TranscriptPageFragmentBinding.inflate(inflater, container, false);

        adapter = new TranscriptAdapter(getContext(), this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        viewBinding.recyclerView.setLayoutManager(layoutManager);
        viewBinding.recyclerView.setAdapter(adapter);

        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                    updateBackToCurrentVisibility();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    resumeFollowingIfHighlightVisible();
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
        viewBinding.btnFontSmaller.setOnClickListener(v -> adjustFontSize(-1));
        viewBinding.btnFontLarger.setOnClickListener(v -> adjustFontSize(1));
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
        updateOffsetLabel();
        loadTranscript();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
    }

    private void loadTranscript() {
        if (disposable != null) {
            disposable.dispose();
        }

        long currentMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (viewBinding != null && currentMedia != null && currentMedia.getId() == currentMediaId
                && adapter.getItemCount() > 0) {
            viewBinding.progLoading.setVisibility(View.GONE);
            viewBinding.emptyView.setVisibility(View.GONE);
            viewBinding.recyclerView.setVisibility(View.VISIBLE);
            if (!userScrolling) {
                if (PlaybackPreferences.getCurrentPlayerStatus()
                        != PlaybackPreferences.PLAYER_STATUS_PLAYING) {
                    lastPositionMs = Math.max(0, currentMedia.getPosition());
                }
                long offset = UserPreferences.getTranscriptTimeOffset(currentMedia.getId());
                int pos = currentMedia.getTranscript().findSegmentIndexBefore(lastPositionMs - offset);
                adapter.setHighlightPosition(pos, lastPositionMs - offset);
                scrollToPosition(pos);
            }
            return;
        }

        if (viewBinding != null) {
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

                        if (!feedMedia.hasTranscript() || feedMedia.getTranscript() == null) {
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
        currentMedia = media;
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
        doInitialScroll = true;
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
    public void onPlaybackPositionEvent(PlaybackPositionEvent event) {
        if (event.getPosition() >= 0) {
            lastPositionMs = event.getPosition();
        }
        if (currentMedia == null || currentMedia.getTranscript() == null || viewBinding == null) {
            return;
        }

        if (segmentLooping && event.getPosition() >= 0) {
            applySegmentLoop(event.getPosition());
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
            followLongSegment(pos, lastPositionMs
                    - UserPreferences.getTranscriptTimeOffset(currentMedia.getId()));
            return;
        }

        scrollToPosition(pos);
    }

    private void followLongSegment(int pos, long position) {
        if (currentMedia == null || currentMedia.getTranscript() == null) {
            return;
        }

        RecyclerView.ViewHolder holder = viewBinding.recyclerView.findViewHolderForAdapterPosition(pos);
        if (!(holder instanceof TranscriptViewholder)) {
            return;
        }

        int viewportHeight = viewBinding.recyclerView.getHeight();
        if (holder.itemView.getHeight() <= viewportHeight) {
            return;
        }

        TextView content = ((TranscriptViewholder) holder).viewContent;
        Layout layout = content.getLayout();
        if (layout == null) {
            return;
        }

        TranscriptSegment segment = currentMedia.getTranscript().getSegmentAt(pos);
        float progress = TranscriptTextUtils.progressAt(segment, position);
        int charIndex = TranscriptTextUtils.charIndexAt(content.getText().toString(), progress);
        charIndex = Math.max(0, Math.min(charIndex, content.length()));
        int line = layout.getLineForOffset(charIndex);
        int screenY = holder.itemView.getTop() + content.getTop() + layout.getLineTop(line);

        if (screenY > viewportHeight * 2 / 3) {
            long now = System.currentTimeMillis();
            if (now - lastFollowScrollTime < FOLLOW_SCROLL_INTERVAL_MS) {
                return;
            }
            lastFollowScrollTime = now;
            viewBinding.recyclerView.stopScroll();
            viewBinding.recyclerView.smoothScrollBy(0, screenY - viewportHeight / 2);
        }
    }

    private void scrollToPosition(int pos) {
        scrollToPosition(pos, false);
    }

    private void scrollToPosition(int pos, boolean fast) {
        lastScrolledPosition = pos;
        LinearLayoutManager lm = (LinearLayoutManager) viewBinding.recyclerView.getLayoutManager();
        if (lm == null) {
            return;
        }

        // No need to scroll if the highlighted line is centered and fully visible
        View targetView = lm.findViewByPosition(pos);
        if (targetView != null) {
            int top = targetView.getTop();
            int bottom = targetView.getBottom();
            int rvHeight = viewBinding.recyclerView.getHeight();
            int itemHeight = targetView.getHeight();
            int centerTop = (rvHeight - itemHeight) / 2;
            if (Math.abs(top - centerTop) < 20 && bottom <= rvHeight) {
                return;
            }
        }

        if (!doInitialScroll) {
            if (lm.findFirstVisibleItemPosition() < pos
                    && !viewBinding.recyclerView.canScrollVertically(1)) {
                return;
            }
        }
        doInitialScroll = false;

        boolean quickScroll = Math.abs(lm.findFirstVisibleItemPosition() - pos) > 5;
        if (quickScroll) {
            viewBinding.recyclerView.scrollToPosition(pos);
        }

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_ANY;
            }

            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                int itemHeight = viewEnd - viewStart;
                int boxHeight = boxEnd - boxStart;

                if (itemHeight >= boxHeight) {
                    return boxStart - viewStart;
                }

                // First try to center the highlighted transcript line
                int targetTop = boxStart + (boxHeight - itemHeight) / 2;

                // If centering pushes it below the screen, scroll down until it is fully visible (bottom aligned)
                if (targetTop + itemHeight > boxEnd) {
                    targetTop = boxEnd - itemHeight;
                }

                return targetTop - viewStart;
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return (fast ? 100 : quickScroll ? 200 : 1000) / (float) displayMetrics.densityDpi;
            }
        };
        smoothScroller.setTargetPosition(pos);
        lm.startSmoothScroll(smoothScroller);
    }

    @Override
    public void onTranscriptClicked(int position, TranscriptSegment segment, long sentenceStartTime) {
        long mediaId = currentMedia == null ? 0 : currentMedia.getId();
        long offset = UserPreferences.getTranscriptTimeOffset(mediaId);
        long startTime = sentenceStartTime + offset;
        long endTime = segment.getEndTime() + offset;

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

    private void resumeFollowingIfHighlightVisible() {
        if (!userScrolling || viewBinding == null || adapter == null) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) viewBinding.recyclerView.getLayoutManager();
        int highlight = adapter.getHighlightPosition();
        if (lm != null && highlight >= lm.findFirstVisibleItemPosition()
                && highlight <= lm.findLastVisibleItemPosition()) {
            userScrolling = false;
            updateBackToCurrentVisibility();
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
        long startTime = segment.getStartTime() + offset;
        long endTime = segment.getEndTime() + offset;
        if (position >= endTime) {
            PlaybackController.bindToMedia3Service(getContext(), controller ->
                    controller.seekTo((int) Math.max(0, startTime)));
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
        UserPreferences.setTranscriptTimeOffset(currentMedia.getId(), offsetMs);
        if (adapter != null && currentMedia.getTranscript() != null
                && currentMedia.getTranscript().getSegmentCount() > 0) {
            int pos = currentMedia.getTranscript().findSegmentIndexBefore(lastPositionMs - offsetMs);
            adapter.setHighlightPosition(pos, lastPositionMs - offsetMs);
            userScrolling = false;
            updateBackToCurrentVisibility();
            lastScrolledPosition = -1;
            scrollToPosition(pos);
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
        viewBinding.offsetControls.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateOffsetToggle();
        updateOffsetLabel();
    }

    private void updateOffsetToggle() {
        if (viewBinding == null) {
            return;
        }
        boolean visible = viewBinding.offsetControls.getVisibility() == View.VISIBLE;
        viewBinding.btnOffsetToggle.setAlpha(visible ? 1.0f : 0.5f);
    }

    private String formatTimeOffset(long offsetMs) {
        double seconds = offsetMs / 1000.0;
        String value = seconds == Math.rint(seconds)
                ? String.valueOf((long) seconds)
                : String.format(Locale.getDefault(), "%.1f", seconds);
        return (seconds > 0 ? "+" : "") + value + " s";
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
        viewBinding.controlsBar.setVisibility(visible ? View.GONE : View.VISIBLE);
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
