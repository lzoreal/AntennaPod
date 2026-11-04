package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class AsrFinalizeSchedulerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
    }

    @Test
    public void scheduleIsUniquePerMediaId() throws Exception {
        AsrFinalizeScheduler.schedule(context, 42);
        AsrFinalizeScheduler.schedule(context, 42);

        List<WorkInfo> infos = work("asr-finalize-42");
        assertEquals(1, infos.size());
        assertEquals(WorkInfo.State.ENQUEUED, infos.get(0).getState());
    }

    @Test
    public void differentMediaIdsGetSeparateWork() throws Exception {
        AsrFinalizeScheduler.schedule(context, 42);
        AsrFinalizeScheduler.schedule(context, 43);

        assertEquals(1, work("asr-finalize-42").size());
        assertEquals(1, work("asr-finalize-43").size());
    }

    @Test
    public void cancelStopsTheScheduledWork() throws Exception {
        AsrFinalizeScheduler.schedule(context, 42);
        AsrFinalizeScheduler.cancel(context, 42);

        List<WorkInfo> infos = work("asr-finalize-42");
        for (WorkInfo info : infos) {
            assertEquals(WorkInfo.State.CANCELLED, info.getState());
        }
    }

    private List<WorkInfo> work(String name) throws Exception {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get();
    }
}
