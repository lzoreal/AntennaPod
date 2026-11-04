package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules the background finalizer that takes over if the player process goes away. */
public final class AsrFinalizeScheduler {
    private static final long INITIAL_DELAY_MINUTES = 15;

    private AsrFinalizeScheduler() {
    }

    public static void schedule(Context context, long mediaId) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AsrFinalizeWorker.class)
                .setConstraints(constraints)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(new Data.Builder()
                        .putLong(AsrFinalizeWorker.KEY_MEDIA_ID, mediaId)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                workName(mediaId), ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context, long mediaId) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(mediaId));
    }

    private static String workName(long mediaId) {
        return "asr-finalize-" + mediaId;
    }
}
