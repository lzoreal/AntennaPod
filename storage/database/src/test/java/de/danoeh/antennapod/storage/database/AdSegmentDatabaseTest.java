package de.danoeh.antennapod.storage.database;

import android.content.Context;

import de.danoeh.antennapod.model.feed.AdChunkFingerprintRow;
import de.danoeh.antennapod.model.feed.AdFeedSettings;
import de.danoeh.antennapod.model.feed.AdFingerprintRow;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AdSegmentDatabaseTest {
    private long mediaId;
    private long feedId = 1;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        DownloadServiceInterface.setImpl(new DownloadServiceInterfaceStub());
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        Feed feed = new Feed("url", null, "Feed");
        FeedItem item = new FeedItem(0, "Episode", "id", "link", new Date(1000),
                FeedItem.UNPLAYED, feed);
        item.setMedia(new FeedMedia(item, "url", 60_000, "audio/mpeg"));
        feed.setItems(Collections.singletonList(item));
        adapter.setCompleteFeed(feed);
        mediaId = item.getMedia().getId();
        feedId = feed.getId();
        adapter.close();
    }

    @Test
    public void segmentsRoundTripAndDisable() throws Exception {
        AdSegment segment = new AdSegment();
        segment.setMediaId(mediaId);
        segment.setStartMs(1000);
        segment.setEndMs(31_000);
        segment.setKind(AdSegment.KIND_PREROLL);
        segment.setTier(AdSegment.TIER_AUTO);
        segment.setSupport(2);
        segment.setMatchRatio(0.3f);
        DBWriter.replaceAdSegments(mediaId, List.of(segment)).get();

        List<AdSegment> loaded = DBReader.getAdSegments(mediaId);
        assertEquals(1, loaded.size());
        assertEquals(1000, loaded.get(0).getStartMs());
        assertTrue(loaded.get(0).getId() > 0);

        DBWriter.setAdSegmentState(loaded.get(0).getId(), AdSegment.STATE_DISABLED).get();
        assertEquals(AdSegment.STATE_DISABLED, DBReader.getAdSegments(mediaId).get(0).getState());
    }

    @Test
    public void deletingFeedRemovesAdData() throws Exception {
        AdSegment segment = new AdSegment();
        segment.setMediaId(mediaId);
        segment.setStartMs(0);
        segment.setEndMs(20_000);
        DBWriter.replaceAdSegments(mediaId, List.of(segment)).get();

        AdFingerprintRow row = new AdFingerprintRow();
        row.setMediaId(mediaId);
        row.setBlob(new byte[]{1});
        DBWriter.saveAdFingerprint(row).get();

        DBWriter.saveAdChunkFingerprint(chunkRow(0, 18_750, new byte[]{2})).get();

        AdFeedSettings settings = new AdFeedSettings(feedId);
        settings.setEnabled(true);
        DBWriter.saveAdFeedSettings(settings).get();

        DBWriter.deleteFeed(context, feedId).get();

        assertEquals(0, DBReader.getAdSegments(mediaId).size());
        assertEquals(0, DBReader.getAdFingerprints(feedId, 8).size());
        assertEquals(0, DBReader.getAdChunkFingerprints(mediaId).size());
        assertFalse(DBReader.getAdFeedSettings(feedId).isEnabled());
    }

    @Test
    public void unscannedMediaSkipsEpisodesThatAlreadyHaveSegments() throws Exception {
        assertEquals(1, DBReader.getLatestUnscannedMediaForAdScan(feedId, 3).size());

        DBWriter.replaceAdSegments(mediaId, List.of(segment(0, 20_000, 0.1f))).get();

        assertEquals(0, DBReader.getLatestUnscannedMediaForAdScan(feedId, 3).size());
    }

    @Test
    public void enabledAdFeedsListsOnlyOptedInFeeds() throws Exception {
        assertEquals(0, DBReader.getEnabledAdFeeds().size());

        AdFeedSettings settings = new AdFeedSettings(feedId);
        settings.setEnabled(true);
        DBWriter.saveAdFeedSettings(settings).get();
        assertEquals(List.of(feedId), DBReader.getEnabledAdFeeds());

        settings.setEnabled(false);
        DBWriter.saveAdFeedSettings(settings).get();
        assertEquals(0, DBReader.getEnabledAdFeeds().size());
    }

    @Test
    public void chunkFingerprintsRoundTrip() throws Exception {
        DBWriter.saveAdChunkFingerprint(chunkRow(0, 18_750, new byte[]{1, 2, 3})).get();
        DBWriter.saveAdChunkFingerprint(chunkRow(18_750, 18_750, new byte[]{4})).get();

        List<AdChunkFingerprintRow> loaded = DBReader.getAdChunkFingerprints(mediaId);
        assertEquals(2, loaded.size());
        assertEquals(0, loaded.get(0).getStartFrame());
        assertEquals(18_750, loaded.get(1).getStartFrame());
        assertEquals(18_750, loaded.get(0).getFrames());
        assertEquals(4, loaded.get(1).getStride());
        assertEquals(3, loaded.get(0).getBlob().length);
    }

    @Test
    public void overlappingChunkFingerprintReplacesPrevious() throws Exception {
        DBWriter.saveAdChunkFingerprint(chunkRow(0, 18_750, new byte[]{1})).get();
        DBWriter.saveAdChunkFingerprint(chunkRow(1000, 18_750, new byte[]{2})).get();

        List<AdChunkFingerprintRow> loaded = DBReader.getAdChunkFingerprints(mediaId);
        assertEquals(1, loaded.size());
        assertEquals(1000, loaded.get(0).getStartFrame());
    }

    @Test
    public void addAdSegmentsDeduplicatesOverlap() throws Exception {
        DBWriter.addAdSegments(mediaId, List.of(segment(1000, 31_000, 0.06f))).get();
        DBWriter.addAdSegments(mediaId, List.of(segment(1500, 32_000, 0.12f))).get();

        List<AdSegment> loaded = DBReader.getAdSegments(mediaId);
        assertEquals(1, loaded.size());
        assertEquals(0.12f, loaded.get(0).getMatchRatio(), 1e-4);
        assertEquals(32_000, loaded.get(0).getEndMs());
    }

    @Test
    public void addAdSegmentsMergesContainedRange() throws Exception {
        DBWriter.addAdSegments(mediaId, List.of(segment(0, 100_000, 0.12f))).get();
        DBWriter.addAdSegments(mediaId, List.of(segment(0, 41_000, 0.12f))).get();

        List<AdSegment> loaded = DBReader.getAdSegments(mediaId);
        assertEquals(1, loaded.size());
        assertEquals(100_000, loaded.get(0).getEndMs());
    }

    @Test
    public void addAdSegmentsKeepsSeparateRanges() throws Exception {
        DBWriter.addAdSegments(mediaId, List.of(segment(0, 20_000, 0.1f))).get();
        DBWriter.addAdSegments(mediaId, List.of(segment(60_000, 80_000, 0.1f))).get();

        assertEquals(2, DBReader.getAdSegments(mediaId).size());
    }

    private AdChunkFingerprintRow chunkRow(int startFrame, int frames, byte[] blob) {
        AdChunkFingerprintRow row = new AdChunkFingerprintRow();
        row.setMediaId(mediaId);
        row.setStartFrame(startFrame);
        row.setFrames(frames);
        row.setStride(4);
        row.setBlob(blob);
        return row;
    }

    private static AdSegment segment(int startMs, int endMs, float ratio) {
        AdSegment segment = new AdSegment();
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        segment.setKind(AdSegment.KIND_PREROLL);
        segment.setTier(AdSegment.TIER_AUTO);
        segment.setSupport(1);
        segment.setMatchRatio(ratio);
        return segment;
    }

    @Test
    public void fingerprintAndSettingsRoundTrip() throws Exception {
        AdFingerprintRow row = new AdFingerprintRow();
        row.setMediaId(mediaId);
        row.setDurationMs(60_000);
        row.setAlgoVersion(1);
        row.setStride(8);
        row.setFrames(3000);
        row.setAudioSize(1234);
        row.setAudioModified(5678);
        row.setBlob(new byte[]{9, 8, 7});
        DBWriter.saveAdFingerprint(row).get();

        assertEquals(1, DBReader.getAdFingerprints(feedId, 8).size());
        assertEquals(3, DBReader.getAdFingerprints(feedId, 8).get(0).getBlob().length);

        AdFeedSettings settings = new AdFeedSettings(feedId);
        settings.setEnabled(true);
        settings.setAggressive(true);
        DBWriter.saveAdFeedSettings(settings).get();
        assertTrue(DBReader.getAdFeedSettings(feedId).isEnabled());
        assertTrue(DBReader.getAdFeedSettings(feedId).isAggressive());
        assertFalse(DBReader.getAdFeedSettings(2).isEnabled());
        assertFalse(DBReader.getAdFeedSettings(2).isAggressive());
    }
}
