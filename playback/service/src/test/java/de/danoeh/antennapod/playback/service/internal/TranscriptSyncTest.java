package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TranscriptSyncTest {
    private static final long MEDIA_ID = 42;

    @Before
    public void setUp() {
        UserPreferences.init(ApplicationProvider.getApplicationContext());
        UserPreferences.getPreferences().edit().clear().commit();
    }

    @Test
    public void autoSkipMovesTheOffsetPastTheAd() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 90_000);

        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000);

        assertNotNull(shift);
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 600_000));
        assertEquals(150_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 660_000));
    }

    @Test
    public void undoRestoresTheOffsetPlayingAfterTheAd() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 90_000);
        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000);

        TranscriptSync.undo(shift);

        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 660_000));
    }

    @Test
    public void undoRemovesOffsetsThatTheSkipCreated() {
        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 0, 30_000), 0);
        assertTrue(UserPreferences.hasTranscriptTimeOffset(MEDIA_ID));

        TranscriptSync.undo(shift);

        assertFalse(UserPreferences.hasTranscriptTimeOffset(MEDIA_ID));
    }

    @Test
    public void undoKeepsAnchorsThatWereConfiguredAfterTheAd() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 0);
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 90_000, 20_000);
        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 50_000, 80_000), 50_000);
        assertNotNull(shift);
        assertEquals(30_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 80_000));

        TranscriptSync.undo(shift);

        assertEquals(20_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 90_000));
    }

    @Test
    public void undoKeepsAnAlignmentMadeAfterTheSkip() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 90_000);
        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000);
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 660_000, 120_000);

        TranscriptSync.undo(shift);

        assertEquals(120_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 660_000));
    }

    @Test
    public void undoKeepsAnEditMadeAfterTheAd() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 90_000);
        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000);
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 900_000, 120_000);

        TranscriptSync.undo(shift);

        assertEquals(120_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 900_000));
    }

    @Test
    public void headAdAlreadyCoveredByTheOffsetIsNotShiftedAgain() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 99_600);

        assertNull(TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 0, 99_914), 0));
        assertEquals(99_600, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 99_914));
    }

    @Test
    public void skipStartingInsideAnOverlappingSegmentKeepsTheCurrentCue() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 100_320);

        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 99_808, 111_216), 100_384);

        assertNotNull(shift);
        assertEquals(111_152, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 111_216));
    }

    @Test
    public void skipStartingWhereTheLastCutEndedKeepsTheOffset() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 600_000, 90_000);

        TranscriptSync.Shift shift = TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000);

        assertNotNull(shift);
        assertEquals(150_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 660_000));
    }

    @Test
    public void markedSegmentDoesNotTouchTheOffset() {
        assertNull(TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_MARKED, 600_000, 660_000), 600_000));
        assertFalse(UserPreferences.hasTranscriptTimeOffset(MEDIA_ID));
    }

    @Test
    public void userAlignmentAfterTheAdIsKept() {
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 0, 90_000);
        UserPreferences.setTranscriptTimeOffset(MEDIA_ID, 660_000, 120_000);

        assertNull(TranscriptSync.alignAfterSkip(
                MEDIA_ID, segment(AdSegment.TIER_AUTO, 600_000, 660_000), 600_000));
        assertEquals(120_000, UserPreferences.getTranscriptTimeOffset(MEDIA_ID, 660_000));
    }

    private static AdSegment segment(int tier, int startMs, int endMs) {
        AdSegment segment = new AdSegment();
        segment.setTier(tier);
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        return segment;
    }
}
