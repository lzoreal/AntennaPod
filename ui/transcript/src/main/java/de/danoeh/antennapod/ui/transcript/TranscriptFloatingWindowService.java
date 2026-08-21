package de.danoeh.antennapod.ui.transcript;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
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
    private static final String ACTION_SHOW = "de.danoeh.antennapod.action.SHOW_FLOATING_TRANSCRIPT";
    private static final String ACTION_HIDE = "de.danoeh.antennapod.action.HIDE_FLOATING_TRANSCRIPT";
    private static final String EXTRA_MEDIA_ID = "extra_media_id";

    private WindowManager windowManager;
    private View floatingView;
    private ScrollTextView  tvTranscript;
    private Transcript transcript;
    private Disposable loadDisposable;
    private WindowManager.LayoutParams params;
    private long currentMediaId = -1;
    private int windowWidth;

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
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, TranscriptFloatingWindowService.class);
        intent.setAction(ACTION_HIDE);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        EventBus.getDefault().register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.util.Log.d("FloatingTranscript", ">>> onStartCommand intent=" + (intent != null));
        if (intent == null) {
            return START_NOT_STICKY;
        }
        android.util.Log.d("FloatingTranscript", "action=" + intent.getAction());
        if (ACTION_HIDE.equals(intent.getAction())) {
            android.util.Log.d("FloatingTranscript", "stopping self");
            stopSelf();
            return START_NOT_STICKY;
        }

        long mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1);
        android.util.Log.d("FloatingTranscript", "mediaId=" + mediaId + ", current=" + currentMediaId);
        if (mediaId != -1 && mediaId != currentMediaId) {
            currentMediaId = mediaId;
            loadTranscript(mediaId);
        }
        if (floatingView == null) {
            createFloatingWindow();
        } else {
            android.util.Log.d("FloatingTranscript", "floatingView already exists");
        }
        if (floatingView != null) {
            tvTranscript.setVisibility(View.VISIBLE);
        }
        return START_NOT_STICKY;
    }

    private void createFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_transcript_layout, null);
        tvTranscript = floatingView.findViewById(R.id.tv_floating_transcript);

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

        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - downX);
                        params.y = startY + (int) (event.getRawY() - downY);
                        params.x = Math.max(0, Math.min(params.x, screenWidth - windowWidth));
                        params.y = Math.max(0, Math.min(params.y, screenHeight - floatingView.getHeight()));
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingView, params);
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private void openPlayer() {
        Intent intent = new MainActivityStarter(this)
                .withOpenPlayer()
                .getIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadTranscript(long mediaId) {
        android.util.Log.d("FloatingTranscript", ">>> loadTranscript: " + mediaId);
        if (loadDisposable != null && !loadDisposable.isDisposed()) {
            loadDisposable.dispose();
        }
        transcript = null;
        loadDisposable = Observable.fromCallable(() -> {
                    FeedMedia media = DBReader.getFeedMedia(mediaId);
                    android.util.Log.d("FloatingTranscript", "DBReader media=" + (media != null));
                    if (media == null) {
                        return null;
                    }
                    Transcript t = TranscriptUtils.loadTranscript(media, false);
                    android.util.Log.d("FloatingTranscript", "TranscriptUtils result=" + (t != null));
                    return t;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(t -> {
                    this.transcript = t;
                    android.util.Log.d("FloatingTranscript", "transcript assigned, segments="
                            + (t != null ? t.getSegmentCount() : "null"));
                    if (t != null && !(t.getSegmentCount() == 0)) {
                        TranscriptSegment first = t.getSegmentAt(0);
                        android.util.Log.d("FloatingTranscript", "firstSeg: start=" + first.getStartTime()
                                + ", end=" + first.getEndTime() + ", text=" + first.getWords());
                    }
                }, e -> {
                    android.util.Log.e("FloatingTranscript", "load error", e);
                    e.printStackTrace();
                });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPositionEvent(PlaybackPositionEvent event) {
        if (transcript == null || tvTranscript == null) return;

        TranscriptSegment segment = transcript.getSegmentAtTime(event.getPosition());
        if (segment != null) {
            String text = segment.getWords();
            if (text.contains("\n")) {
                text = text.substring(0, text.indexOf('\n'));
            }
            if (segment.getSpeaker() != null && !segment.getSpeaker().isEmpty()) {
                text = segment.getSpeaker() + ": " + text;
            }

            CharSequence current = tvTranscript.getText();
            boolean isNewSegment = current == null || !text.contentEquals(current);

            if (isNewSegment) {
                tvTranscript.setText(text);
                long remainingMs = segment.getEndTime() - event.getPosition();
                tvTranscript.startAutoScroll(remainingMs, windowWidth);
            }

            tvTranscript.setVisibility(View.VISIBLE);
        } else {
            tvTranscript.stopAutoScroll();
            tvTranscript.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (loadDisposable != null && !loadDisposable.isDisposed()) {
            loadDisposable.dispose();
        }
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
