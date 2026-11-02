package de.danoeh.antennapod.ad;

import android.content.Context;

import androidx.work.ExistingWorkPolicy;
import androidx.work.WorkManager;

public final class AdScanScheduler {
    private static final String UNIQUE_PREFIX = "ad-scan-";

    private AdScanScheduler() {
    }

    public static void enable(Context context, long feedId) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + feedId, ExistingWorkPolicy.REPLACE, AdScanWorker.createRequest(feedId));
    }

    public static void scanNow(Context context, long feedId) {
        enable(context, feedId);
    }

    public static void disable(Context context, long feedId) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + feedId);
    }
}
