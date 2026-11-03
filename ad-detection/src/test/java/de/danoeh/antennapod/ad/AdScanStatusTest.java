package de.danoeh.antennapod.ad;

import androidx.work.Data;
import androidx.work.WorkInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdScanStatusTest {
    @Test
    public void runningShowsEpisodeProgress() {
        Data progress = new Data.Builder()
                .putInt(AdScanScheduler.KEY_PROGRESS_EPISODE, 2)
                .putInt(AdScanScheduler.KEY_PROGRESS_TOTAL, 5)
                .build();
        AdScanStatus status = AdScanStatus.from(WorkInfo.State.RUNNING, 0, progress, Data.EMPTY);
        assertEquals(AdScanStatus.State.RUNNING, status.state);
        assertEquals(2, status.episode);
        assertEquals(5, status.total);
    }

    @Test
    public void enqueuedBecomesRetryingOnlyAfterAFailedAttempt() {
        assertEquals(AdScanStatus.State.QUEUED,
                AdScanStatus.from(WorkInfo.State.ENQUEUED, 0, Data.EMPTY, Data.EMPTY).state);
        assertEquals(AdScanStatus.State.RETRYING,
                AdScanStatus.from(WorkInfo.State.ENQUEUED, 1, Data.EMPTY, Data.EMPTY).state);
    }

    @Test
    public void succeededCarriesResultCounts() {
        Data output = new Data.Builder()
                .putInt(AdScanScheduler.KEY_RESULT_EPISODES, 5)
                .putInt(AdScanScheduler.KEY_RESULT_SEGMENTS, 12)
                .build();
        AdScanStatus status = AdScanStatus.from(WorkInfo.State.SUCCEEDED, 0, Data.EMPTY, output);
        assertEquals(AdScanStatus.State.DONE, status.state);
        assertEquals(5, status.episodes);
        assertEquals(12, status.segments);
    }

    @Test
    public void failedAndCancelledStates() {
        assertEquals(AdScanStatus.State.FAILED,
                AdScanStatus.from(WorkInfo.State.FAILED, 0, Data.EMPTY, Data.EMPTY).state);
        assertEquals(AdScanStatus.State.IDLE,
                AdScanStatus.from(WorkInfo.State.CANCELLED, 0, Data.EMPTY, Data.EMPTY).state);
    }
}
