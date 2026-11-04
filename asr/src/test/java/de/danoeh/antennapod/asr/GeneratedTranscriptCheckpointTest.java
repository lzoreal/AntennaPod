package de.danoeh.antennapod.asr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GeneratedTranscriptCheckpointTest {
    @Test
    public void roundTripKeepsModelDurationAndCoverage() {
        GeneratedTranscriptCheckpoint checkpoint = new GeneratedTranscriptCheckpoint(
                "zh-14m", 12500,
                Arrays.asList(new MediaRange(0, 1000), new MediaRange(2000, 3000)));

        GeneratedTranscriptCheckpoint restored =
                GeneratedTranscriptCheckpoint.fromJson(checkpoint.toJson());

        assertEquals("zh-14m", restored.getModelId());
        assertEquals(12500, restored.getAudioDurationMs());
        assertEquals(Arrays.asList(new MediaRange(0, 1000), new MediaRange(2000, 3000)),
                restored.getCoverage());
        TranscriptCoverage coverage = restored.toCoverage();
        assertTrue(coverage.covers(0, 1000));
        assertTrue(coverage.covers(2000, 3000));
    }

    @Test
    public void emptyCoverageRoundTrips() {
        GeneratedTranscriptCheckpoint checkpoint = new GeneratedTranscriptCheckpoint(
                "en-20m", 0, Collections.emptyList());

        GeneratedTranscriptCheckpoint restored =
                GeneratedTranscriptCheckpoint.fromJson(checkpoint.toJson());

        assertEquals("en-20m", restored.getModelId());
        assertEquals(0, restored.getAudioDurationMs());
        assertTrue(restored.getCoverage().isEmpty());
    }

    @Test
    public void audioIdentityRoundTripsAndDetectsReplacedAudio() {
        GeneratedTranscriptCheckpoint checkpoint = new GeneratedTranscriptCheckpoint(
                "zh-14m", 12500, Collections.emptyList(), 987654, "abc123");

        GeneratedTranscriptCheckpoint restored =
                GeneratedTranscriptCheckpoint.fromJson(checkpoint.toJson());

        assertEquals(987654, restored.getAudioBytes());
        assertEquals("abc123", restored.getFingerprint());
        assertTrue(restored.matchesAudio(12500, 987654, "abc123"));
        assertTrue(restored.matchesAudio(13300, 987654, "abc123"));
        assertEquals(false, restored.matchesAudio(20000, 987654, "abc123"));
        assertEquals(false, restored.matchesAudio(12500, 987655, "abc123"));
        assertEquals(false, restored.matchesAudio(12500, 987654, "def456"));
    }

    @Test
    public void legacyCheckpointWithoutIdentityStillMatches() {
        GeneratedTranscriptCheckpoint checkpoint = new GeneratedTranscriptCheckpoint(
                "zh-14m", 12500, Collections.emptyList());

        assertTrue(checkpoint.matchesAudio(12500, 987654, "abc123"));
        assertEquals(false, checkpoint.matchesAudio(20000, 987654, "abc123"));
    }

    @Test
    public void unsupportedVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeneratedTranscriptCheckpoint.fromJson(
                "{\"version\":2,\"model\":\"m\",\"coverage\":[]}"));
    }

    @Test
    public void invertedRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeneratedTranscriptCheckpoint.fromJson(
                "{\"version\":1,\"model\":\"m\",\"coverage\":[[10,5]]}"));
    }

    @Test
    public void missingModelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeneratedTranscriptCheckpoint.fromJson(
                "{\"version\":1,\"coverage\":[]}"));
    }

    @Test
    public void blankModelIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedTranscriptCheckpoint("  ", 0, Collections.emptyList()));
    }
}
