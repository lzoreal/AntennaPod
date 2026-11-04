package de.danoeh.antennapod.ui.transcript;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.Transcript;
import org.junit.Test;

public class TranscriptTimingTest {
    private static final double EPSILON = 1e-9;

    @Test
    public void smallDurationDriftIsScaled() {
        assertEquals(1.002298, TranscriptTiming.scaleFromDurations(3432045, 3424177), 1e-6);
    }

    @Test
    public void impossibleDurationRatioIsIgnored() {
        assertEquals(1.0, TranscriptTiming.scaleFromDurations(2_000_000, 1_000_000), EPSILON);
        assertEquals(1.0, TranscriptTiming.scaleFromDurations(500_000, 1_000_000), EPSILON);
    }

    @Test
    public void tinyDifferenceDisablesScaling() {
        assertEquals(1.0, TranscriptTiming.scaleFromDurations(1_000_100, 1_000_000), EPSILON);
    }

    @Test
    public void generatedTranscriptNeverUsesOffsets() {
        Transcript transcript = new Transcript();
        transcript.setGenerated(true);

        assertEquals(0, TranscriptTiming.offsetAt(transcript, 7, 120_000));
        assertEquals(0, TranscriptTiming.offsetAtCue(transcript, 7, 120_000));
    }

    @Test
    public void generatedTranscriptNeverUsesDurationScale() {
        Transcript transcript = new Transcript();
        transcript.setGenerated(true);
        transcript.setAudioDurationMs(2_000_000);

        assertEquals(1.0, TranscriptTiming.scale(transcript, 1_000_000), EPSILON);
    }

    @Test
    public void scaleConvertsBothDirections() {
        assertEquals(1_002_298, TranscriptTiming.toTranscriptTime(1_000_000, 1.002298));
        assertEquals(1_000_000, TranscriptTiming.toPlayerTime(1_002_298, 1.002298));
    }
}
