package de.danoeh.antennapod.ad;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.event.AdScanEvent;
import de.danoeh.antennapod.model.feed.AdFeedSettings;
import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Scans only the first minutes of the episode that is about to play. */
public class HeadScanWorker extends Worker {
    public static final String KEY_MEDIA_ID = "mediaId";
    public static final String KEY_PREFETCH = "prefetch";
    private static final String TAG = "HeadScanWorker";
    private static final int FIRST_SECONDS = 20;
    private static final int HEAD_SECONDS = 120;

    public HeadScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static OneTimeWorkRequest createRequest(long mediaId) {
        return createRequest(mediaId, true);
    }

    /** Head scan for an upcoming episode; does not prefetch beyond it. */
    public static OneTimeWorkRequest createPrefetchRequest(long mediaId) {
        return createRequest(mediaId, false);
    }

    private static OneTimeWorkRequest createRequest(long mediaId, boolean prefetch) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();
        return new OneTimeWorkRequest.Builder(HeadScanWorker.class)
                .setConstraints(constraints)
                .setInputData(new Data.Builder()
                        .putLong(KEY_MEDIA_ID, mediaId)
                        .putBoolean(KEY_PREFETCH, prefetch)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();
    }

    @Override
    @NonNull
    public Result doWork() {
        long mediaId = getInputData().getLong(KEY_MEDIA_ID, -1);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getItem() == null || media.getItem().getFeed() == null) {
            return Result.success();
        }
        Feed feed = media.getItem().getFeed();
        AdFeedSettings settings = DBReader.getAdFeedSettings(feed.getId());
        if (!settings.isEnabled() || media.getLocalFileUrl() != null || media.getDownloadUrl() == null) {
            return Result.success();
        }
        boolean prefetch = getInputData().getBoolean(KEY_PREFETCH, false);
        if (!DBReader.getAdSegments(mediaId).isEmpty()) {
            if (prefetch) {
                AdScanScheduler.prefetchUpcoming(getApplicationContext(), media);
            }
            return Result.success();
        }
        List<AdFingerprintRow> references =
                DBReader.getAdFingerprints(feed.getId(), settings.getWindowSize());
        if (references.isEmpty()) {
            return Result.success();
        }
        // Short prefix first so the first skip does not wait for the full head scan.
        if (!scanAndStore(media, references, FIRST_SECONDS)) {
            return Result.retry();
        }
        if (media.getDuration() > FIRST_SECONDS * 1000L) {
            scanAndStore(media, references, HEAD_SECONDS);
        }
        if (prefetch) {
            AdScanScheduler.prefetchUpcoming(getApplicationContext(), media);
        }
        return Result.success();
    }

    /**
     * Downloads and scans the first {@code seconds} of the episode; false if that failed.
     */
    private boolean scanAndStore(FeedMedia media, List<AdFingerprintRow> references, int seconds) {
        long started = SystemClock.elapsedRealtime();
        File head;
        try {
            head = SeedDownloader.downloadHead(media.getDownloadUrl(), cacheDir(),
                    AdScanWorker.fileName(media.getId(), media.getDownloadUrl()) + "." + seconds + ".head",
                    SeedDownloader.headBytes(media.getSize(), media.getDuration(), seconds));
        } catch (IOException e) {
            Log.w(TAG, "Head download failed for " + media.getId() + ": " + e);
            return false;
        }
        try {
            long downloaded = SystemClock.elapsedRealtime();
            double durationSec = media.getDuration() > 0
                    ? media.getDuration() / 1000.0 : HEAD_SECONDS * 2.0;
            List<AdScanEngine.Segment> found =
                    HeadScanEngine.scan(fingerprint(head), durationSec, references);
            long matched = SystemClock.elapsedRealtime();
            List<AdSegment> segments = new ArrayList<>();
            for (AdScanEngine.Segment s : found) {
                AdSegment segment = new AdSegment();
                segment.setMediaId(media.getId());
                segment.setStartMs((int) (s.startSec * 1000));
                segment.setEndMs((int) (s.endSec * 1000));
                segment.setKind(s.kind);
                segment.setTier(s.tier);
                segment.setMatchRatio((float) s.matchRatio);
                segment.setSupport(s.support);
                segments.add(segment);
            }
            DBWriter.replaceAdSegments(media.getId(), segments).get();
            Log.d(TAG, "scan media " + media.getId() + " " + seconds + "s: " + segments.size()
                    + " segment(s) in " + (SystemClock.elapsedRealtime() - started) + "ms"
                    + " (download " + (downloaded - started) + "ms, fingerprint "
                    + (matched - downloaded) + "ms, match " + (SystemClock.elapsedRealtime() - matched)
                    + "ms)");
            EventBus.getDefault().post(new AdScanEvent(media.getItem().getFeed().getId(), segments.size()));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Head scan failed for " + media.getId() + ": " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            head.delete();
        }
    }

    private static Fingerprinter.Hashes fingerprint(File file) {
        ChunkedFingerprinter fingerprinter = new ChunkedFingerprinter();
        Resampler.Stream[] stream = new Resampler.Stream[1];
        try {
            PcmDecoder.decodeStreaming(file, (mono, samples, rate) -> {
                if (stream[0] == null) {
                    stream[0] = new Resampler.Stream(rate);
                }
                float[] pcm8k = stream[0].process(mono, 0, samples);
                fingerprinter.add(pcm8k, 0, pcm8k.length);
            });
        } catch (Exception e) {
            // A truncated head usually ends in a decoder error; keep what was decoded.
            Log.w(TAG, "Head decode stopped: " + e);
        }
        if (stream[0] != null) {
            float[] tail = stream[0].finish();
            fingerprinter.add(tail, 0, tail.length);
        }
        return fingerprinter.finish();
    }

    private File cacheDir() {
        File root = getApplicationContext().getExternalCacheDir();
        if (root == null) {
            root = getApplicationContext().getCacheDir();
        }
        return new File(root, "ad-analysis");
    }
}
