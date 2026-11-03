package de.danoeh.antennapod.ad;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.work.ExistingWorkPolicy;
import androidx.work.WorkManager;

import de.danoeh.antennapod.model.feed.AdFeedSettings;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;

public final class AdScanScheduler {
    public static final String KEY_PROGRESS_EPISODE = "progressEpisode";
    public static final String KEY_PROGRESS_TOTAL = "progressTotal";
    public static final String KEY_RESULT_EPISODES = "resultEpisodes";
    public static final String KEY_RESULT_SEGMENTS = "resultSegments";
    private static final String UNIQUE_PREFIX = "ad-scan-";
    private static final String HEAD_PREFIX = "ad-head-";
    private static final int STARTUP_PREFETCH_LIMIT = 4;
    private static final int UPCOMING_PREFETCH_LIMIT = 2;

    private AdScanScheduler() {
    }

    /** Live progress of the initial feed scan, for the feed settings screen. */
    public static LiveData<AdScanStatus> status(Context context, long feedId) {
        return Transformations.map(
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(UNIQUE_PREFIX + feedId),
                AdScanStatus::from);
    }

    public static void enable(Context context, long feedId) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + feedId, ExistingWorkPolicy.REPLACE, AdScanWorker.createRequest(feedId));
    }

    /** Fast head-only scan for an episode that has no segments yet. */
    public static void scanMediaNow(Context context, long mediaId) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                HEAD_PREFIX + mediaId, ExistingWorkPolicy.KEEP, HeadScanWorker.createRequest(mediaId));
    }

    /** Warm-up scan for an upcoming episode; never restarts an already pending scan. */
    public static void prefetchMediaNow(Context context, long mediaId) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                HEAD_PREFIX + mediaId, ExistingWorkPolicy.KEEP, HeadScanWorker.createPrefetchRequest(mediaId));
    }

    /** Warms heads of episodes that are likely played next. Call from a background thread. */
    public static void prefetchUpcoming(Context context, FeedMedia current) {
        FeedItem item = current.getItem();
        if (item == null || item.getFeed() == null) {
            return;
        }
        int requested = 0;
        FeedItem next = DBReader.getNextInQueue(item);
        if (next != null && next.getMedia() != null
                && prefetch(context, next.getMedia(), current.getId())) {
            requested++;
        }
        prefetchNewest(context, item.getFeed().getId(), current.getId(), requested,
                UPCOMING_PREFETCH_LIMIT);
    }

    /** Warms the newest episodes of every opted-in feed when the app starts. */
    public static void prefetchEnabledFeeds(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            int requested = 0;
            for (long feedId : DBReader.getEnabledAdFeeds()) {
                requested = prefetchNewest(app, feedId, -1, requested, STARTUP_PREFETCH_LIMIT);
                if (requested >= STARTUP_PREFETCH_LIMIT) {
                    break;
                }
            }
        }, "ad-feed-prefetch").start();
    }

    private static int prefetchNewest(Context context, long feedId, long excludeMediaId,
                                      int requested, int limit) {
        if (requested >= limit) {
            return requested;
        }
        AdFeedSettings settings = DBReader.getAdFeedSettings(feedId);
        if (!settings.isEnabled()
                || DBReader.getAdFingerprints(feedId, settings.getWindowSize()).isEmpty()) {
            return requested;
        }
        for (FeedMedia candidate : DBReader.getLatestUnscannedMediaForAdScan(feedId, 3)) {
            if (requested >= limit) {
                break;
            }
            if (prefetch(context, candidate, excludeMediaId)) {
                requested++;
            }
        }
        return requested;
    }

    private static boolean prefetch(Context context, FeedMedia candidate, long excludeMediaId) {
        if (candidate.getId() == excludeMediaId || candidate.getLocalFileUrl() != null
                || candidate.getDownloadUrl() == null
                || !DBReader.getAdSegments(candidate.getId()).isEmpty()) {
            return false;
        }
        prefetchMediaNow(context, candidate.getId());
        return true;
    }

    public static void scanNow(Context context, long feedId) {
        enable(context, feedId);
    }

    public static void disable(Context context, long feedId) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + feedId);
    }
}
