package de.danoeh.antennapod.asr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AsrTranscriptWriterRoundTripTest {
    @Test
    public void wordsSurviveRoundTrip() {
        Transcript transcript = roundTrip(12500, segment("hello world",
                word("hello", 120, 300, 0, 5),
                word("world", 350, 500, 6, 11)));

        assertNotNull(transcript);
        assertEquals(1, transcript.getSegmentCount());
        TranscriptSegment segment = transcript.getSegmentAt(0);
        assertEquals("hello world", segment.getWords());
        assertEquals(2, segment.getWordTimings().size());
        assertEquals(0, segment.getWordTimings().get(0).getStartChar());
        assertEquals(5, segment.getWordTimings().get(0).getEndChar());
        assertEquals(6, segment.getWordTimings().get(1).getStartChar());
        assertEquals(11, segment.getWordTimings().get(1).getEndChar());
        assertEquals(120, segment.getWordTimings().get(0).getStartTime());
        assertEquals(500, segment.getWordTimings().get(1).getEndTime());
    }

    @Test
    public void wordStartTimesAreExact() {
        Transcript transcript = roundTrip(0, segment("one two",
                word("one", 1200, 1500, 0, 3),
                word("two", 1700, 2100, 4, 7)));

        assertNotNull(transcript);
        assertEquals(1200, transcript.getSegmentAt(0).getWordTimings().get(0).getStartTime());
        assertEquals(1700, transcript.getSegmentAt(0).getWordTimings().get(1).getStartTime());
    }

    @Test
    public void wordDurationsSurviveRoundTrip() {
        Transcript transcript = roundTrip(0, segment("one two",
                word("one", 1200, 1500, 0, 3),
                word("two", 1700, 2100, 4, 7)));

        assertNotNull(transcript);
        assertEquals(300, transcript.getSegmentAt(0).getWordTimings().get(0).getEndTime()
                - transcript.getSegmentAt(0).getWordTimings().get(0).getStartTime());
        assertEquals(400, transcript.getSegmentAt(0).getWordTimings().get(1).getEndTime()
                - transcript.getSegmentAt(0).getWordTimings().get(1).getStartTime());
    }

    @Test
    public void repeatedWordsKeepTheirOwnPositions() {
        Transcript transcript = roundTrip(0, segment("go go",
                word("go", 100, 200, 0, 2),
                word("go", 400, 500, 3, 5)));

        assertNotNull(transcript);
        TranscriptSegment segment = transcript.getSegmentAt(0);
        assertEquals(2, segment.getWordTimings().size());
        assertEquals(100, segment.getWordTimings().get(0).getStartTime());
        assertEquals(0, segment.getWordTimings().get(0).getStartChar());
        assertEquals(400, segment.getWordTimings().get(1).getStartTime());
        assertEquals(3, segment.getWordTimings().get(1).getStartChar());
    }

    @Test
    public void whitespaceIsPreservedVerbatim() {
        String text = "  hello   world ";
        Transcript transcript = roundTrip(0, segment(text,
                word("hello", 100, 200, 2, 7),
                word("world", 200, 300, 10, 15)));

        assertNotNull(transcript);
        assertEquals(text, transcript.getSegmentAt(0).getWords());
        assertEquals(2, transcript.getSegmentAt(0).getWordTimings().size());
    }

    @Test
    public void audioDurationSurvivesRoundTrip() {
        Transcript transcript = roundTrip(12500, segment("hello",
                word("hello", 0, 100, 0, 5)));

        assertNotNull(transcript);
        assertEquals(12500, transcript.getAudioDurationMs());
    }

    @Test
    public void zeroAudioDurationRoundTripsAsZero() {
        Transcript transcript = roundTrip(0, segment("hello",
                word("hello", 0, 100, 0, 5)));

        assertNotNull(transcript);
        assertEquals(0, transcript.getAudioDurationMs());
    }

    @Test
    public void segmentEndStaysAboveItsStart() {
        Transcript transcript = roundTrip(0, segment("hello",
                word("hello", 1000, 1000, 0, 5)));

        assertNotNull(transcript);
        TranscriptSegment segment = transcript.getSegmentAt(0);
        assertEquals(1000, segment.getStartTime());
        assertTrue(segment.getEndTime() > segment.getStartTime());
    }

    private static Transcript roundTrip(long audioDurationMs, AsrSegment... segments) {
        String json = AsrTranscriptWriter.write(Arrays.asList(segments), audioDurationMs);
        return de.danoeh.antennapod.parser.transcript.JsonTranscriptParser.parse(json);
    }

    private static AsrSegment segment(String text, AsrWord... words) {
        return new AsrSegment(text, Arrays.asList(words));
    }

    private static AsrWord word(String text, long start, long end, int startChar, int endChar) {
        return new AsrWord(text, start, end, startChar, endChar);
    }
}
