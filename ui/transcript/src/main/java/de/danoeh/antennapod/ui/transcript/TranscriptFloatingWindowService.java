package de.danoeh.antennapod.ui.transcript;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class TranscriptFloatingWindowService extends Service {
    private static final String TAG = "FloatingTranscript";
    private static final String ACTION_SHOW = "de.danoeh.antennapod.action.SHOW_FLOATING_TRANSCRIPT";
    private static final String EXTRA_MEDIA_ID = "extra_media_id";
    private static final long CONTROLS_HIDE_DELAY_MS = 4000;

    private WindowManager windowManager;
    private View floatingView;
    private View floatingControls;
    private ScrollTextView tvTranscript;
    private ScrollTextView tvTranscriptTranslation;
    private ImageButton btnFloatingLock;
    private Transcript transcript;
    private Disposable loadDisposable;
    private WindowManager.LayoutParams params;
    private long currentMediaId = -1;
    private volatile long currentFeedId = -1;
    private int windowWidth;
    private int screenWidth;
    private int screenHeight;
    private long lastSentenceSegmentStart = -1;
    private int lastSentenceIndex = -1;
    private TranscriptSegment sentenceCacheSegment;
    private String sentenceCacheWords;
    private int sentenceCacheSplit = -1;
    private int[] sentenceCacheStarts;
    private int[] translationCacheStarts;
    private volatile long lastPlaybackPosition = -1;
    private boolean playing;
    private boolean positionLocked;
    private boolean windowHidden = true;
    private boolean controlsWereVisible;
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = () -> setControlsVisible(false);
    private final SharedPreferences.OnSharedPreferenceChangeListener languageModeListener =
            (sharedPreferences, key) -> {
                if (UserPreferences.PREF_TRANSCRIPT_LANGUAGE_MODE.equals(key)
                        || UserPreferences.PREF_TRANSCRIPT_TIME_OFFSETS.equals(key)) {
                    updateTranscript(true);
                }
            };

    public static void start(Context context, long mediaId) {
        if (!UserPreferences.isFloatingTranscriptEnabled()) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, TranscriptFloatingWindowService.class);
        intent.setAction(ACTION_SHOW);
        intent.putExtra(EXTRA_MEDIA_ID, mediaId);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to start floating transcript", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, TranscriptFloatingWindowService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        playing = PlaybackPreferences.getCurrentPlayerStatus()
                == PlaybackPreferences.PLAYER_STATUS_PLAYING;
        positionLocked = UserPreferences.isFloatingTranscriptLocked();
        EventBus.getDefault().register(this);
        UserPreferences.registerFloatingTranscriptListener(languageModeListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        long mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1);
        if (mediaId != -1 && mediaId != currentMediaId) {
            currentMediaId = mediaId;
            loadTranscript(mediaId);
        }
        if (floatingView == null) {
            createFloatingWindow();
        }
        if (transcript == null) {
            hideTranscript();
        }
        updateTranscript(true);
        return START_NOT_STICKY;
    }

    private void createFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_transcript_layout, null);
        floatingControls = floatingView.findViewById(R.id.floating_controls);
        tvTranscript = floatingView.findViewById(R.id.tv_floating_transcript);
        tvTranscriptTranslation = floatingView.findViewById(R.id.tv_floating_transcript_translation);
        btnFloatingLock = floatingView.findViewById(R.id.btn_floating_lock);
        ImageButton btnFloatingClose = floatingView.findViewById(R.id.btn_floating_close);
        btnFloatingLock.setOnClickListener(v -> {
            togglePositionLock();
            showControls();
        });
        btnFloatingClose.setOnClickListener(v -> closeFloatingTranscript());
        setControlsVisible(false);
        updateLockButton();

        final int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        windowWidth = (int) (screenWidth * 0.9);

        params = new WindowManager.LayoutParams(
                windowWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (screenWidth - windowWidth) / 2;
        params.y = getStatusBarHeight();
        int savedX = UserPreferences.getFloatingTranscriptX();
        int savedY = UserPreferences.getFloatingTranscriptY();
        if (savedX >= 0 && savedY >= 0) {
            params.x = Math.max(0, Math.min((int) (savedX * dm.density), screenWidth - windowWidth));
            params.y = Math.max(0, Math.min((int) (savedY * dm.density),
                    screenHeight - getStatusBarHeight()));
        }

        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        controlsWereVisible = areControlsVisible();
                        showControls();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - downX;
                        float deltaY = event.getRawY() - downY;
                        if (!moved
                                && Math.abs(deltaX) < touchSlop
                                && Math.abs(deltaY) < touchSlop) {
                            return true;
                        }
                        moved = true;
                        showControls();
                        if (positionLocked) {
                            return true;
                        }
                        params.x = startX + (int) deltaX;
                        params.y = startY + (int) deltaY;
                        params.x = Math.max(0, Math.min(params.x, screenWidth - windowWidth));
                        params.y = Math.max(0, Math.min(params.y,
                                screenHeight - floatingView.getHeight()));
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            if (!positionLocked) {
                                snapToEdge();
                                saveFloatingPosition();
                            }
                            showControls();
                        } else if (controlsWereVisible) {
                            openTranscriptPage();
                        } else {
                            showControls();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingView, params);
    }

    private void togglePositionLock() {
        positionLocked = !positionLocked;
        UserPreferences.setFloatingTranscriptLocked(positionLocked);
        updateLockButton();
    }

    private void setControlsVisible(boolean visible) {
        if (floatingControls == null) {
            return;
        }
        floatingControls.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        controlsHandler.removeCallbacks(hideControlsRunnable);
    }

    private void showControls() {
        if (floatingControls == null) {
            return;
        }
        floatingControls.setVisibility(View.VISIBLE);
        controlsHandler.removeCallbacks(hideControlsRunnable);
        controlsHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
    }

    private boolean areControlsVisible() {
        return floatingControls != null && floatingControls.getVisibility() == View.VISIBLE;
    }

    private void updateLockButton() {
        if (btnFloatingLock == null) {
            return;
        }
        btnFloatingLock.setImageResource(positionLocked
                ? R.drawable.ic_transcript_lock : R.drawable.ic_transcript_unlock);
        btnFloatingLock.setContentDescription(getString(positionLocked
                ? R.string.transcript_floating_unlock : R.string.transcript_floating_lock));
        btnFloatingLock.setAlpha(positionLocked ? 1.0f : 0.7f);
    }

    private void snapToEdge() {
        if (params == null) {
            return;
        }
        params.x = params.x + windowWidth / 2 < screenWidth / 2
                ? 0 : screenWidth - windowWidth;
        params.y = Math.max(0, Math.min(params.y, screenHeight - floatingView.getHeight()));
        try {
            windowManager.updateViewLayout(floatingView, params);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to snap floating window", e);
        }
    }

    private void saveFloatingPosition() {
        if (params == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        UserPreferences.setFloatingTranscriptPosition(
                (int) (params.x / density), (int) (params.y / density));
    }

    private void clampPosition() {
        if (params == null || floatingView == null) {
            return;
        }
        params.x = Math.max(0, Math.min(params.x, screenWidth - windowWidth));
        params.y = Math.max(0, Math.min(params.y, screenHeight - floatingView.getHeight()));
        try {
            windowManager.updateViewLayout(floatingView, params);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to clamp floating window", e);
        }
    }

    private void closeFloatingTranscript() {
        UserPreferences.setFloatingTranscriptEnabled(false);
        stopSelf();
    }

    private void openTranscriptPage() {
        MainActivityStarter starter = new MainActivityStarter(this)
                .withOpenPlayer()
                .withOpenTranscript();
        starter.getIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        starter.start();
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (floatingView == null || params == null) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        windowWidth = (int) (dm.widthPixels * 0.9f);
        params.width = windowWidth;
        params.x = Math.max(0, Math.min(params.x, screenWidth - windowWidth));
        params.y = Math.max(0, Math.min(params.y, screenHeight - floatingView.getHeight()));
        try {
            windowManager.updateViewLayout(floatingView, params);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to update floating window", e);
        }
    }

    private void loadTranscript(long mediaId) {
        loadTranscript(mediaId, false);
    }

    private void loadTranscript(long mediaId, boolean keepPosition) {
        if (loadDisposable != null && !loadDisposable.isDisposed()) {
            loadDisposable.dispose();
        }
        if (!keepPosition) {
            transcript = null;
            lastPlaybackPosition = -1;
            currentFeedId = -1;
            hideTranscript();
        }
        lastSentenceSegmentStart = -1;
        lastSentenceIndex = -1;
        sentenceCacheSegment = null;
        sentenceCacheWords = null;
        sentenceCacheSplit = -1;
        sentenceCacheStarts = null;
        translationCacheStarts = null;
        loadDisposable = Observable.fromCallable(() -> {
            FeedMedia media = DBReader.getFeedMedia(mediaId);
            if (media == null || media.getItem() == null) {
                return null;
            }
            media.setTranscript(TranscriptUtils.loadTranscript(media, false));
            return media;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(media -> {
                    if (media == null || media.getItem() == null) {
                        if (!keepPosition) {
                            hideTranscript();
                            stopSelf();
                        }
                        return;
                    }
                    currentFeedId = media.getItem().getFeedId();
                    if (lastPlaybackPosition < 0) {
                        lastPlaybackPosition = Math.max(0, media.getPosition());
                    }
                    Transcript loaded = media.getTranscript();
                    if (loaded == null || loaded.getSegmentCount() == 0) {
                        if (!keepPosition || transcript == null) {
                            hideTranscript();
                            stopSelf();
                        }
                        return;
                    }
                    this.transcript = loaded;
                    updateTranscript(true);
                }, e -> {
                    Log.e(TAG, "Unable to load transcript", e);
                    if (!keepPosition) {
                        hideTranscript();
                        stopSelf();
                    }
                });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPositionEvent(PlaybackPositionEvent event) {
        if (event.getPosition() < 0) {
            return;
        }
        lastPlaybackPosition = event.getPosition();
        updateTranscript(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListUpdateEvent(FeedListUpdateEvent event) {
        if (currentMediaId == -1 || currentFeedId == -1 || !event.contains(currentFeedId)) {
            return;
        }
        loadTranscript(currentMediaId, true);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        boolean playingNow = PlaybackPreferences.getCurrentPlayerStatus()
                == PlaybackPreferences.PLAYER_STATUS_PLAYING;
        if (playingNow != playing) {
            playing = playingNow;
            updateTranscript(true);
        }
    }

    private void updateTranscript(boolean forceUpdate) {
        if (lastPlaybackPosition < 0 || transcript == null
                || transcript.getSegmentCount() == 0 || tvTranscript == null) {
            return;
        }

        long offset = UserPreferences.getTranscriptTimeOffset(currentMediaId);
        long position = lastPlaybackPosition - offset;
        long firstStart = transcript.getSegmentAt(0).getStartTime();
        long lastEnd = transcript.getSegmentAt(transcript.getSegmentCount() - 1).getEndTime();
        if (position < firstStart || position > lastEnd) {
            hideTranscript();
            return;
        }
        TranscriptSegment segment = transcript.getSegmentAtTime(position);

        String words = segment.getWords();
        boolean cacheHit = segment == sentenceCacheSegment && words.equals(sentenceCacheWords);
        int split = cacheHit ? sentenceCacheSplit : TranscriptTextUtils.languageSplitIndex(words);
        boolean bilingual = split >= 0;
        String primaryText = bilingual ? words.substring(0, split) : words;
        int primaryLength = primaryText.length();
        if (!cacheHit) {
            sentenceCacheSegment = segment;
            sentenceCacheWords = words;
            sentenceCacheSplit = split;
            sentenceCacheStarts = TranscriptTextUtils.chunkStarts(primaryText, primaryLength);
            if (bilingual) {
                String translation = words.substring(split + 1);
                translationCacheStarts = TranscriptTextUtils.chunkStarts(
                        translation, translation.length());
            } else {
                translationCacheStarts = null;
            }
        }
        int[] sentenceStarts = sentenceCacheStarts;
        if (primaryLength <= 0 || sentenceStarts.length < 2) {
            hideTranscript();
            return;
        }

        int sentenceIndex = TranscriptTextUtils.sentenceIndexAt(sentenceStarts,
                TranscriptTextUtils.progressAt(segment, position), primaryLength);
        boolean isNewSentence = segment.getStartTime() != lastSentenceSegmentStart
                || sentenceIndex != lastSentenceIndex;
        if (!forceUpdate && !isNewSentence) {
            return;
        }

        lastSentenceSegmentStart = segment.getStartTime();
        lastSentenceIndex = sentenceIndex;
        long sentenceEndTime = TranscriptTextUtils.sentenceEndTime(
                segment, sentenceStarts, sentenceIndex);
        long remainingMs = sentenceEndTime + offset - lastPlaybackPosition;

        int languageMode = UserPreferences.getTranscriptLanguageMode();
        boolean showTranslation = bilingual
                && languageMode != UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY;
        boolean showPrimary = languageMode != UserPreferences.TRANSCRIPT_LANGUAGE_MODE_SECONDARY_ONLY
                || !showTranslation;

        setTouchable(true);
        if (floatingView != null && windowHidden) {
            windowHidden = false;
            floatingView.setVisibility(View.VISIBLE);
            setControlsVisible(false);
            floatingView.post(this::clampPosition);
        }
        if (showPrimary) {
            String text = primaryText.substring(sentenceStarts[sentenceIndex],
                    sentenceStarts[sentenceIndex + 1]).trim();
            tvTranscript.setText(text);
            tvTranscript.setVisibility(View.VISIBLE);
            startAutoScrollIfPlaying(tvTranscript, remainingMs);
        } else {
            tvTranscript.stopAutoScroll();
            tvTranscript.setVisibility(View.GONE);
        }

        updateTranslationView(words, split, sentenceIndex, showTranslation, remainingMs,
                sentenceStarts.length == 2, translationCacheStarts);
    }

    private void updateTranslationView(String words, int languageSplit, int sentenceIndex,
                                       boolean showTranslation, long remainingMs,
                                       boolean singlePrimarySentence, int[] translationStarts) {
        if (!showTranslation) {
            tvTranscriptTranslation.stopAutoScroll();
            tvTranscriptTranslation.setVisibility(View.GONE);
            return;
        }
        String translation = words.substring(languageSplit + 1);
        if (singlePrimarySentence) {
            String text = translation.trim();
            if (text.isEmpty()) {
                tvTranscriptTranslation.stopAutoScroll();
                tvTranscriptTranslation.setVisibility(View.GONE);
                return;
            }
            tvTranscriptTranslation.setText(text);
            tvTranscriptTranslation.setVisibility(View.VISIBLE);
            startAutoScrollIfPlaying(tvTranscriptTranslation, remainingMs);
            return;
        }
        if (translationStarts == null || translationStarts.length < 2) {
            tvTranscriptTranslation.stopAutoScroll();
            tvTranscriptTranslation.setVisibility(View.GONE);
            return;
        }
        int translationIndex = Math.min(sentenceIndex, translationStarts.length - 2);
        tvTranscriptTranslation.setText(translation.substring(translationStarts[translationIndex],
                translationStarts[translationIndex + 1]).trim());
        tvTranscriptTranslation.setVisibility(View.VISIBLE);
        startAutoScrollIfPlaying(tvTranscriptTranslation, remainingMs);
    }

    private void startAutoScrollIfPlaying(ScrollTextView view, long remainingMs) {
        if (playing) {
            view.startAutoScroll(remainingMs, windowWidth);
        } else {
            view.stopAutoScroll();
        }
    }

    private void hideTranscript() {
        lastSentenceSegmentStart = -1;
        lastSentenceIndex = -1;
        if (tvTranscript != null) {
            tvTranscript.stopAutoScroll();
            tvTranscript.setVisibility(View.GONE);
        }
        if (tvTranscriptTranslation != null) {
            tvTranscriptTranslation.stopAutoScroll();
            tvTranscriptTranslation.setVisibility(View.GONE);
        }
        if (floatingView != null) {
            floatingView.setVisibility(View.GONE);
        }
        windowHidden = true;
        setControlsVisible(false);
        setTouchable(false);
    }

    private void setTouchable(boolean touchable) {
        if (floatingView == null || params == null) {
            return;
        }
        boolean currentlyTouchable =
                (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (currentlyTouchable == touchable) {
            return;
        }
        if (touchable) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            windowManager.updateViewLayout(floatingView, params);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to update floating window", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        controlsHandler.removeCallbacks(hideControlsRunnable);
        EventBus.getDefault().unregister(this);
        UserPreferences.unregisterFloatingTranscriptListener(languageModeListener);
        if (loadDisposable != null && !loadDisposable.isDisposed()) {
            loadDisposable.dispose();
        }
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Floating view was already removed", e);
            }
            floatingView = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
