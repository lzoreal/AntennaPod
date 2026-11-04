package de.danoeh.antennapod.ui.transcript;

import de.danoeh.antennapod.model.feed.Transcript;

/**
 * One transcript attached to one episode: the duration-scale correction and the per-media time
 * offset, so the page, the dialog and the floating window map positions the same way.
 */
public final class TranscriptSession {
    private final long mediaId;
    private final Transcript transcript;
    private double scale = 1.0;

    public TranscriptSession(long mediaId, Transcript transcript) {
        this.mediaId = mediaId;
        this.transcript = transcript;
    }

    public long getMediaId() {
        return mediaId;
    }

    public Transcript getTranscript() {
        return transcript;
    }

    public void setDuration(long reportedDurationMs) {
        scale = TranscriptTiming.scale(transcript, reportedDurationMs);
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public double getScale() {
        return scale;
    }

    public long offsetAt(long mediaPositionMs) {
        return TranscriptTiming.offsetAt(transcript, mediaId, mediaPositionMs);
    }

    public long offsetAtCue(long cueTimeMs) {
        return TranscriptTiming.offsetAtCue(transcript, mediaId, cueTimeMs);
    }

    public long toTranscriptTime(long playerPositionMs) {
        return TranscriptTiming.toTranscriptTime(playerPositionMs, scale);
    }

    public long toPlayerTime(long transcriptPositionMs) {
        return TranscriptTiming.toPlayerTime(transcriptPositionMs, scale);
    }

    /** Player position mapped into transcript coordinates with the offset applied. */
    public long transcriptPositionAt(long playerPositionMs) {
        long transcriptPosition = toTranscriptTime(playerPositionMs);
        return transcriptPosition - offsetAt(transcriptPosition);
    }

    /** Segment shown at a player position; -1 when there is none. */
    public int segmentIndexAtPlayerPosition(long playerPositionMs) {
        return transcript.findSegmentIndexBefore(transcriptPositionAt(playerPositionMs));
    }
}
