package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;

@RunWith(RobolectricTestRunner.class)
public class AsrGenerationManagerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PlaybackServiceInterface.isCasting = false;
        Settings.System.putInt(context.getContentResolver(), "POWER_SAVE_MODE_OPEN", 0);
    }

    @After
    public void tearDown() {
        PlaybackServiceInterface.isCasting = false;
        Settings.System.putInt(context.getContentResolver(), "POWER_SAVE_MODE_OPEN", 0);
    }

    @Test
    public void castingStopsGeneration() {
        PlaybackServiceInterface.isCasting = true;

        assertEquals(R.string.transcript_generate_casting,
                AsrGenerationManager.guardStopReason(context));
    }

    @Test
    public void batterySaverStopsGeneration() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(powerManager).setIsPowerSaveMode(true);

        assertEquals(R.string.transcript_generate_power_saver,
                AsrGenerationManager.guardStopReason(context));
    }

    @Test
    public void miuiPowerSaverStopsGeneration() {
        Settings.System.putInt(context.getContentResolver(), "POWER_SAVE_MODE_OPEN", 1);

        assertEquals(R.string.transcript_generate_power_saver,
                AsrGenerationManager.guardStopReason(context));
    }

    @Test
    public void normalStateKeepsGenerating() {
        assertEquals(0, AsrGenerationManager.guardStopReason(context));
    }
}
