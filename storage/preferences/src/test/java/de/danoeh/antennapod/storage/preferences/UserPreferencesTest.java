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
