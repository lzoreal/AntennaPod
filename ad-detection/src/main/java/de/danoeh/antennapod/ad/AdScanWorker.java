package de.danoeh.antennapod.ad;

import android.annotation.SuppressLint;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AdScanWorker extends Worker {
    public static final String KEY_FEED_ID = "feedId";
    private static final String TAG = "AdScanWorker";
    private static final int NOTIFICATION_ID = 0xAD00;
    private static final int STRIDE = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_SEED_BYTES = 200L * 1024 * 1024;

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
        if (media.size() < 2) {
            return Result.success();
        }
        publishProgress(0, media.size());
        try {
            setForegroundAsync(preparingInfo(media.size())).get();
        } catch (Exception e) {
            Log.w(TAG, "Preparing foreground notification failed", e);
        }
        seedMissingAudio(media);
        if (countWithAudio(media) < 2) {
            return retryOrGiveUp();
        }

        Map<Long, AdFingerprintRow> existing = new HashMap<>();
        for (AdFingerprintRow row : DBReader.getAdFingerprints(feedId, settings.getWindowSize())) {
            existing.put(row.getMediaId(), row);
        }

        List<AdScanEngine.EpisodeData> data = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            FeedMedia m = media.get(i);
            try {
                publishProgress(i + 1, media.size());
                setForegroundAsync(foregroundInfo(i + 1, media.size())).get();
                File audio = resolveAudio(m);
                if (audio == null) {
                    continue;
                }
                AdFingerprintRow old = existing.get(m.getId());
                if (old != null && old.getAlgoVersion() == FingerprintCodec.ALGO_VERSION
                        && old.getStride() == STRIDE
                        && old.getAudioSize() == audio.length()
                        && old.getAudioModified() == audio.lastModified()
                        && old.getDurationMs() == m.getDuration()) {
                    FingerprintCodec.Decoded decoded = FingerprintCodec.decode(old.getBlob());
                    data.add(new AdScanEngine.EpisodeData(m.getId(), m.getDuration() / 1000.0, decoded.hashes));
                    continue;
                }
                ChunkedFingerprinter fingerprinter = new ChunkedFingerprinter();
                Resampler.Stream[] stream = new Resampler.Stream[1];
                PcmDecoder.decodeStreaming(audio, (mono, samples, rate) -> {
                    if (stream[0] == null) {
                        stream[0] = new Resampler.Stream(rate);
                    }
                    float[] pcm8k = stream[0].process(mono, 0, samples);
                    fingerprinter.add(pcm8k, 0, pcm8k.length);
                });
                if (stream[0] != null) {
                    float[] tail = stream[0].finish();
                    fingerprinter.add(tail, 0, tail.length);
                }
                Fingerprinter.Hashes hashes = fingerprinter.finish();
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
        if (data.size() < 2) {
            return retryOrGiveUp();
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
                segment.setSourceMediaIds(s.sourceMediaIds);
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
        NotificationUtils.showAdScanFinished(getApplicationContext(), data.size(), segmentCount);
        return Result.success(new Data.Builder()
                .putInt(AdScanScheduler.KEY_RESULT_EPISODES, data.size())
                .putInt(AdScanScheduler.KEY_RESULT_SEGMENTS, segmentCount)
                .build());
    }

    private Result retryOrGiveUp() {
        if (getRunAttemptCount() + 1 < MAX_ATTEMPTS) {
            return Result.retry();
        }
        NotificationUtils.showAdScanFailed(getApplicationContext());
        return Result.failure();
    }

    private void publishProgress(int episode, int total) {
        setProgressAsync(new Data.Builder()
                .putInt(AdScanScheduler.KEY_PROGRESS_EPISODE, episode)
                .putInt(AdScanScheduler.KEY_PROGRESS_TOTAL, total)
                .build());
    }

    private ForegroundInfo preparingInfo(int total) {
        return foregroundInfo(NotificationUtils.createAdScanPreparingNotification(
                getApplicationContext(), total));
    }

    private ForegroundInfo foregroundInfo(int episodeIndex, int total) {
        return foregroundInfo(NotificationUtils.createAdScanNotification(
                getApplicationContext(), episodeIndex, total));
    }

    // The dataSync type is declared on SystemForegroundService in app/src/main/AndroidManifest.xml;
    // lint only inspects this library module's manifest and cannot see it.
    @SuppressLint("SpecifyForegroundServiceType")
    private static ForegroundInfo foregroundInfo(Notification notification) {
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
        purgeOldSeeds(dir);
        for (FeedMedia m : media) {
            if (countWithAudio(media) >= 2) {
                return;
            }
            if (resolveAudio(m) != null || m.getDownloadUrl() == null) {
                continue;
            }
            String name = fileName(m.getId(), m.getDownloadUrl());
            File cached = new File(dir, name);
            if (cached.length() > 0) {
                m.setLocalFileUrl(cached.getAbsolutePath());
                Log.d(TAG, "Reused seed " + m.getId());
                continue;
            }
            try {
                File file = SeedDownloader.download(m.getDownloadUrl(), dir, name, MAX_SEED_BYTES);
                m.setLocalFileUrl(file.getAbsolutePath());
                Log.d(TAG, "Seeded media " + m.getId());
            } catch (IOException e) {
                Log.w(TAG, "Seed failed for media " + m.getId() + ": " + e);
            }
        }
    }

    private static void purgeOldSeeds(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
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

    static String fileName(long mediaId, String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        if (name.isEmpty() || !name.contains(".")) {
            name = "audio.mp3";
        }
        return mediaId + "-" + name;
    }

    private static File resolveAudio(FeedMedia media) {
        if (media.getLocalFileUrl() == null) {
            return null;
        }
        File file = new File(media.getLocalFileUrl());
        return file.exists() ? file : null;
    }
}
