package de.danoeh.antennapod.ad;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.event.AdScanEvent;
import de.danoeh.antennapod.model.feed.AdFeedSettings;
import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class AdScanWorker extends Worker {
    public static final String KEY_FEED_ID = "feedId";
    private static final String TAG = "AdScanWorker";
    private static final int NOTIFICATION_ID = 0xAD00;
    private static final int STRIDE = 4;

    public AdScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static OneTimeWorkRequest createRequest(long feedId) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();
        return new OneTimeWorkRequest.Builder(AdScanWorker.class)
                .setConstraints(constraints)
                .setInputData(new Data.Builder().putLong(KEY_FEED_ID, feedId).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();
    }

    @Override
    @NonNull
    public Result doWork() {
        long feedId = getInputData().getLong(KEY_FEED_ID, -1);
        AdFeedSettings settings = DBReader.getAdFeedSettings(feedId);
        if (feedId < 0 || !settings.isEnabled()) {
            return Result.success();
        }

        List<FeedMedia> media = DBReader.getLatestMediaForAdScan(feedId, settings.getWindowSize());
        seedMissingAudio(media);
        if (countWithAudio(media) < 2) {
            return Result.retry();
        }

        Map<Long, AdFingerprintRow> existing = new HashMap<>();
        for (AdFingerprintRow row : DBReader.getAdFingerprints(feedId, settings.getWindowSize())) {
            existing.put(row.getMediaId(), row);
        }

        List<AdScanEngine.EpisodeData> data = new ArrayList<>();
        List<File> tempFiles = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            FeedMedia m = media.get(i);
            try {
                setForegroundAsync(foregroundInfo(i + 1)).get();
                File audio = resolveAudio(m);
                if (audio == null) {
                    continue;
                }
                if (audio.getAbsolutePath().contains("/ad-analysis/")) {
                    tempFiles.add(audio);
                }
                AdFingerprintRow old = existing.get(m.getId());
                if (old != null && old.getAlgoVersion() == FingerprintCodec.ALGO_VERSION
                        && old.getStride() == STRIDE
                        && old.getAudioSize() == audio.length()
                        && old.getAudioModified() == audio.lastModified()) {
                    FingerprintCodec.Decoded decoded = FingerprintCodec.decode(old.getBlob());
                    data.add(new AdScanEngine.EpisodeData(m.getId(), m.getDuration() / 1000.0, decoded.hashes));
                    continue;
                }
                PcmDecoder.Result decoded = PcmDecoder.decodeToMono(audio);
                float[] pcm = Resampler.to8k(decoded.pcm, decoded.sampleRate);
                Fingerprinter.Hashes hashes = Fingerprinter.fingerprint(pcm);
                byte[] blob = FingerprintCodec.encode(hashes, STRIDE, Fingerprinter.SAMPLE_RATE);
                AdFingerprintRow row = new AdFingerprintRow();
                row.setMediaId(m.getId());
                row.setDurationMs(m.getDuration());
                row.setAlgoVersion(FingerprintCodec.ALGO_VERSION);
                row.setStride(STRIDE);
                row.setFrames(hashes.frames);
                row.setAudioSize(audio.length());
                row.setAudioModified(audio.lastModified());
                row.setBlob(blob);
                DBWriter.saveAdFingerprint(row).get();
                Log.d(TAG, "Fingerprinted media " + m.getId());
                data.add(new AdScanEngine.EpisodeData(m.getId(), m.getDuration() / 1000.0, hashes));
            } catch (Exception e) {
                Log.w(TAG, "Skipping media " + m.getId() + ": " + e);
            }
        }
        for (File file : tempFiles) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        if (data.size() < 2) {
            return Result.retry();
        }

        AdScanEngine.Result result = new AdScanEngine().scan(data);
        int segmentCount = 0;
        for (AdScanEngine.EpisodeData episode : data) {
            List<AdSegment> segments = new ArrayList<>();
            for (AdScanEngine.Segment s : result.segments(episode.mediaId)) {
                AdSegment segment = new AdSegment();
                segment.setMediaId(episode.mediaId);
                segment.setStartMs((int) (s.startSec * 1000));
                segment.setEndMs((int) (s.endSec * 1000));
                segment.setKind(s.kind);
                segment.setTier(s.tier);
                segment.setMatchRatio((float) s.matchRatio);
                segment.setSupport(s.support);
                segments.add(segment);
            }
            segmentCount += segments.size();
            try {
                DBWriter.replaceAdSegments(episode.mediaId, segments).get();
            } catch (Exception e) {
                // Continue with the remaining episodes.
            }
        }
        Log.d(TAG, "scan feed " + feedId + ": " + segmentCount + " segments");
        EventBus.getDefault().post(new AdScanEvent(feedId, segmentCount));
        return Result.success();
    }

    private ForegroundInfo foregroundInfo(int episodeIndex) {
        Notification notification = NotificationUtils.createAdScanNotification(getApplicationContext(), episodeIndex);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    private void seedMissingAudio(List<FeedMedia> media) {
        File cacheRoot = getApplicationContext().getExternalCacheDir();
        if (cacheRoot == null) {
            cacheRoot = getApplicationContext().getCacheDir();
        }
        File dir = new File(cacheRoot, "ad-analysis");
        for (FeedMedia m : media) {
            if (countWithAudio(media) >= 2) {
                return;
            }
            if (resolveAudio(m) != null || m.getDownloadUrl() == null) {
                continue;
            }
            try {
                File file = SeedDownloader.download(m.getDownloadUrl(), dir, fileName(m));
                m.setLocalFileUrl(file.getAbsolutePath());
                Log.d(TAG, "Seeded media " + m.getId());
            } catch (IOException e) {
                Log.w(TAG, "Seed failed for media " + m.getId() + ": " + e);
            }
        }
    }

    private static int countWithAudio(List<FeedMedia> media) {
        int count = 0;
        for (FeedMedia m : media) {
            if (resolveAudio(m) != null) {
                count++;
            }
        }
        return count;
    }

    private static String fileName(FeedMedia media) {
        String url = media.getDownloadUrl();
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        if (name.isEmpty() || !name.contains(".")) {
            name = media.getId() + ".mp3";
        }
        return name;
    }

    private static File resolveAudio(FeedMedia media) {
        if (media.getLocalFileUrl() == null) {
            return null;
        }
        File file = new File(media.getLocalFileUrl());
        return file.exists() ? file : null;
    }
}
