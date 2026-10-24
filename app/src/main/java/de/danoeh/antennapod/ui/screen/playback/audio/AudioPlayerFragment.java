package de.danoeh.antennapod.ui.screen.playback.audio;

import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.media3.session.MediaController;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import de.danoeh.antennapod.ui.episodes.TimeSpeedConverter;
import de.danoeh.antennapod.ui.screen.playback.MediaPlayerErrorDialog;
import de.danoeh.antennapod.ui.screen.playback.PlayButton;
import de.danoeh.antennapod.ui.screen.playback.SleepTimerDialog;
import de.danoeh.antennapod.ui.screen.playback.TranscriptPageFragment;
import de.danoeh.antennapod.ui.screen.playback.VariableSpeedDialog;
import de.danoeh.antennapod.ui.transcript.TranscriptFloatingWindowService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.screen.feed.preferences.SkipPreferenceDialog;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.PlayerErrorEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.BufferUpdateEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.event.playback.PlaybackServiceEvent;
import de.danoeh.antennapod.event.playback.SleepTimerUpdatedEvent;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.cast.CastEnabledActivity;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Shows the audio player.
 */
public class AudioPlayerFragment extends Fragment implements
        ChapterSeekBar.OnSeekBarChangeListener, MaterialToolbar.OnMenuItemClickListener {
    public static final String TAG = "AudioPlayerFragment";
    public static final int POS_COVER = 0;
    public static final int POS_DESCRIPTION = 1;
    public static final int POS_TRANSCRIPT = 2;
    private static final int NUM_CONTENT_FRAGMENTS = 3;

    private TextView txtvPlaybackSpeed;
    private ViewPager2 pager;
    private TextView txtvPosition;
    private TextView txtvLength;
    private ChapterSeekBar sbPosition;
    private ImageButton butRev;
    private TextView txtvRev;
    private PlayButton butPlay;
    private ImageButton butFF;
    private TextView txtvFF;
    private ImageButton butSkip;
    private MaterialToolbar toolbar;
    private ProgressBar progressIndicator;
    private CardView cardViewSeek;
    private TextView txtvSeek;
    private View playtimeLayout;
    private View playerControl;

    private FeedMedia currentMedia;
    private Disposable disposable;
    private boolean showTimeLeft;
    private boolean seekedToChapterStart = false;
    private int currentChapterIndex = -1;
    private boolean isImmersiveMode = false;
    private OnBackPressedCallback immersiveBackCallback;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View root = inflater.inflate(R.layout.audioplayer_fragment, container, false);
        root.setOnTouchListener((v, event) -> true);
        toolbar = root.findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbar.setNavigationOnClickListener(v ->
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED));
        toolbar.setOnMenuItemClickListener(this);
        toolbar.inflateMenu(R.menu.mediaplayer);

        playtimeLayout = root.findViewById(R.id.playtime_layout);
        playerControl = root.findViewById(R.id.player_control);

        ExternalPlayerFragment externalPlayerFragment = new ExternalPlayerFragment();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.playerFragment, externalPlayerFragment, ExternalPlayerFragment.TAG)
                .commit();

        txtvPlaybackSpeed = root.findViewById(R.id.txtvPlaybackSpeed);
        sbPosition = root.findViewById(R.id.sbPosition);
        txtvPosition = root.findViewById(R.id.txtvPosition);
        txtvLength = root.findViewById(R.id.txtvLength);
        butRev = root.findViewById(R.id.butRev);
        txtvRev = root.findViewById(R.id.txtvRev);
        butPlay = root.findViewById(R.id.butPlay);
        butFF = root.findViewById(R.id.butFF);
        txtvFF = root.findViewById(R.id.txtvFF);
        butSkip = root.findViewById(R.id.butSkip);
        progressIndicator = root.findViewById(R.id.progLoading);
        cardViewSeek = root.findViewById(R.id.cardViewSeek);
        txtvSeek = root.findViewById(R.id.txtvSeek);

        setupLengthTextView();
        setupControlButtons();
        final ImageButton butPlaybackSpeed = root.findViewById(R.id.butPlaybackSpeed);
        butPlaybackSpeed.setOnClickListener(v -> new VariableSpeedDialog().show(getChildFragmentManager(), null));
        sbPosition.setOnSeekBarChangeListener(this);

        pager = root.findViewById(R.id.pager);
        pager.setAdapter(new AudioPlayerPagerAdapter(this));
        pager.setOffscreenPageLimit(NUM_CONTENT_FRAGMENTS);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                pager.post(() -> {
                    if (getActivity() != null) {
                        ((MainActivity) getActivity()).getBottomSheet().updateScrollingChild();
                    }
                });
                updateToolbarButtonsVisibility(position);
                if (position != POS_TRANSCRIPT && isImmersiveMode) {
                    exitImmersiveMode();
                }
            }
        });

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Register the back callback in onViewCreated, when the View Lifecycle has reached CREATED
        immersiveBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitImmersiveMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), immersiveBackCallback);
    }

    private void updateToolbarButtonsVisibility(int position) {
        boolean hasTranscript = currentMedia != null && currentMedia.hasTranscript();

        MenuItem controlsItem = toolbar.getMenu().findItem(R.id.transcript_menu_item);
        if (controlsItem != null) {
            controlsItem.setVisible(hasTranscript);
            updateControlsBarButton();
        }

        MenuItem floatingItem = toolbar.getMenu().findItem(R.id.floating_transcript_item);
        if (floatingItem != null) {
            floatingItem.setVisible(hasTranscript);
            updateFloatingTranscriptButton();
        }
    }

    private void updateControlsBarButton() {
        MenuItem item = toolbar.getMenu().findItem(R.id.transcript_menu_item);
        if (item == null || item.getIcon() == null) {
            return;
        }
        TranscriptPageFragment transcriptFragment = getTranscriptPageFragment();
        boolean isTranscript = pager.getCurrentItem() == POS_TRANSCRIPT;
        boolean controlsVisible = transcriptFragment != null && transcriptFragment.isControlsBarVisible();
        item.getIcon().mutate().setAlpha(!isTranscript || controlsVisible ? 255 : 128);
    }

    private void toggleFloatingTranscript() {
        if (currentMedia == null) {
            return;
        }
        boolean enabled = !UserPreferences.isFloatingTranscriptEnabled();
        if (enabled && !Settings.canDrawOverlays(requireContext())) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
            return;
        }
        UserPreferences.setFloatingTranscriptEnabled(enabled);
        if (enabled) {
            TranscriptFloatingWindowService.start(requireContext(), currentMedia.getId());
        } else {
            TranscriptFloatingWindowService.stop(requireContext());
        }
        updateFloatingTranscriptButton();
    }

    private void updateFloatingTranscriptButton() {
        MenuItem item = toolbar.getMenu().findItem(R.id.floating_transcript_item);
        if (item == null) {
            return;
        }
        item.setChecked(UserPreferences.isFloatingTranscriptEnabled());
    }

    private TranscriptPageFragment getTranscriptPageFragment() {
        Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + POS_TRANSCRIPT);
        if (fragment instanceof TranscriptPageFragment) {
            return (TranscriptPageFragment) fragment;
        }
        return null;
    }

    public void toggleImmersiveMode() {
        if (isImmersiveMode) {
            exitImmersiveMode();
        } else {
            enterImmersiveMode();
        }
    }

    private void enterImmersiveMode() {
        if (getActivity() == null || getView() == null) {
            return;
        }
        isImmersiveMode = true;

        // Re-register the back callback at the end of the dispatcher to give it the highest priority
        if (immersiveBackCallback != null) {
            immersiveBackCallback.remove();
        }
        immersiveBackCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                exitImmersiveMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), immersiveBackCallback);

        // Hide the toolbar and the bottom controls
        toolbar.setVisibility(View.GONE);
        playtimeLayout.setVisibility(View.GONE);

        // Hide the main navigation bar
        View bottomNav = getActivity().findViewById(R.id.bottomNavigationView);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.GONE);
        }
        View bottomPadding = getActivity().findViewById(R.id.bottom_padding);
        if (bottomPadding != null) {
            bottomPadding.setVisibility(View.GONE);
        }

        // Important: ViewPager2 is inside a RelativeLayout, so remove the top/bottom rules and use MATCH_PARENT
        RelativeLayout.LayoutParams pagerParams = (RelativeLayout.LayoutParams) pager.getLayoutParams();
        pagerParams.removeRule(RelativeLayout.BELOW);
        pagerParams.removeRule(RelativeLayout.ABOVE);
        pagerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        pagerParams.setMargins(0, 0, 0, 0);
        pager.setLayoutParams(pagerParams);
        pager.requestLayout();

        // Hide the system status bar and navigation bar
        View decorView = getActivity().getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            decorView.setSystemUiVisibility(flags);
        }

        TranscriptPageFragment transcriptFragment = getTranscriptPageFragment();
        if (transcriptFragment != null) {
            transcriptFragment.setImmersiveMode(true);
        }
    }

    private void exitImmersiveMode() {
        if (getActivity() == null || getView() == null) {
            return;
        }
        isImmersiveMode = false;

        // Disable the back callback so the back button behaves normally again
        if (immersiveBackCallback != null) {
            immersiveBackCallback.setEnabled(false);
        }

        // Restore the toolbar and the bottom controls
        toolbar.setVisibility(View.VISIBLE);
        playtimeLayout.setVisibility(View.VISIBLE);

        // Restore the main navigation bar
        View bottomNav = getActivity().findViewById(R.id.bottomNavigationView);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
        }
        View bottomPadding = getActivity().findViewById(R.id.bottom_padding);
        if (bottomPadding != null) {
            bottomPadding.setVisibility(View.VISIBLE);
        }

        // Restore the original ViewPager2 layout parameters
        RelativeLayout.LayoutParams pagerParams = (RelativeLayout.LayoutParams) pager.getLayoutParams();
        pagerParams.addRule(RelativeLayout.BELOW, R.id.toolbar);
        pagerParams.addRule(RelativeLayout.ABOVE, R.id.playtime_layout);
        pagerParams.height = 0; // 0dp with top/bottom rules fills the middle area
        pagerParams.setMargins(0, 0, 0, (int) (12 * getResources().getDisplayMetrics().density));
        pager.setLayoutParams(pagerParams);
        pager.requestLayout();

        // Show the system status bar and navigation bar
        View decorView = getActivity().getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        TranscriptPageFragment transcriptFragment = getTranscriptPageFragment();
        if (transcriptFragment != null) {
            transcriptFragment.setImmersiveMode(false);
        }
        updateControlsBarButton();
    }

    private void setChapterDividers() {
        if (currentMedia == null) {
            return;
        }

        float[] dividerPos = null;

        if (currentMedia.getChapters() != null && !currentMedia.getChapters().isEmpty()) {
            List<Chapter> chapters = currentMedia.getChapters();
            int duration = currentMedia.getDuration();
            if (duration > 0) {
                dividerPos = new float[chapters.size()];
                for (int i = 0; i < chapters.size(); i++) {
                    dividerPos[i] = chapters.get(i).getStart() / (float) duration;
                }
            }
        }

        sbPosition.setDividerPos(dividerPos);
    }

    private void setupControlButtons() {
        butRev.setOnClickListener(v -> {
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                final FeedMedia media = currentMedia;
                PlaybackController.bindToMedia3Service(getContext(), controller -> {
                    if (controller.getCurrentMediaItem() != null) {
                        controller.seekBack();
                    } else if (media != null) {
                        seekWithoutPlaybackService(media,
                                Math.max(0, media.getPosition() - UserPreferences.getRewindSecs() * 1000));
                    }
                });
            } else {
                PlaybackController.bindToService(getActivity(), playbackService ->
                        playbackService.seekTo(playbackService.getCurrentPosition()
                                - UserPreferences.getRewindSecs() * 1000));
            }
        });
        butRev.setOnLongClickListener(v -> {
            SkipPreferenceDialog.showSkipPreference(getContext(),
                    SkipPreferenceDialog.SkipDirection.SKIP_REWIND, txtvRev);
            return true;
        });
        butPlay.setOnClickListener(v -> {
            if (PlaybackService.isRunning
                    && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
                if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                    PlaybackController.bindToMedia3Service(getContext(), MediaController::pause);
                } else {
                    getActivity().sendBroadcast(
                            MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_PAUSE));
                }
            } else {
                new PlaybackServiceStarter(getContext(), currentMedia)
                        .callEvenIfRunning(true)
                        .start();
            }
        });
        butFF.setOnClickListener(v -> {
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                final FeedMedia media = currentMedia;
                PlaybackController.bindToMedia3Service(getContext(), controller -> {
                    if (controller.getCurrentMediaItem() != null) {
                        controller.seekForward();
                    } else if (media != null) {
                        seekWithoutPlaybackService(media, Math.min(media.getDuration(),
                                media.getPosition() + UserPreferences.getFastForwardSecs() * 1000));
                    }
                });
            } else {
                PlaybackController.bindToService(getActivity(), playbackService ->
                        playbackService.seekTo(playbackService.getCurrentPosition()
                                + UserPreferences.getFastForwardSecs() * 1000));
            }
        });
        butFF.setOnLongClickListener(v -> {
            SkipPreferenceDialog.showSkipPreference(getContext(),
                    SkipPreferenceDialog.SkipDirection.SKIP_FORWARD, txtvFF);
            return false;
        });
        butSkip.setOnClickListener(v -> {
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                PlaybackController.bindToMedia3Service(getContext(), MediaController::seekToNextMediaItem);
            } else {
                getActivity().sendBroadcast(
                        MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_NEXT));
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onItemsUpdate(FeedItemEvent event) {
        if (currentMedia == null) {
            return;
        }
        if (FeedItemEvent.indexOfItemWithId(event.items, currentMedia.getItemId()) != -1) {
            AudioPlayerFragment.this.loadMediaInfo(false);
        }
        if (event.items.isEmpty()) {
            updatePosition(new PlaybackPositionEvent(currentMedia.getPosition(),
                    currentMedia.getDuration()));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackServiceChanged(PlaybackServiceEvent event) {
        if (event.action == PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN) {
            ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    private void setupLengthTextView() {
        showTimeLeft = UserPreferences.shouldShowRemainingTime();
        txtvLength.setOnClickListener(v -> {
            if (currentMedia == null) {
                return;
            }
            showTimeLeft = !showTimeLeft;
            UserPreferences.setShowRemainTimeSetting(showTimeLeft);
            updatePosition(new PlaybackPositionEvent(currentMedia.getPosition(),
                    currentMedia.getDuration()));
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePlaybackSpeedButton(SpeedChangedEvent event) {
        String speedStr = new DecimalFormat("0.00").format(event.getNewSpeed());
        txtvPlaybackSpeed.setText(speedStr);
    }

    private void loadMediaInfo(boolean includingChapters) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.<FeedMedia>create(emitter -> {
                    FeedMedia media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
                    if (media != null) {
                        if (includingChapters) {
                            ChapterUtils.loadChapters(media, getContext(), false);
                        }
                        emitter.onSuccess(media);
                    } else {
                        emitter.onComplete();
                    }
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(media -> {
                    currentMedia = media;
                    updateUi();
                    if (media.getChapters() == null && !includingChapters) {
                        loadMediaInfo(true);
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void updateUi() {
        if (currentMedia == null) {
            return;
        }
        updatePosition(new PlaybackPositionEvent(currentMedia.getPosition(), currentMedia.getDuration()));
        updatePlaybackSpeedButton(new SpeedChangedEvent(PlaybackSpeedUtils.getCurrentPlaybackSpeed(currentMedia)));
        setChapterDividers();
        setupOptionsMenu();
        boolean isPlaying = PlaybackService.isRunning
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING;
        butPlay.setIsShowPlay(!isPlaying);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void sleepTimerUpdate(SleepTimerUpdatedEvent event) {
        boolean timerActive = !event.isCancelled() && !event.isOver();
        toolbar.getMenu().findItem(R.id.set_sleeptimer_item).setVisible(!timerActive);
        toolbar.getMenu().findItem(R.id.disable_sleeptimer_item).setVisible(timerActive);
        if (event.isCancelled() || event.wasJustEnabled() || event.isOver()) {
            AudioPlayerFragment.this.loadMediaInfo(false);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        loadMediaInfo(false);
        EventBus.getDefault().register(this);
        txtvRev.setText(NumberFormat.getInstance().format(UserPreferences.getRewindSecs()));
        txtvFF.setText(NumberFormat.getInstance().format(UserPreferences.getFastForwardSecs()));
        updateFloatingTranscriptButton();
        updateControlsBarButton();
    }

    @Override
    public void onStop() {
        super.onStop();
        progressIndicator.setVisibility(View.GONE);
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
        if (isImmersiveMode) {
            exitImmersiveMode();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void bufferUpdate(BufferUpdateEvent event) {
        if (event.hasStarted()) {
            progressIndicator.setVisibility(View.VISIBLE);
        } else if (event.hasEnded()) {
            progressIndicator.setVisibility(View.GONE);
        } else if (currentMedia != null && !currentMedia.localFileAvailable()) {
            sbPosition.setSecondaryProgress((int) (event.getProgress() * sbPosition.getMax()));
        } else {
            sbPosition.setSecondaryProgress(0);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePosition(PlaybackPositionEvent event) {
        if (txtvPosition == null || txtvLength == null || sbPosition == null) {
            return;
        }

        float playbackSpeed = currentMedia != null ? PlaybackSpeedUtils.getCurrentPlaybackSpeed(currentMedia) : 1.0f;
        TimeSpeedConverter converter = new TimeSpeedConverter(playbackSpeed);
        int convertedPosition = converter.convert(event.getPosition());
        int convertedDuration = converter.convert(event.getDuration());
        int remainingTime = converter.convert(Math.max(event.getDuration() - event.getPosition(), 0));
        if (currentMedia != null) {
            currentChapterIndex = Chapter.getAfterPosition(currentMedia.getChapters(), convertedPosition);
        }
        Log.d(TAG, "currentPosition " + Converter.getDurationStringLong(convertedPosition));
        if (convertedPosition == Playable.INVALID_TIME || convertedDuration == Playable.INVALID_TIME) {
            Log.w(TAG, "Could not react to position observer update because of invalid time");
            return;
        }
        txtvPosition.setText(Converter.getDurationStringLong(convertedPosition));
        txtvPosition.setContentDescription(getString(R.string.position,
                Converter.getDurationStringLocalized(getContext(), convertedPosition)));
        showTimeLeft = UserPreferences.shouldShowRemainingTime();
        if (showTimeLeft) {
            txtvLength.setContentDescription(getString(R.string.remaining_time,
                    Converter.getDurationStringLocalized(getContext(), remainingTime)));
            txtvLength.setText(((remainingTime > 0) ? "-" : "") + Converter.getDurationStringLong(remainingTime));
        } else {
            txtvLength.setContentDescription(getString(R.string.chapter_duration,
                    Converter.getDurationStringLocalized(getContext(), convertedDuration)));
            txtvLength.setText(Converter.getDurationStringLong(convertedDuration));
        }

        if (!sbPosition.isPressed()) {
            float progress = ((float) event.getPosition()) / event.getDuration();
            sbPosition.setProgress((int) (progress * sbPosition.getMax()));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void mediaPlayerError(PlayerErrorEvent event) {
        MediaPlayerErrorDialog.show(getActivity(), event);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (currentMedia == null || txtvLength == null) {
            return;
        }

        if (fromUser) {
            float prog = progress / ((float) seekBar.getMax());
            float playbackSpeed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(currentMedia);
            TimeSpeedConverter converter = new TimeSpeedConverter(playbackSpeed);
            int duration = currentMedia.getDuration();
            int position = converter.convert((int) (prog * duration));
            int newChapterIndex = Chapter.getAfterPosition(currentMedia.getChapters(), position);
            if (newChapterIndex > -1) {
                if (!sbPosition.isPressed() && currentChapterIndex != newChapterIndex) {
                    currentChapterIndex = newChapterIndex;
                    position = (int) currentMedia.getChapters().get(currentChapterIndex).getStart();
                    seekedToChapterStart = true;
                    final int positionFinal = position;
                    if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                        PlaybackController.bindToMedia3Service(getContext(), controller ->
                                controller.seekTo(positionFinal));
                    } else {
                        PlaybackController.bindToService(getActivity(), playbackService ->
                                playbackService.seekTo(positionFinal));
                    }
                    sbPosition.highlightCurrentChapter();
                }
                txtvSeek.setText(currentMedia.getChapters().get(newChapterIndex).getTitle()
                        + "\n" + Converter.getDurationStringLong(position));
            } else {
                txtvSeek.setText(Converter.getDurationStringLong(position));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        cardViewSeek.setScaleX(.8f);
        cardViewSeek.setScaleY(.8f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .start();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekedToChapterStart) {
            seekedToChapterStart = false;
        } else if (currentMedia != null) {
            final float prog = seekBar.getProgress() / ((float) seekBar.getMax());
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                final FeedMedia media = currentMedia;
                PlaybackController.bindToMedia3Service(getContext(), controller -> {
                    if (controller.getCurrentMediaItem() != null) {
                        controller.seekTo((long) (controller.getDuration() * prog));
                    } else {
                        seekWithoutPlaybackService(media, (int) (media.getDuration() * prog));
                    }
                });
            } else {
                PlaybackController.bindToService(getActivity(), playbackService ->
                        playbackService.seekTo((int) (playbackService.getDuration() * prog)));
            }
        }
        cardViewSeek.setScaleX(1f);
        cardViewSeek.setScaleY(1f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(0f).scaleX(.8f).scaleY(.8f)
                .setDuration(200)
                .start();
    }

    private void seekWithoutPlaybackService(FeedMedia media, int position) {
        media.setPosition(position);
        DBWriter.setFeedMediaPlaybackInformation(media);
        EventBus.getDefault().post(new PlaybackPositionEvent(media.getPosition(), media.getDuration()));
    }

    public void setupOptionsMenu() {
        toolbar.getMenu().findItem(R.id.open_feed_item).setVisible(true);
        FeedItemMenuHandler.onPrepareMenu(toolbar.getMenu(),
                Collections.singletonList(currentMedia.getItem()));
        ((CastEnabledActivity) getActivity()).requestCastButton(toolbar.getMenu());
        updateToolbarButtonsVisibility(pager.getCurrentItem());
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (currentMedia == null) {
            return false;
        }

        final @Nullable FeedItem feedItem = currentMedia.getItem();
        if (feedItem != null && FeedItemMenuHandler.onMenuItemClicked(this, item.getItemId(), feedItem)) {
            return true;
        }

        final int itemId = item.getItemId();
        if (itemId == R.id.disable_sleeptimer_item || itemId == R.id.set_sleeptimer_item) {
            new SleepTimerDialog().show(getChildFragmentManager(), "SleepTimerDialog");
            return true;
        } else if (itemId == R.id.transcript_item) {
            scrollToPage(POS_TRANSCRIPT, true);
            return true;
        } else if (itemId == R.id.floating_transcript_item) {
            toggleFloatingTranscript();
            return true;
        } else if (itemId == R.id.transcript_menu_item) {
            if (pager.getCurrentItem() == POS_TRANSCRIPT) {
                TranscriptPageFragment transcriptFragment = getTranscriptPageFragment();
                if (transcriptFragment != null) {
                    transcriptFragment.toggleControlsBar();
                    updateControlsBarButton();
                }
            } else {
                scrollToPage(POS_TRANSCRIPT, true);
            }
            return true;
        } else if (itemId == R.id.open_feed_item) {
            if (feedItem != null) {
                openFeed(feedItem.getFeed());
            }
            return true;
        }
        return false;
    }

    private void openFeed(Feed feed) {
        if (feed == null) {
            return;
        }
        if (feed.getState() == Feed.STATE_NOT_SUBSCRIBED) {
            startActivity(new OnlineFeedviewActivityStarter(getContext(), feed.getDownloadUrl()).getIntent());
        } else {
            new MainActivityStarter(getContext()).withOpenFeed(feed.getId()).withClearTop().start();
        }
    }

    public void fadePlayerToToolbar(float slideOffset) {
        if (isImmersiveMode) {
            return; // Do not interfere with BottomSheet animations in immersive mode
        }
        float playerFadeProgress = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.2f)) / 0.2f;
        View player = getView().findViewById(R.id.playerFragment);
        player.setAlpha(1 - playerFadeProgress);
        player.setVisibility(playerFadeProgress > 0.99f ? View.INVISIBLE : View.VISIBLE);
        float toolbarFadeProgress = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.6f)) / 0.2f;
        toolbar.setAlpha(toolbarFadeProgress);
        toolbar.setVisibility(toolbarFadeProgress < 0.01f ? View.INVISIBLE : View.VISIBLE);
    }

    private static class AudioPlayerPagerAdapter extends FragmentStateAdapter {
        private static final String TAG = "AudioPlayerPagerAdapter";

        public AudioPlayerPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "getItem(" + position + ")");

            switch (position) {
                case POS_COVER:
                    return new CoverFragment();
                case POS_DESCRIPTION:
                    return new ItemDescriptionFragment();
                case POS_TRANSCRIPT:
                    return new TranscriptPageFragment();
                default:
                    return new ItemDescriptionFragment();
            }
        }

        @Override
        public int getItemCount() {
            return NUM_CONTENT_FRAGMENTS;
        }
    }

    public void scrollToPage(int page, boolean smoothScroll) {
        if (pager == null) {
            return;
        }

        pager.setCurrentItem(page, smoothScroll);

        Fragment visibleChild = getChildFragmentManager().findFragmentByTag("f" + POS_DESCRIPTION);
        if (visibleChild instanceof ItemDescriptionFragment) {
            ((ItemDescriptionFragment) visibleChild).scrollToTop();
        }
    }

    public void scrollToPage(int page) {
        scrollToPage(page, false);
    }
}
