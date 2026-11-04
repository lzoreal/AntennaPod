package de.danoeh.antennapod.ui.screen.playback;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.danoeh.antennapod.asr.AsrSegment;
import de.danoeh.antennapod.asr.AsrTranscriptStore;
import de.danoeh.antennapod.asr.GeneratedTranscriptCheckpoint;
import de.danoeh.antennapod.asr.MediaRange;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;

/**
 * Finishes a generated transcript in the background after the player process went away.
 * Resumes from the persisted coverage checkpoint and decodes the remaining gaps as fast as it can.
 */
public class AsrFinalizeWorker extends Worker {
    public static final String KEY_MEDIA_ID = "mediaId";
    private static final String TAG = "AsrFinalizeWorker";
    private static final int NOTIFICATION_ID = 0xA5F0;
    private static final long MAX_RUN_MS = TimeUnit.MINUTES.toMillis(9);
    private static final long COMPLETE_MARGIN_MS = 5000;

    public AsrFinalizeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        long mediaId = getInputData().getLong(KEY_MEDIA_ID, -1);
        if (mediaId <= 0 || getRunAttemptCount() > 5) {
            return Result.success();
        }
        if (AsrGenerationManager.isRunning(mediaId)) {
            // A live session still owns this episode; try again later.
            return Result.retry();
        }
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getLocalFileUrl() == null
                || media.getGeneratedTranscriptFileUrl() == null
                || media.getItem() != null && media.getItem().getTranscriptUrl() != null) {
            // Streaming sources need the player cache; finalization only covers local files.
            return Result.success();
        }
        if (isComplete(media)) {
            return Result.success();
        }
        try {
            setForegroundAsync(foregroundInfo()).get();
        } catch (Exception e) {
            Log.w(TAG, "Foreground notification failed", e);
        }

        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean failed = new AtomicBoolean(false);
        AsrGenerationController controller = new AsrGenerationController(
                getApplicationContext(), media, AsrGenerationController.languageFor(media),
                () -> 0L, new AsrGenerationController.Listener() {
                    @Override
                    public void onSegment(@NonNull AsrSegment segment) {
                    }

                    @Override
                    public void onProgress(long decodedEndMs) {
                    }

                    @Override
                    public void onCompleted() {
                        finished.countDown();
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        failed.set(true);
                        finished.countDown();
                    }
                }, true);
        try {
            controller.start();
        } catch (IOException e) {
            Log.w(TAG, "Cannot start finalize session", e);
            return Result.retry();
        }
        try {
            if (!finished.await(MAX_RUN_MS, TimeUnit.MILLISECONDS)) {
                controller.stop();
                return Result.retry();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            controller.stop();
            return Result.retry();
        }
        return failed.get() ? Result.retry() : Result.success();
    }

    private static boolean isComplete(FeedMedia media) {
        AsrTranscriptStore store =
                new AsrTranscriptStore(new File(media.getGeneratedTranscriptFileUrl()));
        if (!store.hasTranscript()) {
            return false;
        }
        try {
            GeneratedTranscriptCheckpoint checkpoint = store.loadCheckpoint();
            List<MediaRange> ranges = checkpoint.getCoverage();
            if (ranges.isEmpty()) {
                return false;
            }
            long end = ranges.get(ranges.size() - 1).endMs();
            return media.getDuration() > 0
                    && end >= media.getDuration() - COMPLETE_MARGIN_MS;
        } catch (IOException e) {
            return false;
        }
    }

    private ForegroundInfo foregroundInfo() {
        Notification notification = NotificationUtils.createAsrFinalizeNotification(
                getApplicationContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }
}
