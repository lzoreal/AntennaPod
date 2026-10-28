package de.danoeh.antennapod.storage.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class UserPreferencesTest {
    @Before
    public void setUp() {
        UserPreferences.init(ApplicationProvider.getApplicationContext());
        UserPreferences.getPreferences().edit().clear().commit();
    }

    @Test
    public void testTranscriptOffsetsAreStoredPerMedia() {
        UserPreferences.setTranscriptTimeOffset(1, 0, 1000);
        UserPreferences.setTranscriptTimeOffset(2, 0, 2000);

        assertEquals(1000, UserPreferences.getTranscriptTimeOffset(1, 0));
        assertEquals(2000, UserPreferences.getTranscriptTimeOffset(2, 0));
    }

    @Test
    public void testTranscriptOffsetCanBeRemoved() {
        UserPreferences.setTranscriptTimeOffset(1, 0, 1000);
        UserPreferences.setTranscriptTimeOffset(2, 0, 2000);

        UserPreferences.removeTranscriptTimeOffset(1);

        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1, 0));
        assertEquals(2000, UserPreferences.getTranscriptTimeOffset(2, 0));
    }

    @Test
    public void testTranscriptOffsetAnchorAppliesFromItsPositionOnwards() {
        UserPreferences.setTranscriptTimeOffset(1, 200_000, 90_000);

        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1, 0));
        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1, 199_999));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 200_000));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 900_000));
    }

    @Test
    public void testOffsetAnchoredOnOpeningLineCoversEpisodeStart() {
        UserPreferences.setTranscriptTimeOffset(1, 90_000, 90_000);

        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 0));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 30_000));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 90_000));
    }

    @Test
    public void testHeadAdAndMidAdSyncKeepEarlierContentAligned() {
        UserPreferences.setTranscriptTimeOffset(1, 90_000, 90_000);
        UserPreferences.setTranscriptTimeOffset(1, 1_980_000, 270_000);

        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 300_000));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 1_800_000));
        assertEquals(270_000, UserPreferences.getTranscriptTimeOffset(1, 2_000_000));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffsetAtCueTime(1, 1_700_000));
        assertEquals(270_000, UserPreferences.getTranscriptTimeOffsetAtCueTime(1, 1_720_000));
    }

    @Test
    public void testTranscriptOffsetUsesLatestAnchorBeforePosition() {
        UserPreferences.setTranscriptTimeOffset(1, 200_000, 90_000);
        UserPreferences.setTranscriptTimeOffset(1, 1_000_000, 60_000);

        assertEquals(90_000, UserPreferences.getTranscriptTimeOffset(1, 500_000));
        assertEquals(60_000, UserPreferences.getTranscriptTimeOffset(1, 1_500_000));
    }

    @Test
    public void testTranscriptOffsetAtCueTimeSkipsHeadAd() {
        UserPreferences.setTranscriptTimeOffset(1, 200_000, 90_000);

        assertEquals(0, UserPreferences.getTranscriptTimeOffsetAtCueTime(1, 50_000));
        assertEquals(90_000, UserPreferences.getTranscriptTimeOffsetAtCueTime(1, 150_000));
    }

    @Test
    public void testTranscriptOffsetUpdateOnlyAffectsCurrentSegment() {
        UserPreferences.setTranscriptTimeOffset(1, 0, 90_000);
        UserPreferences.setTranscriptTimeOffset(1, 1_000_000, 60_000);

        UserPreferences.updateTranscriptTimeOffset(1, 500_000, 95_000);

        assertEquals(95_000, UserPreferences.getTranscriptTimeOffset(1, 500_000));
        assertEquals(60_000, UserPreferences.getTranscriptTimeOffset(1, 1_500_000));
    }

    @Test
    public void testOffsetEditCannotOvertakePreviousSegment() {
        UserPreferences.setTranscriptTimeOffset(1, 0, 30_000);
        UserPreferences.setTranscriptTimeOffset(1, 5_000, 5_000);

        UserPreferences.updateTranscriptTimeOffset(1, 6_000, 35_000);

        assertEquals(30_000, UserPreferences.getTranscriptTimeOffset(1, 1_000));
        assertEquals(34_999, UserPreferences.getTranscriptTimeOffset(1, 6_000));
    }

    @Test
    public void testStartOffsetReplacesEveryAnchor() {
        UserPreferences.setTranscriptTimeOffset(1, 200_000, 90_000);
        UserPreferences.setTranscriptTimeOffset(1, 1_000_000, 60_000);

        UserPreferences.setTranscriptStartOffset(1, 100_000);

        assertEquals(100_000, UserPreferences.getTranscriptTimeOffset(1, 0));
        assertEquals(100_000, UserPreferences.getTranscriptTimeOffset(1, 1_500_000));
        assertEquals(100_000, UserPreferences.getTranscriptTimeOffsetAtCueTime(1, 10_000));
    }

    @Test
    public void testTranscriptOffsetAnchorIsReplacedAtSamePosition() {
        UserPreferences.setTranscriptTimeOffset(1, 300_000, 80_000);
        UserPreferences.setTranscriptTimeOffset(1, 300_400, 100_000);

        assertEquals(100_000, UserPreferences.getTranscriptTimeOffset(1, 300_000));
        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1, 100_000));
    }

    @Test
    public void testTranscriptOffsetResetClearsEveryAnchor() {
        UserPreferences.setTranscriptTimeOffset(1, 200_000, 90_000);
        UserPreferences.setTranscriptTimeOffset(1, 1_000_000, 60_000);
        assertTrue(UserPreferences.hasTranscriptTimeOffset(1));

        UserPreferences.removeTranscriptTimeOffset(1);

        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1, 900_000));
        assertFalse(UserPreferences.hasTranscriptTimeOffset(1));
    }

    @Test
    public void testTranscriptLayoutDefaults() {
        assertEquals(1.0f, UserPreferences.getTranscriptLineSpacingMultiplier(), 0.001f);
        assertEquals(24, UserPreferences.getTranscriptSideMarginsDp());
        assertEquals(8, UserPreferences.getTranscriptVerticalSpacingDp());
    }

    @Test
    public void testTranscriptLayoutStepsAreClamped() {
        UserPreferences.setTranscriptLineSpacingStep(5);
        UserPreferences.setTranscriptSideMarginsStep(-3);
        UserPreferences.setTranscriptVerticalSpacingStep(2);

        assertEquals(1.3f, UserPreferences.getTranscriptLineSpacingMultiplier(), 0.001f);
        assertEquals(16, UserPreferences.getTranscriptSideMarginsDp());
        assertEquals(16, UserPreferences.getTranscriptVerticalSpacingDp());

        UserPreferences.setTranscriptLineSpacingStep(0);
        UserPreferences.setTranscriptSideMarginsStep(1);
        UserPreferences.setTranscriptVerticalSpacingStep(1);

        assertEquals(1.0f, UserPreferences.getTranscriptLineSpacingMultiplier(), 0.001f);
        assertEquals(24, UserPreferences.getTranscriptSideMarginsDp());
        assertEquals(8, UserPreferences.getTranscriptVerticalSpacingDp());
    }

    @Test
    public void testLayoutKeysFromOtherBuildsAreIgnored() {
        UserPreferences.getPreferences().edit()
                .putInt("prefTranscriptLineSpacing", 0)
                .putInt("prefTranscriptSideMargins", 0)
                .putInt("prefTranscriptVerticalSpacing", 0)
                .commit();

        assertEquals(1.0f, UserPreferences.getTranscriptLineSpacingMultiplier(), 0.001f);
        assertEquals(24, UserPreferences.getTranscriptSideMarginsDp());
        assertEquals(8, UserPreferences.getTranscriptVerticalSpacingDp());
    }

    @Test
    public void testLegacyTranscriptOffsetIsMigrated() {
        SharedPreferences preferences = UserPreferences.getPreferences();
        preferences.edit()
                .putLong("prefTranscriptTimeOffsetMedia", 42L)
                .putLong("prefTranscriptTimeOffset", 1234L)
                .commit();

        assertEquals(1234, UserPreferences.getTranscriptTimeOffset(42, 0));
        assertEquals(1234, UserPreferences.getTranscriptTimeOffset(42, 900_000));
        assertEquals(0, UserPreferences.getTranscriptTimeOffset(7, 0));
    }
}
