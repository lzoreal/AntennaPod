package de.danoeh.antennapod.playback.service;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.ad.AdScanScheduler;
import de.danoeh.antennapod.ad.HeadScanEngine;
import de.danoeh.antennapod.ad.StreamingAdMatcher;
import de.danoeh.antennapod.ad.StreamingAdScanner;
import de.danoeh.antennapod.event.AdScanEvent;
import de.danoeh.antennapod.model.feed.AdFeedSettings;
import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.playback.service.internal.AdSkipCommand;
import de.danoeh.antennapod.playback.service.internal.AdSkipController;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.PlayerErrorEvent;
import de.danoeh.antennapod.event.PlaybackServiceShutdownEvent;
import de.danoeh.antennapod.event.StreamingConfirmationEvent;
import de.danoeh.antennapod.event.settings.VolumeAdaptionChangedEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.BufferUpdateEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.event.playback.PlaybackServiceEvent;
import de.danoeh.antennapod.event.playback.SleepTimerUpdatedEvent;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.base.PlayerStatus;
import de.danoeh.antennapod.playback.base.RewindAfterPauseUtils;
import de.danoeh.antennapod.playback.cast.CastPlayerWrapper;
import de.danoeh.antennapod.playback.service.internal.ExoPlayerUtils;
import de.danoeh.antennapod.playback.service.internal.MediaLibrarySessionCallback;
import de.danoeh.antennapod.playback.service.internal.PlayableUtils;
import de.danoeh.antennapod.playback.service.internal.SkipUtils;
import de.danoeh.antennapod.playback.service.internal.SleepTimer;
import de.danoeh.antennapod.playback.service.internal.ClockSleepTimer;
import de.danoeh.antennapod.playback.service.internal.EpisodeSleepTimer;
import de.danoeh.antennapod.playback.service.internal.TranscriptSync;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import de.danoeh.antennapod.storage.preferences.SleepTimerType;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptFloatingWindowService;
import de.danoeh.antennapod.ui.widget.WidgetUpdater;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Calendar;
import java.util.Collections;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Media3PlaybackService extends MediaLibraryService {
    private static final String TAG = "M3PlaybackService";
    private static final long POSITION_SAVE_INTERVAL_MS = 5000;
    private ExoPlayer exoPlayer;
    private Player player;
    private MediaLibrarySession mediaSession;
    private FeedMedia currentPlayable;
    private String pendingStreamMediaId;
    private boolean allowStreamingThisTime = false;
    private Disposable mediaLoaderDisposable;
    private Disposable positionObserverDisposable;
    private Disposable queueLoaderDisposable;
    private long lastPositionSaveTime = 0;
    private String playedMediaId = null;
    private int playbackStartPosition = -1;
    private int playedDurationCountedUntil = -1;
    private SleepTimer sleepTimer;
    @Nullable
    private LoudnessEnhancer loudnessEnhancer = null;
    private float volumeAdaptionFactor = 1.0f;
    private final Handler adSkipHandler = new Handler(Looper.getMainLooper());
    private AdSkipController adSkipController;
    private boolean programmaticSeek;
    private long adSkipMediaId = -1;
    private TranscriptSync.Shift lastTranscriptShift;
    private volatile long adStreamMediaId = -1;
    private HeadScanEngine.ReferenceSet adReferences;
    private String adReferencesKey;
    private StreamingAdScanner adStreamScanner;
    private volatile long lastPlayerPositionMs;
    private final Runnable adSkipTick = new Runnable() {
        @Override
        public void run() {
            if (adSkipController != null && exoPlayer != null && exoPlayer.isPlaying() && !isCasting()) {
                long position = exoPlayer.getCurrentPosition();
                lastPlayerPositionMs = position;
                PlaybackServiceInterface.currentPositionMs = position;
                Long target = adSkipController.skipAt(position);
                if (target != null) {
                    programmaticSeek = true;
                    exoPlayer.seekTo(target);
                    AdSegment skipped = adSkipController.lastSkipped();
                    Log.i(TAG, "skip media=" + adSkipMediaId + " from=" + position
                            + " to=" + target + " tier=" + (skipped == null ? -1 : skipped.getTier())
                            + " seg=" + (skipped == null ? "-" : skipped.getStartMs()
                            + "-" + skipped.getEndMs()));
                    if (skipped != null) {
                        showSkipUndo(skipped, position);
                    }
                }
                adSkipHandler.postDelayed(this, 250);
            }
        }
    };
    private final BroadcastReceiver adSkipReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (adSkipController == null) {
                return;
            }
            if (AdSkipCommand.ACTION_UNDO.equals(intent.getAction())) {
                undoAdSkip();
            } else if (AdSkipCommand.ACTION_DISABLE_ALL.equals(intent.getAction())
                    && currentPlayable != null) {
                adSkipController.disableAllForMedia(currentPlayable.getId());
            }
        }
    };

    @UnstableApi
    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        IntentFilter adSkipFilter = new IntentFilter();
        adSkipFilter.addAction(AdSkipCommand.ACTION_UNDO);
        adSkipFilter.addAction(AdSkipCommand.ACTION_DISABLE_ALL);
        ContextCompat.registerReceiver(this, adSkipReceiver, adSkipFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        DefaultMediaNotificationProvider notificationProvider = new DefaultMediaNotificationProvider(this,
                session -> R.id.notification_playing,
                NotificationUtils.CHANNEL_ID_PLAYING, R.string.notification_channel_playing);
        notificationProvider.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(notificationProvider);

        adStreamScanner = new StreamingAdScanner(this::onStreamingChunk);
        exoPlayer = ExoPlayerUtils.buildPlayer(this, adStreamScanner, () -> lastPlayerPositionMs);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                initLoudnessEnhancer(audioSessionId);
            }
        });
        initLoudnessEnhancer(exoPlayer.getAudioSessionId());
        Player maybeCastPlayer = CastPlayerWrapper.wrap(exoPlayer, this);
        player = new ForwardingPlayer(maybeCastPlayer) {
            @Override
            @NonNull
            public Player.Commands getAvailableCommands() {
                return super.getAvailableCommands()
                        .buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .remove(Player.COMMAND_SET_REPEAT_MODE)
                        .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                        .build();
            }

            @Override
            public void setRepeatMode(int repeatMode) {
                // Ignored, some car stereos set this via Bluetooth, which would make episodes loop forever
            }

            @Override
            public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
                // Ignored, some car stereos set this via Bluetooth
            }

            @Override
            public void play() {
                if (handleStreamingConfirmation()) {
                    return;
                } else if (shouldBlockForStreamingConfirmation()) {
                    showStreamingConfirmation(currentPlayable);
                    return;
                }

                if (currentPlayable != null && !getPlayWhenReady()) {
                    long savedPosition = getCurrentPosition();
                    long startPosition = RewindAfterPauseUtils.calculatePositionWithRewind(
                            (int) savedPosition, currentPlayable.getLastPlayedTimeStatistics());
                    if (startPosition != savedPosition) {
                        seekTo(startPosition);
                    }
                }
                super.play();
            }

            @Override
            public void setPlaybackSpeed(float speed) {
                super.setPlaybackSpeed(speed);
                PlaybackPreferences.setCurrentlyPlayingTemporaryPlaybackSpeed(speed);
                EventBus.getDefault().post(new SpeedChangedEvent(speed));
            }

            @Override
            public void seekBack() {
                seekTo(Math.max(0, getCurrentPosition() - UserPreferences.getRewindSecs() * 1000L));
            }

            @Override
            public void seekForward() {
                long duration = getDuration();
                long target = getCurrentPosition() + UserPreferences.getFastForwardSecs() * 1000L;

                if (duration > 0 && target >= duration) {
                    handlePlaybackEnded();
                    return;
                }

                seekTo(target);
            }

            @Override
            public void seekToNextMediaItem() {
                if (isRequestOfOtherApp()) {
                    // Some controllers (e.g. Gadgetbridge) call this directly, bypassing
                    // onMediaButtonEvent, so the remap needs to apply here too.
                    performHardwareButtonAction(UserPreferences.getHardwareForwardButton());
                    return;
                }
                skipToNextInQueue();
            }

            @Override
            public void seekToNext() {
                performHardwareButtonAction(UserPreferences.getHardwareForwardButton());
            }

            @Override
            public void seekToPreviousMediaItem() {
                if (isRequestOfOtherApp()) {
                    performHardwareButtonAction(UserPreferences.getHardwarePreviousButton());
                    return;
                }
                super.seekToPreviousMediaItem();
            }

            @Override
            public void seekToPrevious() {
                performHardwareButtonAction(UserPreferences.getHardwarePreviousButton());
            }

            private void performHardwareButtonAction(int action) {
                switch (action) {
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        skipToNextInQueue();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        seekTo(0);
                        break;
                    case KeyEvent.KEYCODE_MEDIA_REWIND:
                        seekBack();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    default:
                        seekForward();
                        break;
                }
            }

            private void skipToNextInQueue() {
                if (currentPlayable != null) {
                    saveCurrentPosition();
                    startNextInQueue(currentPlayable, true, false);
                }
            }

            @Override
            public void seekTo(long positionMs) {
                super.seekTo(positionMs);
                EventBus.getDefault().post(
                        new PlaybackPositionEvent((int) positionMs, (int) player.getDuration()));
            }
        };
        player.addListener(playerListener);
        // Start the floating transcript when resuming while playing
        if (player != null && player.isPlaying() && currentPlayable != null) {
            startFloatingTranscript(currentPlayable.getId());
        }
        mediaSession = new MediaLibraryService.MediaLibrarySession.Builder(this, player, sessionCallback)
                .setSessionActivity(new MainActivityStarter(this).withOpenPlayer().getPendingIntent())
                .build();
        if (isCasting()) {
            keepServiceRunningWhileCasting();
            loadCurrentMediaWhileCasting();
        }
        UserPreferences.registerFloatingTranscriptListener(transcriptPrefListener);
    }

    @UnstableApi
    private boolean isRequestOfOtherApp() {
        MediaSession.ControllerInfo controller = mediaSession.getControllerForCurrentRequest();
        // Skip buttons of the app itself (player screen, notification, widget) are not remapped
        return controller != null && !getPackageName().equals(controller.getPackageName());
    }

    private void loadCurrentMediaWhileCasting() {
        long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (mediaId == PlaybackPreferences.NO_MEDIA_PLAYING) {
            return;
        }
        PlaybackController.bindToMedia3Service(this, controller -> {
            if (player.getCurrentMediaItem() != null || exoPlayer.getMediaItemCount() > 0 || !isCasting()) {
                return;
            }
            controller.setPlayWhenReady(false);
            controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(mediaId));
            controller.prepare();
        });
    }

    /**
     * When freshly binding to the service to start playback (while chromecast is already connected),
     * the service gets started but casting does not transition to PLAYING fast enough. So when unbinding,
     * the service gets destroyed again and releases the session, which stops casting again.
     * This method manually starts the service, to be used when connected to chromecast.
     */
    private void keepServiceRunningWhileCasting() {
        try {
            startService(new Intent(this, Media3PlaybackService.class));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to keep service running while casting", e);
        }
    }

    private void startFloatingTranscript(long mediaId) {
        if (currentPlayable instanceof FeedMedia) {
            FeedMedia media = (FeedMedia) currentPlayable;
            if (media.getItem() == null || !hasTranscriptAvailable(media)) {
                stopFloatingTranscript();
                return;
            }
        }
        TranscriptFloatingWindowService.start(this, mediaId);
    }

    /** Official transcripts come from the feed; generated ones only exist as a local file. */
    private static boolean hasTranscriptAvailable(FeedMedia media) {
        if (media.hasTranscript()) {
            return true;
        }
        String transcriptFile = media.getGeneratedTranscriptFileUrl();
        if (transcriptFile == null) {
            return false;
        }
        File file = new File(transcriptFile);
        return file.isFile() && file.length() > 0;
    }

    private void stopFloatingTranscript() {
        TranscriptFloatingWindowService.stop(this);
    }

    MediaLibrarySessionCallback sessionCallback = new MediaLibrarySessionCallback(this) {
        @Override
        @NonNull
        @UnstableApi
        public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand customCommand,
                @NonNull Bundle args) {
            if (customCommand.customAction.equals(SESSION_COMMAND_PLAYBACK_SPEED.customAction)) {
                setNextPlaybackSpeed();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            } else if (customCommand.customAction.equals(SESSION_COMMAND_NEXT_CHAPTER.customAction)) {
                seekToNextChapter();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            } else if (customCommand.customAction.equals(SESSION_COMMAND_SKIP_SILENCE.customAction)) {
                boolean enabled = MediaLibrarySessionCallback.getBoolean(args, false);
                PlaybackPreferences.setCurrentlyPlayingTemporarySkipSilence(enabled);
                exoPlayer.setSkipSilenceEnabled(enabled);
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            } else if (customCommand.customAction.equals(SESSION_COMMAND_SET_SLEEP_TIMER.customAction)) {
                startSleepTimer(SleepTimerPreferences.timerMillisOrEpisodes());
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            } else if (customCommand.customAction.equals(SESSION_COMMAND_DISABLE_SLEEP_TIMER.customAction)) {
                disableSleepTimer();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            } else if (customCommand.customAction.equals(SESSION_COMMAND_EXTEND_SLEEP_TIMER.customAction)) {
                extendSleepTimer(MediaLibrarySessionCallback.getLong(args, 0));
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            return super.onCustomCommand(session, controller, customCommand, args);
        }
    };

    @UnstableApi
    private final Player.Listener playerListener = new Player.Listener() {
        private boolean wasTemporarilySuspended = false;

        @Override
        public void onPlaybackSuppressionReasonChanged(int playbackSuppressionReason) {
            if (playbackSuppressionReason
                    == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                wasTemporarilySuspended = true;
            } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    && wasTemporarilySuspended && currentPlayable != null) {
                wasTemporarilySuspended = false;
                long savedPosition = player.getCurrentPosition();
                long startPosition = RewindAfterPauseUtils.calculatePositionWithRewind(
                        (int) savedPosition, currentPlayable.getLastPlayedTimeStatistics());
                if (startPosition != savedPosition) {
                    player.seekTo(startPosition);
                }
            } else {
                wasTemporarilySuspended = false;
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
                EventBus.getDefault().post(BufferUpdateEvent.started());
                PlaybackServiceInterface.isPlaying = player.getPlayWhenReady(); // Immediately show as playing
                updatePlaybackPreferences();
            } else {
                EventBus.getDefault().post(BufferUpdateEvent.ended());
            }
            if ((playbackState == Player.STATE_READY && player.getPlayWhenReady())
                    || playbackState == Player.STATE_ENDED) {
                saveCurrentPosition();
            }
            if (playbackState == Player.STATE_READY && player.getPlayWhenReady()
                    && adSkipController != null) {
                adSkipHandler.removeCallbacks(adSkipTick);
                adSkipHandler.postDelayed(adSkipTick, 250);
            }
            if (playbackState == Player.STATE_ENDED && currentPlayable != null) {
                handlePlaybackEnded();
                stopFloatingTranscript();
            }
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition, int reason) {
            Log.i(TAG, "Position discontinuity reason=" + reason
                    + " old=" + oldPosition.positionMs
                    + " new=" + newPosition.positionMs
                    + " newMedia=" + (newPosition.mediaItem == null
                    ? "null" : newPosition.mediaItem.mediaId));
            if ((reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
                    || currentPlayable == null || oldPosition.mediaItem == null || newPosition.mediaItem == null
                    || !oldPosition.mediaItem.mediaId.equals(newPosition.mediaItem.mediaId)
                    || !String.valueOf(currentPlayable.getId()).equals(newPosition.mediaItem.mediaId)) {
                return;
            }
            checkpointPlayedDuration((int) oldPosition.positionMs);
            playedDurationCountedUntil = (int) newPosition.positionMs;
            PlaybackServiceInterface.currentPositionMs = newPosition.positionMs;
            if (adSkipController != null) {
                if (programmaticSeek) {
                    programmaticSeek = false;
                    adSkipController.onProgrammaticSeek(newPosition.positionMs);
                } else {
                    adSkipController.onUserSeek(newPosition.positionMs);
                }
            }
            if (adStreamScanner != null) {
                adStreamScanner.jumpTo(newPosition.positionMs);
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            syncMediaIdFromPlayer();
            PlaybackServiceInterface.isPlaying = !Util.shouldShowPlayButton(player);
            PlaybackServiceInterface.currentPositionMs = player.getCurrentPosition();
            updatePlaybackPreferences();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            syncMediaIdFromPlayer();
            PlaybackServiceInterface.isPlaying = !Util.shouldShowPlayButton(player);
            PlaybackServiceInterface.currentPositionMs = player.getCurrentPosition();
            if (isPlaying && adSkipController != null) {
                adSkipHandler.removeCallbacks(adSkipTick);
                adSkipHandler.postDelayed(adSkipTick, 250);
            }
            if (isPlaying && player.getCurrentMediaItem() != null) {
                playedMediaId = player.getCurrentMediaItem().mediaId;
                saveCurrentPosition();
            }
            if (PlaybackServiceInterface.isPlaying) {
                lastPositionSaveTime = System.currentTimeMillis();
                setupPositionObserver();
                // Start the floating transcript
                if (currentPlayable != null) {
                    startFloatingTranscript(currentPlayable.getId());
                }
            } else {
                cancelPositionObserver();
                saveCurrentPosition();
                if (currentPlayable != null) {
                    SynchronizationQueue.getInstance().enqueueEpisodePlayed(currentPlayable,
                            playbackStartPosition, false);
                }
            }
            WidgetUpdater.WidgetState widgetState = new WidgetUpdater.WidgetState(currentPlayable,
                    PlaybackServiceInterface.isPlaying ? PlayerStatus.PLAYING : PlayerStatus.PAUSED,
                    (int) player.getContentPosition(), (int) player.getDuration(),
                    player.getPlaybackParameters().speed);
            Schedulers.io().scheduleDirect(() ->
                    WidgetUpdater.updateWidget(Media3PlaybackService.this, widgetState));
            updatePlaybackPreferences();

            // Auto-enable sleep timer when playback starts
            if (PlaybackServiceInterface.isPlaying && sleepTimer == null && SleepTimerPreferences.autoEnable()) {
                int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                if (SleepTimerPreferences.isInTimeRange(
                        SleepTimerPreferences.autoEnableFrom(),
                        SleepTimerPreferences.autoEnableTo(),
                        currentHour)) {
                    startSleepTimer(SleepTimerPreferences.timerMillisOrEpisodes());
                }
            }
        }

        @Override
        public void onDeviceInfoChanged(@NonNull DeviceInfo deviceInfo) {
            PlaybackServiceInterface.isCasting =
                    deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE;
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                keepServiceRunningWhileCasting();
                loadCurrentMediaWhileCasting();
            }
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (mediaItem == null) {
                if (currentPlayable != null
                        && CastPlayerWrapper.hasPlaybackJustFinished(Media3PlaybackService.this)) {
                    handlePlaybackEnded();
                } else {
                    currentPlayable = null;
                }
            } else {
                ensureCurrentMediaLoaded();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            PlaybackServiceInterface.isPlaying = false;
            EventBus.getDefault().post(new PlayerErrorEvent(
                    ExoPlayerUtils.translateErrorReason(error, Media3PlaybackService.this)));
            EventBus.getDefault().post(new PlayerStatusEvent());
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && AdSkipCommand.ACTION_UNDO.equals(intent.getAction())) {
            undoAdSkip();
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @UnstableApi
    @Override
    public void onDestroy() {
        PlaybackServiceInterface.isPlaying = false;
        PlaybackServiceInterface.isCasting = false;
        cancelPositionObserver();
        if (sleepTimer != null) {
            sleepTimer.stop();
            sleepTimer = null;
        }
        EventBus.getDefault().unregister(this);
        if (mediaLoaderDisposable != null) {
            mediaLoaderDisposable.dispose();
            mediaLoaderDisposable = null;
        }
        if (queueLoaderDisposable != null) {
            queueLoaderDisposable.dispose();
            queueLoaderDisposable = null;
        }
        saveCurrentPosition();
        if (loudnessEnhancer != null) {
            loudnessEnhancer.release();
            loudnessEnhancer = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
        }
        // Let generated-transcript sessions stop before their stream cache disappears.
        EventBus.getDefault().post(new PlaybackServiceShutdownEvent());
        ExoPlayerUtils.releaseCache();
        if (mediaSession != null) {
            mediaSession.release();
        }
        adSkipHandler.removeCallbacks(adSkipTick);
        unregisterReceiver(adSkipReceiver);
        if (adStreamScanner != null) {
            adStreamScanner.shutdown();
        }
        // Stop the floating transcript
        stopFloatingTranscript();
        UserPreferences.unregisterFloatingTranscriptListener(transcriptPrefListener);
        super.onDestroy();
    }

    private void showSkipUndo(AdSegment skipped, long positionMs) {
        lastTranscriptShift = TranscriptSync.alignAfterSkip(adSkipMediaId, skipped, positionMs);
        Log.i(TAG, "skip undo shift=" + (lastTranscriptShift != null)
                + " media=" + adSkipMediaId + " at=" + positionMs);
        int seconds = Math.max(1, (skipped.getEndMs() - skipped.getStartMs()) / 1000);
        String message = getString(R.string.ad_skipped_message, seconds);
        EventBus.getDefault().post(new MessageEvent(message,
                ctx -> AdSkipCommand.undo(getApplicationContext()),
                getString(R.string.undo)));
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Without the permission the notification is dropped silently; the Snackbar above is
            // the only undo affordance then.
            return;
        }
        Intent undo = new Intent(this, Media3PlaybackService.class).setAction(AdSkipCommand.ACTION_UNDO);
        PendingIntent undoIntent = PendingIntent.getService(this, 1, undo,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationUtils.showAdSkipUndo(this, message,
                new MainActivityStarter(this).getPendingIntent(), undoIntent);
    }

    private void undoAdSkip() {
        if (adSkipController == null) {
            return;
        }
        AdSegment skipped = adSkipController.lastSkipped();
        if (skipped == null) {
            return;
        }
        Log.d(TAG, "Undid ad skip to " + skipped.getStartMs());
        if (exoPlayer != null) {
            programmaticSeek = true;
            exoPlayer.seekTo(skipped.getStartMs());
        }
        adSkipController.undoLast();
        TranscriptSync.undo(lastTranscriptShift);
        lastTranscriptShift = null;
        NotificationUtils.cancelAdSkipUndo(this);
    }

    private void setupPositionObserver() {
        if (positionObserverDisposable != null) {
            positionObserverDisposable.dispose();
        }

        positionObserverDisposable = Observable.interval(1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        ignored -> {
                            if (currentPlayable == null || player == null) {
                                return;
                            }
                            long position = player.getCurrentPosition();
                            long duration = player.getDuration();
                            float speed = player.getPlaybackParameters().speed;
                            if (duration > 0) {
                                EventBus.getDefault().post(
                                        new PlaybackPositionEvent((int) position, (int) duration));
                                WidgetUpdater.WidgetState widgetState = new WidgetUpdater.WidgetState(currentPlayable,
                                        Util.shouldShowPlayButton(player) ? PlayerStatus.PAUSED : PlayerStatus.PLAYING,
                                        (int) position, (int) duration, speed);
                                Schedulers.io().scheduleDirect(() ->
                                        WidgetUpdater.updateWidget(this, widgetState));
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastPositionSaveTime >= POSITION_SAVE_INTERVAL_MS) {
                                    saveCurrentPosition();
                                    lastPositionSaveTime = currentTime;
                                }
                                if (SkipUtils.skipEndingIfNecessary(this, currentPlayable, position, duration, speed)) {
                                    player.seekTo(player.getDuration());
                                }
                            }
                        }, error -> Log.e(TAG, "Position observer error", error));
    }

    private void cancelPositionObserver() {
        if (positionObserverDisposable != null) {
            positionObserverDisposable.dispose();
            positionObserverDisposable = null;
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void ensureCurrentMediaLoaded() {
        if (player == null || player.getCurrentMediaItem() == null) {
            return;
        }
        if (MediaItemAdapter.MEDIA_ID_CONFIRM_STREAMING.equals(player.getCurrentMediaItem().mediaId)) {
            return;
        }
        pendingStreamMediaId = null;
        try {
            long mediaId = Long.parseLong(player.getCurrentMediaItem().mediaId);
            if (currentPlayable == null || currentPlayable.getId() != mediaId) {
                if (mediaLoaderDisposable != null) {
                    mediaLoaderDisposable.dispose();
                }
                mediaLoaderDisposable = Single.fromCallable(() -> {
                    long previousMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
                    if (previousMediaId != PlaybackPreferences.NO_MEDIA_PLAYING && previousMediaId != mediaId) {
                        updateDatabaseAfterPlayback(DBReader.getFeedMedia(previousMediaId), -1, false, false, true);
                    }
                    FeedMedia media = DBReader.getFeedMedia(mediaId);
                    ChapterUtils.loadChapters(media, this, false);
                    return media;
                })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(media -> {
                            if (player == null || confirmStreamingIfNeeded(media)) {
                                return;
                            }
                            media.setPosition((int) player.getCurrentPosition());
                            if (media.getItem() != null && !media.getItem().isTagged(FeedItem.TAG_QUEUE)) {
                                DBWriter.addQueueItem(this, media.getItem());
                            }
                            switchToPlayable(media);
                        },
                                error -> Log.e(TAG, "Failed to load current media", error));

            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid media ID: " + (player != null && player.getCurrentMediaItem() != null
                    ? player.getCurrentMediaItem().mediaId
                    : "null"), e);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private boolean confirmStreamingIfNeeded(FeedMedia media) {
        if (needsStreaming(media) && !NetworkUtils.isStreamingAllowed()
                && !allowStreamingThisTime && !isCasting()) {
            showStreamingConfirmation(media);
            return true;
        }
        allowStreamingThisTime = false;
        return false;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener transcriptPrefListener =
            (sharedPreferences, key) -> {
                if (UserPreferences.PREF_FLOATING_TRANSCRIPT.equals(key)) {
                    boolean enabled = sharedPreferences.getBoolean(key, false);
                    if (enabled && player != null && player.isPlaying() && currentPlayable != null) {
                        startFloatingTranscript(currentPlayable.getId());
                    } else if (!enabled) {
                        stopFloatingTranscript();
                    }
                }
            };

    /** Keeps the shared media id valid when playback is restored without a media transition. */
    private void syncMediaIdFromPlayer() {
        if (player == null || player.getCurrentMediaItem() == null) {
            return;
        }
        try {
            long id = Long.parseLong(player.getCurrentMediaItem().mediaId);
            if (PlaybackServiceInterface.currentMediaId != id) {
                PlaybackServiceInterface.currentMediaId = id;
            }
        } catch (NumberFormatException e) {
            // Placeholder items carry non-numeric ids.
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void switchToPlayable(FeedMedia media) {
        currentPlayable = media;
        adStreamMediaId = media.getId();
        lastPlayerPositionMs = Math.max(media.getPosition(), 0);
        PlaybackServiceInterface.currentMediaId = media.getId();
        PlaybackServiceInterface.currentPositionMs = lastPlayerPositionMs;
        if (media.getId() != adSkipMediaId) {
            // A pending undo belongs to the episode that was skipped, not to the new one.
            lastTranscriptShift = null;
            NotificationUtils.cancelAdSkipUndo(this);
            adSkipMediaId = media.getId();
            adSkipHandler.removeCallbacks(adSkipTick);
            adSkipController = new AdSkipController(media.getId());
            AdScanScheduler.scanMediaNow(getApplicationContext(), media.getId());
        }
        adSkipHandler.postDelayed(adSkipTick, 250);
        playbackStartPosition = Math.max(currentPlayable.getPosition(), 0);
        playedDurationCountedUntil = playbackStartPosition;

        float speed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(currentPlayable);
        player.setPlaybackSpeed(speed);
        boolean enabled = PlaybackSpeedUtils.getCurrentSkipSilencePreference(currentPlayable)
                == FeedPreferences.SkipSilence.AGGRESSIVE;
        PlaybackPreferences.setCurrentlyPlayingTemporarySkipSilence(enabled);
        exoPlayer.setSkipSilenceEnabled(enabled);
        if (currentPlayable.getItem() != null && currentPlayable.getItem().getFeed() != null) {
            volumeAdaptionFactor = currentPlayable.getItem().getFeed()
                    .getPreferences().getVolumeAdaptionSetting().getAdaptionFactor();
            applyVolumeAdaption(1.0f);
        }
        updatePlaybackPreferences();
        // Start the floating transcript when the episode changes
        if (player != null) {
            startFloatingTranscript(media.getId());
        }
        WidgetUpdater.WidgetState widgetState = new WidgetUpdater.WidgetState(currentPlayable,
                PlaybackServiceInterface.isPlaying ? PlayerStatus.PLAYING : PlayerStatus.PAUSED,
                currentPlayable.getPosition(), currentPlayable.getDuration(), speed);
        Schedulers.io().scheduleDirect(() -> WidgetUpdater.updateWidget(this, widgetState));
    }

    private void updatePlaybackPreferences() {
        int statusBefore = PlaybackPreferences.getCurrentPlayerStatus();
        long mediaBefore = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (currentPlayable != null) {
            PlaybackPreferences.writeMediaPlaying(currentPlayable);
        }
        int status = PlaybackServiceInterface.isPlaying ? PlaybackPreferences.PLAYER_STATUS_PLAYING
                : PlaybackPreferences.PLAYER_STATUS_PAUSED;
        PlaybackPreferences.setCurrentPlayerStatus(status);
        if (status != statusBefore || (currentPlayable != null && currentPlayable.getId() != mediaBefore)) {
            EventBus.getDefault().post(new PlayerStatusEvent());
        }
    }

    private void saveCurrentPosition() {
        if (currentPlayable == null || player == null) {
            return;
        }
        try {
            if (player.getCurrentMediaItem() == null
                    || currentPlayable.getId() != Long.parseLong(player.getCurrentMediaItem().mediaId)
                    || !player.getCurrentMediaItem().mediaId.equals(playedMediaId)) {
                return;
            }
        } catch (NumberFormatException e) {
            return;
        }
        if (player.isCurrentMediaItemSeekable() && player.getDuration() > 0) {
            currentPlayable.setDuration((int) player.getDuration());
        }
        long position = player.getCurrentPosition();
        long timestamp = System.currentTimeMillis();
        checkpointPlayedDuration((int) position);
        PlayableUtils.saveCurrentPosition(currentPlayable, (int) position, timestamp);
    }

    private void checkpointPlayedDuration(int position) {
        if (playedDurationCountedUntil < 0) {
            return;
        }
        if (position > playedDurationCountedUntil) {
            currentPlayable.incrementPlayedDuration(position - playedDurationCountedUntil);
        }
        playedDurationCountedUntil = position;
    }

    private void updateDatabaseAfterPlayback(FeedMedia media, int startPosition,
                                             boolean ended, boolean skipped, boolean playingNext) {
        if (media == null) {
            return;
        }

        FeedItem item = media.getItem();
        int smartMarkAsPlayedSecs = UserPreferences.getSmartMarkAsPlayedSecs();
        boolean almostEnded = media.getDuration() > 0
                && media.getPosition() >= media.getDuration() - smartMarkAsPlayedSecs * 1000;

        SynchronizationQueue.getInstance().enqueueEpisodePlayed(media, startPosition, ended || almostEnded);
        if (item != null) {
            if (ended || almostEnded) {
                DBWriter.markItemsPlayed(FeedItem.PLAYED, true, Collections.singletonList(item));
            }
            if (ended || almostEnded || (skipped && !UserPreferences.shouldSkipKeepEpisode())) {
                DBWriter.removeQueueItem(this, ended, item);
                FeedPreferences.AutoDeleteAction action = item.getFeed().getPreferences().getCurrentAutoDelete();
                boolean autoDeleteEnabledGlobally = UserPreferences.isAutoDelete()
                        && (!item.getFeed().isLocalFeed() || UserPreferences.isAutoDeleteLocal());
                boolean shouldAutoDelete = action == FeedPreferences.AutoDeleteAction.ALWAYS
                        || (action == FeedPreferences.AutoDeleteAction.GLOBAL && autoDeleteEnabledGlobally);
                if (shouldAutoDelete && (!item.isTagged(FeedItem.TAG_FAVORITE)
                        || !UserPreferences.shouldFavoriteKeepEpisode())) {
                    DBWriter.deleteFeedMediaOfItem(this, media);
                }
            }
        }
        if (ended || ((skipped || playingNext) && String.valueOf(media.getId()).equals(playedMediaId))) {
            DBWriter.addItemToPlaybackHistory(media);
        }
    }

    private void setNextPlaybackSpeed() {
        if (player == null) {
            return;
        }
        List<Float> selectedSpeeds = UserPreferences.getPlaybackSpeedArray();
        if (selectedSpeeds.size() <= 1) {
            return;
        }

        float currentSpeed = player.getPlaybackParameters().speed;
        int speedPosition = selectedSpeeds.indexOf(currentSpeed);
        float newSpeed;

        if (speedPosition == selectedSpeeds.size() - 1 || speedPosition == -1) {
            newSpeed = selectedSpeeds.get(0);
        } else {
            newSpeed = selectedSpeeds.get(speedPosition + 1);
        }

        player.setPlaybackSpeed(newSpeed);
    }

    @UnstableApi
    private void seekToNextChapter() {
        if (currentPlayable == null || player == null) {
            return;
        }
        List<Chapter> chapters = currentPlayable.getChapters();
        if (chapters == null) {
            if (currentPlayable.getItem() != null) {
                startNextInQueue(currentPlayable, true, false);
            }
            return;
        }

        int nextChapter = Chapter.getAfterPosition(chapters, (int) player.getCurrentPosition()) + 1;

        if (chapters.size() < nextChapter + 1) {
            if (currentPlayable.getItem() != null) {
                startNextInQueue(currentPlayable, true, false);
            }
            return;
        }

        player.seekTo(chapters.get(nextChapter).getStart());
    }

    private boolean shouldBlockForStreamingConfirmation() {
        return currentPlayable != null
                && (player.getPlaybackState() == Player.STATE_READY
                    || player.getPlaybackState() == Player.STATE_BUFFERING)
                && needsStreaming(currentPlayable)
                && !NetworkUtils.isStreamingAllowed()
                && !isCasting();
    }

    private boolean handleStreamingConfirmation() {
        if (pendingStreamMediaId != null && player.getCurrentMediaItem() != null
                && MediaItemAdapter.MEDIA_ID_CONFIRM_STREAMING.equals(player.getCurrentMediaItem().mediaId)) {
            final String mediaId = pendingStreamMediaId;
            pendingStreamMediaId = null;
            allowStreamingThisTime = true;
            MediaItem mediaItem = new MediaItem.Builder()
                    .setMediaId(mediaId)
                    .build();
            PlaybackController.bindToMedia3Service(
                    Media3PlaybackService.this, controller -> {
                        controller.setMediaItem(mediaItem);
                        controller.prepare();
                        controller.play();
                    });
            return true;
        }
        return false;
    }

    @UnstableApi
    private void showStreamingConfirmation(FeedMedia media) {
        pendingStreamMediaId = String.valueOf(media.getId());
        currentPlayable = null;
        MediaItem confirmItem = MediaItemAdapter.buildStreamingConfirmationItem(this,
                R.raw.no_streaming,
                getString(R.string.confirm_mobile_streaming_notification_title),
                getString(R.string.confirm_mobile_streaming_notification_message));
        player.setMediaItem(confirmItem);
        player.setPlayWhenReady(false);
        player.prepare();
        PlaybackServiceInterface.isPlaying = false;
        EventBus.getDefault().post(new StreamingConfirmationEvent());
        EventBus.getDefault().post(new PlayerStatusEvent());
    }

    private static boolean needsStreaming(FeedMedia media) {
        return !media.localFileAvailable() && !URLUtil.isContentUrl(media.getStreamUrl());
    }

    @UnstableApi
    private void handlePlaybackEnded() {
        FeedMedia media = currentPlayable;
        currentPlayable = null; // To avoid position updater saving position after we already reset it
        if (sleepTimer != null && sleepTimer.isActive()) {
            sleepTimer.episodeFinishedPlayback();
            if (!sleepTimer.shouldContinueToNextEpisode()) {
                updateDatabaseAfterPlayback(media, playbackStartPosition, true, false, false);
                player.stop();
                player.clearMediaItems();
                PlaybackPreferences.writeNoMediaPlaying();
                EventBus.getDefault().post(
                        new PlaybackServiceEvent(PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN));
                EventBus.getDefault().post(new PlayerStatusEvent());
                return;
            }
        }
        startNextInQueue(media, false, true);
    }

    private boolean isCasting() {
        return player.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE;
    }

    /**
     * Loads the next item, and starts it if continuous playback is enabled.
     */
    @UnstableApi
    private void startNextInQueue(FeedMedia media, boolean wasSkipped, boolean ended) {
        if (queueLoaderDisposable != null) {
            queueLoaderDisposable.dispose();
        }
        if (media == null) {
            return;
        }
        FeedItem item = media.getItem();
        if (item == null) {
            return;
        }
        int startPosition = playbackStartPosition;
        queueLoaderDisposable = Maybe.fromCallable(() -> {
            FeedItem nextItem = DBReader.getNextInQueue(item);
            boolean hasNext = nextItem != null && nextItem.getMedia() != null;
            updateDatabaseAfterPlayback(media, startPosition, ended, wasSkipped, hasNext);
            if (hasNext) {
                return new Pair<>(nextItem.getMedia(),
                        MediaItemAdapter.fromPlayable(Media3PlaybackService.this, nextItem.getMedia(), false));
            }
            return null;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        pair -> {
                            final FeedMedia nextMedia = pair.first;
                            final MediaItem nextMediaItem = pair.second;
                            if (player == null || confirmStreamingIfNeeded(nextMedia)) {
                                return;
                            }
                            switchToPlayable(nextMedia);
                            player.setPlayWhenReady(UserPreferences.isFollowQueue());
                            player.setMediaItem(nextMediaItem, SkipUtils.skipIntroIfNecessary(this, nextMedia));
                            player.prepare();
                        },
                        error -> Log.e(TAG, "Failed to load next queue item", error),
                        () -> {
                            currentPlayable = null;
                            player.stop();
                            player.clearMediaItems();
                            PlaybackPreferences.writeNoMediaPlaying();
                            EventBus.getDefault().post(new PlayerStatusEvent());
                            EventBus.getDefault().post(
                                    new PlaybackServiceEvent(PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN));
                            if (wasSkipped) {
                                EventBus.getDefault().post(new MessageEvent(getString(R.string.no_following_in_queue)));
                            }
                        });
    }

    @UnstableApi
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSleepTimerUpdated(SleepTimerUpdatedEvent event) {
        if (event.isOver()) {
            Log.d(TAG, "Sleep timer expired, pausing playback");
            if (player != null) {
                applyVolumeAdaption(1.0f);
                player.pause();
            }
            sleepTimer = null;
            sessionCallback.refreshNotification(mediaSession);
        } else if (event.isCancelled()) {
            applyVolumeAdaption(1.0f);
            sessionCallback.refreshNotification(mediaSession);
        } else if (!event.wasJustEnabled()) {
            long millisLeft = event.getMillisTimeLeft();
            if (millisLeft < SleepTimer.NOTIFICATION_THRESHOLD && millisLeft > 0) {
                float volume = (float) millisLeft / SleepTimer.NOTIFICATION_THRESHOLD;
                applyVolumeAdaption(Math.max(0.1f, volume));
            } else {
                applyVolumeAdaption(1.0f);
            }
        }
    }

    @UnstableApi
    private void startSleepTimer(long timeOrEpisodes) {
        if (sleepTimer != null) {
            sleepTimer.stop();
        }
        if (SleepTimerPreferences.getSleepTimerType() == SleepTimerType.EPISODES) {
            sleepTimer = new EpisodeSleepTimer(this);
        } else {
            sleepTimer = new ClockSleepTimer(this);
        }
        sleepTimer.start(timeOrEpisodes);
        sessionCallback.refreshNotification(mediaSession);
    }

    private void disableSleepTimer() {
        if (sleepTimer != null) {
            sleepTimer.stop();
            sleepTimer = null;
        } else if (!BuildConfig.DEBUG) {
            // Unblock user in case we somehow got into an inconsistent state
            EventBus.getDefault().postSticky(SleepTimerUpdatedEvent.cancelled());
        }
        if (player != null) {
            applyVolumeAdaption(1.0f);
        }
    }

    private void extendSleepTimer(long additionalTime) {
        if (sleepTimer != null && sleepTimer.isActive()) {
            long currentLeft = sleepTimer.getTimeLeft().getDisplayValue();
            sleepTimer.updateRemainingTime(currentLeft + additionalTime);
        }
    }

    private void onStreamingChunk(StreamingAdScanner.Chunk chunk) {
        long mediaId = adStreamMediaId;
        if (mediaId < 0) {
            return;
        }
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getItem() == null || media.getItem().getFeed() == null) {
            return;
        }
        long feedId = media.getItem().getFeed().getId();
        AdFeedSettings settings = DBReader.getAdFeedSettings(feedId);
        if (!settings.isEnabled()) {
            return;
        }
        List<AdFingerprintRow> references = DBReader.getAdFingerprints(feedId, settings.getWindowSize());
        if (references.isEmpty()) {
            return;
        }
        String referenceKey = HeadScanEngine.ReferenceSet.keyOf(references);
        HeadScanEngine.ReferenceSet refs = adReferences;
        if (refs == null || !referenceKey.equals(adReferencesKey)) {
            refs = HeadScanEngine.ReferenceSet.load(references);
            adReferences = refs;
            adReferencesKey = referenceKey;
        }
        double durationSec = Math.max(media.getDuration() / 1000.0,
                (chunk.segmentStartMs + chunk.frames * 1000.0 / 62.5) / 1000.0);
        List<AdSegment> found = StreamingAdMatcher.match(mediaId, chunk, durationSec, refs);
        if (found.isEmpty()) {
            return;
        }
        try {
            DBWriter.addAdSegments(mediaId, found).get();
        } catch (Exception e) {
            Log.w(TAG, "Storing streamed ad segments failed", e);
            return;
        }
        Log.d(TAG, "stream chunk " + chunk.segmentStartMs + "ms: " + found.size() + " segment(s)");
        EventBus.getDefault().post(new AdScanEvent(feedId, found.size()));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void adScanFinished(AdScanEvent event) {
        if (adSkipController != null && currentPlayable != null && currentPlayable.getItem() != null
                && currentPlayable.getItem().getFeed() != null
                && currentPlayable.getItem().getFeed().getId() == event.feedId) {
            adSkipController.reload();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void volumeAdaptionChanged(VolumeAdaptionChangedEvent event) {
        if (currentPlayable != null && currentPlayable.getItem() != null
                && currentPlayable.getItem().getFeed() != null
                && currentPlayable.getItem().getFeed().getId() == event.getFeedId()) {
            currentPlayable.getItem().getFeed().getPreferences()
                    .setVolumeAdaptionSetting(event.getVolumeAdaptionSetting());
            volumeAdaptionFactor = event.getVolumeAdaptionSetting().getAdaptionFactor();
            applyVolumeAdaption(1.0f);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void feedItemsUpdated(FeedItemEvent event) {
        if (currentPlayable == null || !currentPlayable.localFileAvailable()) {
            return;
        }
        int index = FeedItemEvent.indexOfItemWithId(event.items, currentPlayable.getItemId());
        if (index >= 0 && event.items.get(index).getMedia() != null
                && !event.items.get(index).getMedia().localFileAvailable()) {
            player.stop();
            player.clearMediaItems();
            currentPlayable = null;
            PlaybackPreferences.writeNoMediaPlaying();
            EventBus.getDefault().post(new PlayerStatusEvent());
        }
    }

    private void initLoudnessEnhancer(int audioSessionId) {
        if (!VolumeAdaptionSetting.isBoostSupported()) {
            return;
        }
        LoudnessEnhancer oldEnhancer = this.loudnessEnhancer;
        try {
            LoudnessEnhancer newEnhancer = new LoudnessEnhancer(audioSessionId);
            if (oldEnhancer != null) {
                newEnhancer.setEnabled(oldEnhancer.getEnabled());
                if (oldEnhancer.getEnabled()) {
                    newEnhancer.setTargetGain((int) oldEnhancer.getTargetGain());
                }
                oldEnhancer.release();
            }
            this.loudnessEnhancer = newEnhancer;
        } catch (Exception e) {
            Log.d(TAG, e.toString());
            this.loudnessEnhancer = null;
        }
    }

    private void applyVolumeAdaption(float baseVolume) {
        float v = baseVolume * volumeAdaptionFactor;
        if (v > 1) {
            player.setVolume(1.0f);
            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.setEnabled(true);
                    loudnessEnhancer.setTargetGain((int) (1000 * (v - 1)));
                }
            } catch (Exception e) {
                Log.d(TAG, e.toString());
            }
        } else {
            player.setVolume(v);
            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.setEnabled(false);
                }
            } catch (Exception e) {
                Log.d(TAG, e.toString());
            }
        }
    }
}
