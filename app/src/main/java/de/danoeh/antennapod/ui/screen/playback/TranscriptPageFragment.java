package de.danoeh.antennapod.ui.screen.playback;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
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
    private long lastUserScrollTime = 0;
    private int lastScrolledPosition = -1;
    private static final long SCROLL_COOLDOWN_MS = 3000;
    private TextView btnToggleTranslation;

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
                    lastUserScrollTime = System.currentTimeMillis();
                }
            }
        });

        btnToggleTranslation = viewBinding.getRoot().findViewById(R.id.btnToggleTranslation);
        if (btnToggleTranslation != null) {
            btnToggleTranslation.setOnClickListener(v -> {
                boolean newState = !adapter.isHideSecondLanguage();
                adapter.setHideSecondLanguage(newState);
                btnToggleTranslation.setAlpha(newState ? 0.5f : 1.0f);
            });
        }

        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
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

        Log.d(TAG, "=== loadTranscript() START ===");

        if (viewBinding != null) {
            viewBinding.progLoading.setVisibility(View.VISIBLE);
            viewBinding.emptyView.setVisibility(View.GONE);
            viewBinding.recyclerView.setVisibility(View.GONE);
        }

        disposable = Maybe.create(emitter -> {
                    long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
                    Log.d(TAG, "mediaId = " + mediaId);

                    if (mediaId == 0) {
                        emitter.onComplete();
                        return;
                    }

                    Playable media = DBReader.getFeedMedia(mediaId);
                    Log.d(TAG, "DBReader result = " + (media == null ? "null" : media.getClass().getSimpleName()));

                    if (media instanceof FeedMedia) {
                        currentMedia = (FeedMedia) media;
                        Log.d(TAG, "hasTranscript before = " + currentMedia.hasTranscript()
                                + ", getTranscript = " + (currentMedia.getTranscript() == null ? "null" : "not null"));

                        if (!currentMedia.hasTranscript() || currentMedia.getTranscript() == null) {
                            try {
                                currentMedia.setTranscript(TranscriptUtils.loadTranscript(currentMedia, false));
                                Log.d(TAG, "loadTranscript done, result = "
                                        + (currentMedia.getTranscript() == null ? "null" : "not null"));
                            } catch (Exception e) {
                                Log.e(TAG, "TranscriptUtils.loadTranscript FAILED", e);
                            }
                        }

                        emitter.onSuccess(currentMedia);
                    } else {
                        emitter.onComplete();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        media -> {
                            Log.d(TAG, "subscribe onSuccess");
                            onMediaLoaded((FeedMedia) media);
                        },
                        error -> {
                            Log.e(TAG, "subscribe onError", error);
                            showEmptyState();
                        },
                        this::showEmptyState
                );
    }

    private void onMediaLoaded(FeedMedia media) {
        if (viewBinding == null) {
            Log.d(TAG, "onMediaLoaded: viewBinding NULL");
            return;
        }
        currentMedia = media;
        viewBinding.progLoading.setVisibility(View.GONE);

        if (currentMedia.getTranscript() == null) {
            Log.d(TAG, "onMediaLoaded: transcript is NULL!");
            showEmptyState();
            return;
        }

        int segmentCount = currentMedia.getTranscript().getSegmentCount();
        Log.d(TAG, "segmentCount = " + segmentCount);

        if (segmentCount == 0) {
            showEmptyState();
            return;
        }

        viewBinding.emptyView.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.VISIBLE);

        adapter.setMedia(currentMedia);
        doInitialScroll = true;
        lastScrolledPosition = -1;

        if (btnToggleTranslation != null) {
            boolean isBilingual = currentMedia.getTranscript().isBilingual();
            btnToggleTranslation.setVisibility(isBilingual ? View.VISIBLE : View.GONE);
            btnToggleTranslation.setAlpha(adapter.isHideSecondLanguage() ? 0.5f : 1.0f);
        }

        Log.d(TAG, "=== DONE, list should appear ===");
    }

    private void showEmptyState() {
        Log.d(TAG, "showEmptyState() called");
        if (viewBinding == null) {
            Log.d(TAG, "showEmptyState: viewBinding is NULL");
            return;
        }
        viewBinding.progLoading.setVisibility(View.GONE);
        viewBinding.recyclerView.setVisibility(View.GONE);
        viewBinding.emptyView.setVisibility(View.VISIBLE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadTranscript();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackPositionEvent(PlaybackPositionEvent event) {
        if (currentMedia == null || currentMedia.getTranscript() == null || viewBinding == null) {
            return;
        }

        int pos = currentMedia.getTranscript().findSegmentIndexBefore(event.getPosition());
        if (pos <= 0 || pos == lastScrolledPosition) {
            return;
        }

        if (userScrolling && System.currentTimeMillis() - lastUserScrollTime < SCROLL_COOLDOWN_MS) {
            return;
        }
        userScrolling = false;

        scrollToPosition(pos);
    }

    private void scrollToPosition(int pos) {
        lastScrolledPosition = pos;
        LinearLayoutManager lm = (LinearLayoutManager) viewBinding.recyclerView.getLayoutManager();
        if (lm == null) {
            return;
        }

        if (!doInitialScroll) {
            if (lm.findFirstVisibleItemPosition() < pos - 1
                    && !viewBinding.recyclerView.canScrollVertically(1)) {
                return;
            }
        }
        doInitialScroll = false;

        boolean quickScroll = Math.abs(lm.findFirstVisibleItemPosition() - pos) > 5;
        if (quickScroll) {
            viewBinding.recyclerView.scrollToPosition(pos - 1);
        }

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_START;
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return (quickScroll ? 200 : 1000) / (float) displayMetrics.densityDpi;
            }
        };
        smoothScroller.setTargetPosition(pos - 1);
        lm.startSmoothScroll(smoothScroller);
    }

    @Override
    public void onTranscriptClicked(int position, TranscriptSegment segment) {
        long startTime = segment.getStartTime();
        long endTime = segment.getEndTime();

        PlaybackController.bindToMedia3Service(getContext(), controller -> {
            long currentPos = controller.getCurrentPosition();
            if (currentPos >= startTime && currentPos <= endTime) {
                if (controller.isPlaying()) {
                    controller.pause();
                } else {
                    controller.play();
                }
            } else {
                controller.seekTo(startTime);
            }
        });

        userScrolling = false;
        lastUserScrollTime = 0;
    }

    @Override
    public void onTranscriptLongClicked(int position, TranscriptSegment segment) {
        // 可扩展长按复制等逻辑
    }
}
