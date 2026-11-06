package de.danoeh.antennapod.playback.service.internal;

import android.util.Log;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import java.util.ArrayList;
import java.util.List;

/** Loads persisted segments off the main thread and maps them into SkipEngine. */
public final class AdSkipController {
    private static final String TAG = "AdSkipController";
    private final SkipEngine engine = new SkipEngine();
    private final List<AdSegment> segments = new ArrayList<>();
    private final long mediaId;
    private volatile AdSegment lastSkipped;

    public AdSkipController(long mediaId) {
        this.mediaId = mediaId;
        reload();
    }

    /** Re-reads segments, e.g. after a head scan finished during playback. */
    public void reload() {
        new Thread(() -> {
            List<AdSegment> loaded = DBReader.getAdSegments(mediaId);
            boolean aggressive = isAggressive();
            List<long[]> ranges = new ArrayList<>();
            synchronized (segments) {
                segments.clear();
                for (AdSegment s : loaded) {
                    if (isSkippable(s, aggressive)) {
                        segments.add(s);
                        ranges.add(new long[]{s.getId(), s.getStartMs(), s.getEndMs()});
                    }
                }
            }
            engine.setSegments(ranges);
            Log.d(TAG, "media " + mediaId + ": " + ranges.size() + "/" + loaded.size()
                    + " segments skippable, aggressive=" + aggressive);
        }, "ad-segments-load").start();
    }

    static boolean isSkippable(AdSegment segment, boolean aggressive) {
        if (segment.getState() != AdSegment.STATE_ACTIVE) {
            return false;
        }
        if (segment.getTier() == AdSegment.TIER_AUTO) {
            return true;
        }
        return aggressive;
    }

    private boolean isAggressive() {
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getItem() == null || media.getItem().getFeed() == null) {
            return false;
        }
        return DBReader.getAdFeedSettings(media.getItem().getFeed().getId()).isAggressive();
    }

    public Long skipAt(long positionMs) {
        Long target = engine.onPosition(positionMs);
        if (target != null) {
            synchronized (segments) {
                for (AdSegment s : segments) {
                    if (positionMs >= s.getStartMs() && positionMs < s.getEndMs()) {
                        lastSkipped = s;
                        break;
                    }
                }
            }
        }
        return target;
    }

    public void onUserSeek(long positionMs) {
        engine.onUserSeek(positionMs);
    }

    public void onProgrammaticSeek(long positionMs) {
        engine.onProgrammaticSeek(positionMs);
    }

    public AdSegment lastSkipped() {
        return lastSkipped;
    }

    public void undoLast() {
        AdSegment skipped = lastSkipped;
        if (skipped == null) {
            return;
        }
        engine.disable(skipped.getId());
        DBWriter.setAdSegmentState(skipped.getId(), AdSegment.STATE_DISABLED);
        lastSkipped = null;
    }

    public void disableAllForMedia(long mediaId) {
        synchronized (segments) {
            for (AdSegment s : segments) {
                engine.disable(s.getId());
            }
        }
        DBWriter.setAllAdSegmentsState(mediaId, AdSegment.STATE_DISABLED);
    }
}
