package de.danoeh.antennapod.ad;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdScanSchedulerTest {
    @Test
    public void requestIsUniqueAndNeedsUnmeteredNetwork() {
        OneTimeWorkRequest request = AdScanWorker.createRequest(42);
        Constraints constraints = request.getWorkSpec().constraints;
        assertEquals(NetworkType.UNMETERED, constraints.getRequiredNetworkType());
    }
}
