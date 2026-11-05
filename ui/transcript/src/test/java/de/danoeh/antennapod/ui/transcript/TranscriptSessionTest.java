package de.danoeh.antennapod.ui.transcript;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.Transcript;
import org.junit.Test;

public class TranscriptSessionTest {
    private static final double EPSILON = 1e-9;

    @Test
    public void durationSetsTheScaleUsedInBothDirections() {
        Transcript transcript = new Transcript();
        transcript.setAudioDurationMs(1_002_298);
        TranscriptSession session = new TranscriptSession(7, transcript);
        session.setDuration(1_000_000);

        assertEquals(1_002_298, session.toTranscriptTime(1_000_000));
        assertEquals(1_000_000, session.toPlayerTime(1_002_298));
        assertEquals(session.getScale(), 1.002298, 1e-6);
    }

    @Test
    public void generatedTranscriptHasNoDurationScale() {
        Transcript transcript = new Transcript();
        transcript.setGenerated(true);
        transcript.setAudioDurationMs(2_000_000);
        TranscriptSession session = new TranscriptSession(7, transcript);
        session.setDuration(1_000_000);

        assertEquals(1.0, session.getScale(), EPSILON);
        assertEquals(1_000_000, session.toTranscriptTime(1_000_000));
    }
}
