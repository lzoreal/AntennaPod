package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;

import de.danoeh.antennapod.asr.AsrModelLanguage;
import de.danoeh.antennapod.asr.AsrSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;

/** Owns one generated-transcript session for the lifetime of the app process. */
public final class AsrGenerationManager {
    private static final String TAG = "AsrManager";
    private static final long MEDIA_WAIT_MS = 2000;
    private static final long MEDIA_CHECK_INTERVAL_MS = 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static AsrGenerationController controller;
    private static long mediaId = -1;
    private static boolean running;
    private static long lastPosition;

    private static final Runnable mediaCheck = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long currentId = PlaybackServiceInterface.currentMediaId;
            if (currentId > 0 && currentId != mediaId) {
                stop();
                return;
            }
            Log.i(TAG, "tick media=" + mediaId + " playMedia=" + currentId
                    + " pos=" + PlaybackServiceInterface.currentPositionMs
                    + " last=" + lastPosition);
            MAIN.postDelayed(this, MEDIA_CHECK_INTERVAL_MS);
        }
    };

    private AsrGenerationManager() {
    }

    public static synchronized void start(@NonNull Context context, @NonNull FeedMedia media,
                                          @NonNull AsrModelLanguage language) {
        final long targetId = media.getId();
        if (running) {
            if (mediaId == targetId) {
                return;
            }
            stopLocked();
        }
        mediaId = targetId;
        running = true;
        lastPosition = Math.max(0, media.getPosition());
        Log.i(TAG, "start media=" + targetId + " language=" + language
                + " initial=" + lastPosition);
        post(new AsrGenerationEvent(AsrGenerationEvent.Type.STARTED, targetId, 0, null));
        MAIN.removeCallbacks(mediaCheck);
        MAIN.postDelayed(mediaCheck, MEDIA_CHECK_INTERVAL_MS);

        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                long deadline = SystemClock.elapsedRealtime() + MEDIA_WAIT_MS;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (PlaybackServiceInterface.currentMediaId == targetId
                            && PlaybackServiceInterface.currentPositionMs >= 0) {
                        lastPosition = PlaybackServiceInterface.currentPositionMs;
                        break;
                    }
                    Thread.sleep(50);
                }
                synchronized (AsrGenerationManager.class) {
                    if (!running || mediaId != targetId) {
                        return;
                    }
                }
                Log.i(TAG, "controller start media=" + targetId + " pos=" + lastPosition);
                AsrGenerationController newController = new AsrGenerationController(
                        appContext, media, language,
                        () -> {
                            if (PlaybackServiceInterface.currentMediaId == targetId
                                    && PlaybackServiceInterface.currentPositionMs >= 0) {
                                lastPosition = PlaybackServiceInterface.currentPositionMs;
                            }
                            return lastPosition;
                        },
                        new AsrGenerationController.Listener() {
                            @Override
                            public void onSegment(@NonNull AsrSegment segment) {
                                post(new AsrGenerationEvent(AsrGenerationEvent.Type.SEGMENT,
                                        targetId, lastPosition, null));
                            }

                            @Override
                            public void onProgress(long decodedEndMs) {
                                post(new AsrGenerationEvent(AsrGenerationEvent.Type.PROGRESS,
                                        targetId, decodedEndMs, null));
                            }

                            @Override
                            public void onCompleted() {
                                finish(AsrGenerationEvent.Type.COMPLETED, null);
                            }

                            @Override
                            public void onError(@NonNull Throwable error) {
                                finish(AsrGenerationEvent.Type.ERROR, error);
                            }
                        });
                synchronized (AsrGenerationManager.class) {
                    if (!running || mediaId != targetId) {
                        newController.stop();
                        return;
                    }
                    controller = newController;
                }
                newController.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish(AsrGenerationEvent.Type.ERROR, new IOException("interrupted", e));
            } catch (IOException | RuntimeException e) {
                finish(AsrGenerationEvent.Type.ERROR, e);
            }
        }, "AsrStart").start();
    }

    public static synchronized void stop() {
        stopLocked();
    }

    public static synchronized void stopIfRunningFor(long id) {
        if (running && mediaId == id) {
            stopLocked();
        }
    }

    public static synchronized boolean isRunning(long id) {
        return running && mediaId == id;
    }

    private static void stopLocked() {
        if (!running && controller == null) {
            return;
        }
        long stoppedId = mediaId;
        running = false;
        MAIN.removeCallbacks(mediaCheck);
        AsrGenerationController current = controller;
        controller = null;
        Log.i(TAG, "stop media=" + stoppedId);
        if (current != null) {
            current.stop();
        }
        post(new AsrGenerationEvent(AsrGenerationEvent.Type.STOPPED, stoppedId, 0, null));
    }

    private static synchronized void finish(AsrGenerationEvent.Type type,
                                            @Nullable Throwable error) {
        long finishedId = mediaId;
        running = false;
        MAIN.removeCallbacks(mediaCheck);
        controller = null;
        Log.i(TAG, "finish media=" + finishedId + " type=" + type);
        post(new AsrGenerationEvent(type, finishedId, 0, error));
    }

    private static void post(@NonNull AsrGenerationEvent event) {
        MAIN.post(() -> EventBus.getDefault().post(event));
        if (event.error != null) {
            Log.e(TAG, "Generation failed for " + event.mediaId, event.error);
        }
    }
}
