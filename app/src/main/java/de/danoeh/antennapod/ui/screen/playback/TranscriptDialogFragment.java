package de.danoeh.antennapod.ui.screen.playback;

import android.app.Dialog;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.TranscriptDialogBinding;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TranscriptDialogFragment extends DialogFragment
        implements TranscriptAdapter.SegmentClickListener {
    public static final String TAG = "TranscriptFragment";
    private TranscriptDialogBinding viewBinding;
    private Disposable disposable;
    private Playable media;
    private Transcript transcript;
    private TranscriptAdapter adapter = null;
    private LinearLayoutManager layoutManager;

    @Override
    public void onResume() {
        ViewGroup.LayoutParams params;
        params = getDialog().getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        getDialog().getWindow().setAttributes((WindowManager.LayoutParams) params);
        super.onResume();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        viewBinding = TranscriptDialogBinding.inflate(getLayoutInflater());
        layoutManager = new LinearLayoutManager(getContext());
        viewBinding.transcriptList.setLayoutManager(layoutManager);

        adapter = new TranscriptAdapter(getContext(), this);
        viewBinding.transcriptList.setAdapter(adapter);
        viewBinding.transcriptList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    viewBinding.followAudioCheckbox.setChecked(false);
                }
            }
        });

        viewBinding.toolbar.inflateMenu(R.menu.transcript);
        viewBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        viewBinding.followAudioCheckbox.setChecked(true);
        viewBinding.progLoading.setVisibility(View.VISIBLE);

        View btnToggleTranslation = viewBinding.getRoot().findViewById(R.id.btnToggleTranslation);
        if (btnToggleTranslation != null) {
            btnToggleTranslation.setAlpha(
                    adapter.getLanguageMode() == TranscriptAdapter.LANGUAGE_MODE_BOTH ? 1.0f : 0.5f);
            btnToggleTranslation.setOnClickListener(v -> {
                int newState = (adapter.getLanguageMode() + 1) % 3;
                adapter.setLanguageMode(newState);
                btnToggleTranslation.setAlpha(
                        newState == TranscriptAdapter.LANGUAGE_MODE_BOTH ? 1.0f : 0.5f);
            });
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(viewBinding.getRoot())
                .setNegativeButton(R.string.close_label, null)
                .create();
        return dialog;
    }

    @Override
    public void onTranscriptClicked(int pos, TranscriptSegment segment,
                                    long sentenceStartTime, long sentenceEndTime) {
        long offset = media instanceof FeedMedia
                ? UserPreferences.getTranscriptTimeOffset(((FeedMedia) media).getId()) : 0;
        long startTime = sentenceStartTime + offset;
        long endTime = sentenceEndTime + offset;

        scrollToPosition(pos);
        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            if (!(controller.getCurrentPosition() >= startTime
                    && controller.getCurrentPosition() <= endTime)) {
                controller.seekTo(Math.max(0, startTime));
            } else if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });
        viewBinding.followAudioCheckbox.setChecked(true);
    }

    @Override
    public void onTranscriptWordSelected(int position, TranscriptSegment seg, String selectedText) {
        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            if (controller.isPlaying()) {
                controller.pause();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        if (adapter != null
                && adapter.getLanguageMode() != UserPreferences.getTranscriptLanguageMode()) {
            adapter.setLanguageMode(UserPreferences.getTranscriptLanguageMode());
        }
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListUpdateEvent(FeedListUpdateEvent event) {
        if (!(media instanceof FeedMedia) || ((FeedMedia) media).getItem() == null) {
            return;
        }
        if (event.contains(((FeedMedia) media).getItem().getFeedId())) {
            loadMediaInfo(false, true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        if (transcript == null || event.getPosition() < 0) {
            return;
        }
        long offset = media instanceof FeedMedia
                ? UserPreferences.getTranscriptTimeOffset(((FeedMedia) media).getId()) : 0;
        int pos = transcript.findSegmentIndexBefore(event.getPosition() - offset);
        scrollToPosition(pos);
    }

    private void loadMediaInfo(boolean forceRefresh) {
        loadMediaInfo(forceRefresh, false);
    }

    private void loadMediaInfo(boolean forceRefresh, boolean forceReload) {
        if (!forceRefresh && !forceReload && media instanceof FeedMedia && transcript != null
                && adapter != null && adapter.getItemCount() > 0
                && ((FeedMedia) media).getId()
                == PlaybackPreferences.getCurrentlyPlayingFeedMediaId()) {
            if (viewBinding != null) {
                viewBinding.progLoading.setVisibility(View.GONE);
            }
            return;
        }
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.create(emitter -> {
            Playable loadedMedia = DBReader.getFeedMedia(
                    PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (loadedMedia instanceof FeedMedia) {
                FeedMedia feedMedia = (FeedMedia) loadedMedia;
                feedMedia.setTranscript(TranscriptUtils.loadTranscript(feedMedia, forceRefresh));
                emitter.onSuccess(feedMedia);
            } else {
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(media -> onMediaChanged((Playable) media),
                error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    if (viewBinding != null) {
                        viewBinding.progLoading.setVisibility(View.GONE);
                    }
                },
                () -> {
                    if (viewBinding != null) {
                        viewBinding.progLoading.setVisibility(View.GONE);
                    }
                });
    }

    private void onMediaChanged(Playable media) {
        if (viewBinding == null || !isAdded() || !(media instanceof FeedMedia)) {
            return;
        }
        this.media = media;
        this.transcript = ((FeedMedia) media).getTranscript();
        if (this.transcript == null || this.transcript.getSegmentCount() == 0) {
            dismiss();
            Toast.makeText(getContext(), R.string.no_transcript_label, Toast.LENGTH_LONG).show();
            return;
        }
        Transcript loadedTranscript = this.transcript;

        viewBinding.progLoading.setVisibility(View.GONE);
        adapter.setMedia(media);

        long offset = UserPreferences.getTranscriptTimeOffset(((FeedMedia) media).getId());
        long position = Math.max(0, ((FeedMedia) media).getPosition() - offset);
        int initialPosition = loadedTranscript.findSegmentIndexBefore(position);
        long initialMediaId = ((FeedMedia) media).getId();
        adapter.setHighlightPosition(initialPosition);
        viewBinding.transcriptList.post(() -> {
            if (viewBinding != null && isAdded() && media instanceof FeedMedia
                    && ((FeedMedia) media).getId() == initialMediaId
                    && initialPosition < adapter.getItemCount()) {
                scrollToPosition(initialPosition);
            }
        });

        View btnToggleTranslation = viewBinding.getRoot().findViewById(R.id.btnToggleTranslation);
        if (btnToggleTranslation != null) {
            boolean isBilingual = loadedTranscript.isBilingual();
            btnToggleTranslation.setVisibility(isBilingual ? View.VISIBLE : View.GONE);
        }
    }

    public void scrollToPosition(int pos) {
        if (pos < 0) {
            return;
        }
        if (!viewBinding.followAudioCheckbox.isChecked()) {
            return;
        }

        int target = Math.max(0, pos - 1);
        View targetView = layoutManager.findViewByPosition(target);
        if (targetView != null && Math.abs(targetView.getTop()) < 20) {
            return;
        }
        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        boolean quickScroll = Math.abs(firstVisible - pos) > 5;
        if (firstVisible < target && !viewBinding.transcriptList.canScrollVertically(1)) {
            return;
        }
        if (quickScroll) {
            viewBinding.transcriptList.scrollToPosition(target);
        }
        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_START;
            }

            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return (quickScroll ? 200 : 1000) / (float) displayMetrics.densityDpi;
            }
        };
        smoothScroller.setTargetPosition(target);
        layoutManager.startSmoothScroll(smoothScroller);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
        EventBus.getDefault().unregister(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            viewBinding.progLoading.setVisibility(View.VISIBLE);
            loadMediaInfo(true);
            return true;
        }
        return false;
    }
}
