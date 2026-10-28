package de.danoeh.antennapod.storage.preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Piecewise-constant transcript offsets. Audio with dynamically inserted ads is shifted by a
 * different amount after every ad, so each anchor re-aligns the transcript from the media
 * position it was measured at until the next anchor takes over.
 */
public final class TranscriptTimeOffsets {
    private static final long SAME_ANCHOR_TOLERANCE_MS = 1000;

    private final List<Anchor> anchors = new ArrayList<>();

    public static final class Anchor {
        public final long mediaPositionMs;
        public final long offsetMs;

        Anchor(long mediaPositionMs, long offsetMs) {
            this.mediaPositionMs = mediaPositionMs;
            this.offsetMs = offsetMs;
        }

        /** Transcript time this anchor maps its media position to. */
        long cueTimeMs() {
            return mediaPositionMs - offsetMs;
        }
    }

    public boolean isEmpty() {
        return anchors.isEmpty();
    }

    public List<Anchor> getAnchors() {
        return Collections.unmodifiableList(anchors);
    }

    public long offsetAtMediaTime(long mediaPositionMs) {
        Anchor governing = null;
        for (Anchor anchor : anchors) {
            if (anchor.mediaPositionMs > mediaPositionMs) {
                break;
            }
            governing = anchor;
        }
        return governing == null ? 0 : governing.offsetMs;
    }

    public long offsetAtCueTime(long cueTimeMs) {
        Anchor governing = null;
        for (Anchor anchor : anchors) {
            if (anchor.cueTimeMs() > cueTimeMs) {
                break;
            }
            governing = anchor;
        }
        return governing == null ? 0 : governing.offsetMs;
    }

    /** Starts a new segment at {@code mediaPositionMs}, replacing any anchor measured there. */
    public void setAnchor(long mediaPositionMs, long offsetMs) {
        long coverageStartMs = mediaPositionMs;
        if (governingIndex(mediaPositionMs) < 0
                && mediaPositionMs - offsetMs <= SAME_ANCHOR_TOLERANCE_MS) {
            // Aligned on the opening line, so the ad sits before the content: cover the episode
            // start too, otherwise the transcript scrolls through the ad and jumps back after it.
            coverageStartMs = 0;
        }
        for (Iterator<Anchor> iterator = anchors.iterator(); iterator.hasNext();) {
            Anchor existing = iterator.next();
            if (Math.abs(existing.mediaPositionMs - mediaPositionMs) <= SAME_ANCHOR_TOLERANCE_MS) {
                // Re-syncing the same spot must not shrink the segment it already covers.
                coverageStartMs = Math.min(coverageStartMs, existing.mediaPositionMs);
                iterator.remove();
            }
        }
        anchors.add(new Anchor(coverageStartMs, offsetMs));
        normalize();
    }

    /**
     * Changes the offset of the segment playing at {@code mediaPositionMs}. The edit is clamped so
     * it cannot move the segment onto or past a neighbour's cue time, which would make this anchor
     * unreachable and silently fall back to the neighbour.
     */
    public void setOffsetForSegment(long mediaPositionMs, long offsetMs) {
        int governing = governingIndex(mediaPositionMs);
        if (governing < 0) {
            anchors.add(new Anchor(0, offsetMs));
            normalize();
            return;
        }
        Anchor anchor = anchors.get(governing);
        long cueTime = anchor.mediaPositionMs - offsetMs;
        if (governing > 0) {
            cueTime = Math.max(cueTime, anchors.get(governing - 1).cueTimeMs() + 1);
        }
        if (governing + 1 < anchors.size()) {
            cueTime = Math.min(cueTime, anchors.get(governing + 1).cueTimeMs() - 1);
        }
        anchors.set(governing, new Anchor(anchor.mediaPositionMs, anchor.mediaPositionMs - cueTime));
        normalize();
    }

    private int governingIndex(long mediaPositionMs) {
        int governing = -1;
        for (int i = 0; i < anchors.size(); i++) {
            if (anchors.get(i).mediaPositionMs > mediaPositionMs) {
                break;
            }
            governing = i;
        }
        return governing;
    }

    /**
     * Keeps anchors ordered on both timelines. An anchor whose cue time is not after the previous
     * one can never govern a cue, so it is dropped; runs are idempotent.
     */
    private void normalize() {
        anchors.sort(Comparator.comparingLong(anchor -> anchor.mediaPositionMs));
        long previousCueTime = Long.MIN_VALUE;
        for (Iterator<Anchor> iterator = anchors.iterator(); iterator.hasNext();) {
            Anchor anchor = iterator.next();
            if (anchor.cueTimeMs() <= previousCueTime) {
                iterator.remove();
            } else {
                previousCueTime = anchor.cueTimeMs();
            }
        }
    }
}
