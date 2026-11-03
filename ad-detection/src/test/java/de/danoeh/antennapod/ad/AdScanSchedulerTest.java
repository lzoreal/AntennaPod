package de.danoeh.antennapod.ad;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdScanSchedulerTest {
    @Test
    public void requestIsUniqueAndNeedsUnmeteredNetwork() {
        OneTimeWorkRequest request = AdScanWorker.createRequest(42);
        Constraints constraints = request.getWorkSpec().constraints;
        assertEquals(NetworkType.UNMETERED, constraints.getRequiredNetworkType());
    }

    @Test
    public void prefetchRequestDoesNotPrefetchFurther() {
        assertTrue(HeadScanWorker.createRequest(42).getWorkSpec().input
                .getBoolean(HeadScanWorker.KEY_PREFETCH, false));
        assertFalse(HeadScanWorker.createPrefetchRequest(42).getWorkSpec().input
                .getBoolean(HeadScanWorker.KEY_PREFETCH, true));
        assertEquals(NetworkType.UNMETERED,
                HeadScanWorker.createPrefetchRequest(42).getWorkSpec().constraints.getRequiredNetworkType());
    }
}
