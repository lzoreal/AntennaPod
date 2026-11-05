package de.danoeh.antennapod.asr;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsrTranscriptConverterTest {
    @Test
    public void wordTimingsBecomeWordsWithTheirText() {
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(100, 500, "hello world", "",
                Arrays.asList(new TranscriptWord(0, 5, 100, 300),
                        new TranscriptWord(6, 11, 350, 500))));

        List<AsrSegment> segments = AsrTranscriptConverter.fromTranscript(transcript);

        assertEquals(1, segments.size());
        AsrSegment segment = segments.get(0);
        assertEquals("hello world", segment.getText());
        assertEquals(2, segment.getWords().size());
        assertEquals("hello", segment.getWords().get(0).getText());
        assertEquals(100, segment.getWords().get(0).getStartTime());
        assertEquals(300, segment.getWords().get(0).getEndTime());
        assertEquals("world", segment.getWords().get(1).getText());
        assertEquals(6, segment.getWords().get(1).getStartChar());
        assertEquals(11, segment.getWords().get(1).getEndChar());
    }

    @Test
    public void segmentWithoutWordsStaysTextOnly() {
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(0, 0, "annotation", ""));

        List<AsrSegment> segments = AsrTranscriptConverter.fromTranscript(transcript);

        assertEquals(1, segments.size());
        assertTrue(segments.get(0).getWords().isEmpty());
    }

    @Test
    public void wordRangesPastTheTextAreClampedInsteadOfCrashing() {
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(0, 500, "hello", "",
                Arrays.asList(new TranscriptWord(0, 5, 0, 200),
                        new TranscriptWord(3, 99, 250, 400),
                        new TranscriptWord(9, 12, 450, 500))));

        List<AsrSegment> segments = AsrTranscriptConverter.fromTranscript(transcript);

        assertEquals(2, segments.get(0).getWords().size());
        assertEquals("hello", segments.get(0).getWords().get(0).getText());
        assertEquals("lo", segments.get(0).getWords().get(1).getText());
        assertEquals(5, segments.get(0).getWords().get(1).getEndChar());
    }
}
