package de.danoeh.antennapod.ad;

import androidx.work.Data;
import androidx.work.WorkInfo;

import java.util.List;

/** User-visible progress of the initial feed scan. */
public final class AdScanStatus {
    public enum State { IDLE, QUEUED, RUNNING, RETRYING, DONE, FAILED }

    public final State state;
    public final int episode;
    public final int total;
    public final int episodes;
    public final int segments;

    public AdScanStatus(State state, int episode, int total, int episodes, int segments) {
        this.state = state;
        this.episode = episode;
        this.total = total;
        this.episodes = episodes;
        this.segments = segments;
    }

    public static AdScanStatus from(List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return new AdScanStatus(State.IDLE, 0, 0, 0, 0);
        }
        WorkInfo info = infos.get(0);
        return from(info.getState(), info.getRunAttemptCount(), info.getProgress(), info.getOutputData());
    }

    static AdScanStatus from(WorkInfo.State state, int attempts, Data progress, Data output) {
        switch (state) {
            case RUNNING:
                return new AdScanStatus(State.RUNNING,
                        progress.getInt(AdScanScheduler.KEY_PROGRESS_EPISODE, 0),
                        progress.getInt(AdScanScheduler.KEY_PROGRESS_TOTAL, 0), 0, 0);
            case ENQUEUED:
            case BLOCKED:
                return new AdScanStatus(attempts > 0 ? State.RETRYING : State.QUEUED, 0, 0, 0, 0);
            case SUCCEEDED:
                return new AdScanStatus(State.DONE, 0, 0,
                        output.getInt(AdScanScheduler.KEY_RESULT_EPISODES, 0),
                        output.getInt(AdScanScheduler.KEY_RESULT_SEGMENTS, 0));
            case FAILED:
                return new AdScanStatus(State.FAILED, 0, 0, 0, 0);
            default:
                return new AdScanStatus(State.IDLE, 0, 0, 0, 0);
        }
    }
}
