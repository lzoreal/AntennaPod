package de.danoeh.antennapod.model.feed;

public class AdFeedSettings {
    private final long feedId;
    private boolean enabled;
    private int windowSize = 8;

    public AdFeedSettings(long feedId) {
        this.feedId = feedId;
    }

    public long getFeedId() {
        return feedId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }
}
