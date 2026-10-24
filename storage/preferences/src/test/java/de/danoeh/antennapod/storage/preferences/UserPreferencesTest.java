package de.danoeh.antennapod.storage.preferences;

import static org.junit.Assert.assertEquals;

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
        UserPreferences.setTranscriptTimeOffset(1, 1000);
        UserPreferences.setTranscriptTimeOffset(2, 2000);

        assertEquals(1000, UserPreferences.getTranscriptTimeOffset(1));
        assertEquals(2000, UserPreferences.getTranscriptTimeOffset(2));
    }

    @Test
    public void testTranscriptOffsetCanBeRemoved() {
        UserPreferences.setTranscriptTimeOffset(1, 1000);
        UserPreferences.setTranscriptTimeOffset(2, 2000);

        UserPreferences.removeTranscriptTimeOffset(1);

        assertEquals(0, UserPreferences.getTranscriptTimeOffset(1));
        assertEquals(2000, UserPreferences.getTranscriptTimeOffset(2));
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

        assertEquals(1234, UserPreferences.getTranscriptTimeOffset(42));
        assertEquals(0, UserPreferences.getTranscriptTimeOffset(7));
    }
}
