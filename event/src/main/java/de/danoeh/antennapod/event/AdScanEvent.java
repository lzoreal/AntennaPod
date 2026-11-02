package de.danoeh.antennapod.event;

public class AdScanEvent {
    public final long feedId;
    public final int segmentCount;

    public AdScanEvent(long feedId, int segmentCount) {
        this.feedId = feedId;
        this.segmentCount = segmentCount;
    }
}
