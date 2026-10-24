package de.danoeh.antennapod.ui.transcript;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.storage.database.DBReader;
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

    private WindowManager windowManager;
    private View floatingView;
    private ScrollTextView tvTranscript;
    private ScrollTextView tvTranscriptTranslation;
    private Transcript transcript;
    private Disposable loadDisposable;
    private WindowManager.LayoutParams params;
    private long currentMediaId = -1;
    private int windowWidth;
    private long lastSentenceSegmentStart = -1;
    private int lastSentenceIndex = -1;
    private long lastPlaybackPosition = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener languageModeListener =
            (sharedPreferences, key) -> {
                if (UserPreferences.PREF_TRANSCRIPT_LANGUAGE_MODE.equals(key)) {
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
        updateTranscript(true);
        return START_NOT_STICKY;
    }

    private void createFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_transcript_layout, null);
        tvTranscript = floatingView.findViewById(R.id.tv_floating_transcript);
        tvTranscriptTranslation = floatingView.findViewById(R.id.tv_floating_transcript_translation);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
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
            params.x = Math.max(0, Math.min(savedX, screenWidth - windowWidth));
            params.y = Math.max(0, Math.min(savedY, screenHeight - getStatusBarHeight()));
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
                        params.x = startX + (int) deltaX;
                        params.y = startY + (int) deltaY;
                        params.x = Math.max(0, Math.min(params.x, screenWidth - windowWidth));
                        params.y = Math.max(0, Math.min(params.y, screenHeight - floatingView.getHeight()));
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            UserPreferences.setFloatingTranscriptPosition(params.x, params.y);
                        } else {
                            openTranscriptPage();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingView, params);
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

    private void loadTranscript(long mediaId) {
        if (loadDisposable != null && !loadDisposable.isDisposed()) {
            loadDisposable.dispose();
        }
        transcript = null;
        lastPlaybackPosition = -1;
        lastSentenceSegmentStart = -1;
        lastSentenceIndex = -1;
        loadDisposable = Observable.fromCallable(() -> {
                    FeedMedia media = DBReader.getFeedMedia(mediaId);
                    if (media == null) {
                        return null;
                    }
                    return TranscriptUtils.loadTranscript(media, false);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(t -> this.transcript = t,
                        e -> android.util.Log.e("FloatingTranscript", "load error", e));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPositionEvent(PlaybackPositionEvent event) {
        lastPlaybackPosition = event.getPosition();
        updateTranscript(false);
    }

    private void updateTranscript(boolean forceUpdate) {
        if (lastPlaybackPosition < 0 || transcript == null
                || transcript.getSegmentCount() == 0 || tvTranscript == null) {
            return;
        }

        long offset = UserPreferences.getTranscriptTimeOffset(currentMediaId);
        long position = lastPlaybackPosition - offset;
        TranscriptSegment segment = transcript.getSegmentAtTime(position);
        if (segment == null) {
            hideTranscript();
            return;
        }

        String words = segment.getWords();
        int split = segment.getTranslationLine() >= 0
                ? TranscriptTextUtils.languageSplitIndex(words) : -1;
        boolean bilingual = split >= 0;
        String primaryText = bilingual ? words.substring(0, split) : words;
        int primaryLength = primaryText.length();
        int[] sentenceStarts = TranscriptTextUtils.chunkStarts(primaryText, primaryLength);
        if (primaryLength <= 0 || sentenceStarts.length < 2) {
            hideTranscript();
            return;
        }

        int sentenceIndex = TranscriptTextUtils.sentenceIndexAt(primaryText,
                TranscriptTextUtils.progressAt(segment, position), primaryLength);
        boolean isNewSentence = segment.getStartTime() != lastSentenceSegmentStart
                || sentenceIndex != lastSentenceIndex;
        if (!forceUpdate && !isNewSentence) {
            return;
        }

        lastSentenceSegmentStart = segment.getStartTime();
        lastSentenceIndex = sentenceIndex;
        long sentenceEndTime = sentenceIndex + 2 < sentenceStarts.length
                ? TranscriptTextUtils.sentenceStartTime(segment, primaryText,
                        sentenceIndex + 1, primaryLength)
                : segment.getEndTime();
        long remainingMs = sentenceEndTime + offset - lastPlaybackPosition;

        int languageMode = UserPreferences.getTranscriptLanguageMode();
        boolean showTranslation = bilingual
                && languageMode != UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY;
        boolean showPrimary = languageMode != UserPreferences.TRANSCRIPT_LANGUAGE_MODE_SECONDARY_ONLY
                || !showTranslation;

        if (showPrimary) {
            String text = primaryText.substring(sentenceStarts[sentenceIndex],
                    sentenceStarts[sentenceIndex + 1]).trim();
            if (segment.getSpeaker() != null && !segment.getSpeaker().isEmpty()) {
                text = segment.getSpeaker() + ": " + text;
            }
            tvTranscript.setText(text);
            tvTranscript.setVisibility(View.VISIBLE);
            tvTranscript.startAutoScroll(remainingMs, windowWidth);
        } else {
            tvTranscript.stopAutoScroll();
            tvTranscript.setVisibility(View.GONE);
        }

        updateTranslationView(words, split, sentenceIndex, showTranslation, remainingMs);
    }

    private void updateTranslationView(String words, int languageSplit, int sentenceIndex,
                                       boolean showTranslation, long remainingMs) {
        if (!showTranslation) {
            tvTranscriptTranslation.stopAutoScroll();
            tvTranscriptTranslation.setVisibility(View.GONE);
            return;
        }
        String translation = words.substring(languageSplit + 1);
        int[] translationStarts = TranscriptTextUtils.chunkStarts(translation, translation.length());
        if (translationStarts.length < 2) {
            tvTranscriptTranslation.stopAutoScroll();
            tvTranscriptTranslation.setVisibility(View.GONE);
            return;
        }
        int translationIndex = Math.min(sentenceIndex, translationStarts.length - 2);
        tvTranscriptTranslation.setText(translation.substring(translationStarts[translationIndex],
                translationStarts[translationIndex + 1]).trim());
        tvTranscriptTranslation.setVisibility(View.VISIBLE);
        tvTranscriptTranslation.startAutoScroll(remainingMs, windowWidth);
    }

    private void hideTranscript() {
        lastSentenceSegmentStart = -1;
        lastSentenceIndex = -1;
        tvTranscript.stopAutoScroll();
        tvTranscript.setVisibility(View.GONE);
        tvTranscriptTranslation.stopAutoScroll();
        tvTranscriptTranslation.setVisibility(View.GONE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
