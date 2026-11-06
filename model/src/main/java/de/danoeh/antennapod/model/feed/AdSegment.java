package de.danoeh.antennapod.model.feed;

import java.util.Arrays;

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
    private long[] sourceMediaIds = new long[0];

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

    public long[] getSourceMediaIds() {
        return sourceMediaIds;
    }

    public void setSourceMediaIds(long[] sourceMediaIds) {
        this.sourceMediaIds = sourceMediaIds == null ? new long[0] : sourceMediaIds;
    }

    public static String formatSources(long[] ids) {
        if (ids == null || ids.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(ids.length * 8);
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(ids[i]);
        }
        return out.toString();
    }

    public static long[] parseSources(String value) {
        if (value == null || value.isEmpty()) {
            return new long[0];
        }
        String[] parts = value.split(",");
        long[] ids = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                ids[count++] = Long.parseLong(part.trim());
            } catch (NumberFormatException ignored) {
                // Skip malformed entries.
            }
        }
        return Arrays.copyOf(ids, count);
    }
}
