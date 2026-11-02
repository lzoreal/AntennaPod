package de.danoeh.antennapod.model.feed;

public class AdSegment {
    public static final int KIND_PREROLL = 0;
    public static final int KIND_MIDROLL = 1;
    public static final int KIND_OUTRO = 2;
    public static final int TIER_AUTO = 0;
    public static final int TIER_MARKED = 1;
    public static final int STATE_ACTIVE = 0;
    public static final int STATE_DISABLED = 1;

    private long id;
    private long mediaId;
    private int startMs;
    private int endMs;
    private int kind;
    private int tier;
    private int support;
    private float matchRatio;
    private int state = STATE_ACTIVE;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getMediaId() {
        return mediaId;
    }

    public void setMediaId(long mediaId) {
        this.mediaId = mediaId;
    }

    public int getStartMs() {
        return startMs;
    }

    public void setStartMs(int startMs) {
        this.startMs = startMs;
    }

    public int getEndMs() {
        return endMs;
    }

    public void setEndMs(int endMs) {
        this.endMs = endMs;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public int getTier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public int getSupport() {
        return support;
    }

    public void setSupport(int support) {
        this.support = support;
    }

    public float getMatchRatio() {
        return matchRatio;
    }

    public void setMatchRatio(float matchRatio) {
        this.matchRatio = matchRatio;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }
}
