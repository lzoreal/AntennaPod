package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import java.util.ArrayList;
import java.util.List;

/** Loads persisted segments off the main thread and maps them into SkipEngine. */
public final class AdSkipController {
    private final SkipEngine engine = new SkipEngine();
    private final List<AdSegment> segments = new ArrayList<>();
    private volatile AdSegment lastSkipped;

    public AdSkipController(Context context, long mediaId) {
        new Thread(() -> {
            List<AdSegment> loaded = DBReader.getAdSegments(mediaId);
            List<long[]> ranges = new ArrayList<>();
            synchronized (segments) {
                segments.clear();
                for (AdSegment s : loaded) {
                    if (s.getState() == AdSegment.STATE_ACTIVE && s.getTier() == AdSegment.TIER_AUTO) {
                        segments.add(s);
                        ranges.add(new long[]{s.getId(), s.getStartMs(), s.getEndMs()});
                    }
                }
            }
            engine.setSegments(ranges);
        }, "ad-segments-load").start();
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
