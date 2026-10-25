package de.danoeh.antennapod;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;

/** Main application class. */
public class PodcastApp extends Application {
    private static final String TAG = "PodcastApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashReportExceptionHandler());
        RxJavaErrorHandlerSetup.setupRxJavaErrorHandler();

        try {
            // Robolectric calls onCreate for every test, which causes problems with static members
            EventBus.builder()
                    .logNoSubscriberMessages(false)
                    .sendNoSubscriberEvent(false)
                    .installDefaultEventBus();
        } catch (EventBusException e) {
            Log.d(TAG, e.getMessage());
        }

        DynamicColors.applyToActivitiesIfAvailable(this);
        ClientConfigurator.initialize(this);
        PreferenceUpgrader.checkUpgrades(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            TranscriptUtils.clearCache();
        }
    }
}
