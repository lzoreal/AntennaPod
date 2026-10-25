package de.danoeh.antennapod.parser.transcript;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import de.danoeh.antennapod.model.feed.Transcript;

@RunWith(RobolectricTestRunner.class)
public class SrtTranscriptParserTest {
    private static String srtStr = "1\n"
            + "00:00:00,000 --> 00:00:50,730\n"
            + "John Doe: Promoting your podcast in a new\n\n"
            + "2\n"
            + "00:00:90,740 --> 00:00:91,600\n"
            + "way. The latest from PogNews.\n\n"
            + "00:00:91,730 --> 00:00:93,600\n"
            + "We bring your favorite podcast.";

    @Test
    public void testParseSrt() {
        Transcript result = SrtTranscriptParser.parse(srtStr);

        assertEquals(result.getSegmentAtTime(0L).getWords(), "Promoting your podcast in a new");
        assertEquals(result.getSegmentAtTime(0L).getSpeaker(), "John Doe");
        assertEquals(result.getSegmentAtTime(0L).getStartTime(), 0L);
        assertEquals(result.getSegmentAtTime(0L).getEndTime(), 50730L);
        assertEquals(result.getSegmentAtTime(90740).getStartTime(), 90740);
        assertEquals("way. The latest from PogNews. We bring your favorite podcast.",
                result.getSegmentAtTime(90740).getWords());
    }

    @Test
    public void testSpeakerKeepsTextAfterSecondColon() {
        String srt = "1\n"
                + "00:00:00,000 --> 00:00:10,000\n"
                + "John Doe: Meet at 10:30";
        Transcript result = SrtTranscriptParser.parse(srt);

        assertEquals("John Doe", result.getSegmentAtTime(0L).getSpeaker());
        assertEquals("Meet at 10:30", result.getSegmentAtTime(0L).getWords());
    }

    @Test
    public void testColonInLaterLineIsNotTreatedAsSpeaker() {
        String srt = "1\n"
                + "00:00:00,000 --> 00:00:10,000\n"
                + "We went home.\n"
                + "Then he said: it was late.";
        Transcript result = SrtTranscriptParser.parse(srt);

        assertEquals("", result.getSegmentAtTime(0L).getSpeaker());
        assertEquals("We went home. Then he said: it was late.",
                result.getSegmentAtTime(0L).getWords());
    }

    @Test
    public void testProseColonIsNotTreatedAsSpeaker() {
        String srt = "1\n"
                + "00:00:00,000 --> 00:00:10,000\n"
                + "Alice: Hello everyone.\n\n"
                + "2\n"
                + "00:00:10,000 --> 00:00:20,000\n"
                + "Note: this transcript was edited.\n\n"
                + "3\n"
                + "00:00:20,000 --> 00:00:30,000\n"
                + "Alice: Goodbye.";
        Transcript result = SrtTranscriptParser.parse(srt);

        assertEquals("Note: this transcript was edited.",
                result.getSegmentAtTime(10000L).getWords());
        assertEquals("Alice", result.getSegmentAtTime(10000L).getSpeaker());
    }

    @Test
    public void testInvalidCueDoesNotBreakNextSegment() {
        String srt = "1\n"
                + "00:00:00,000 --> 00:00:02,000\n"
                + "Alice: First.\n\n"
                + "2\n"
                + "bad --> 00:00:06,000\n"
                + "Alice: Broken.\n\n"
                + "3\n"
                + "00:00:06,000 --> 00:00:08,000\n"
                + "Bob: Third.\n\n"
                + "4\n"
                + "00:00:08,000 --> 00:00:10,000\n"
                + "Bob: Fourth.";
        Transcript result = SrtTranscriptParser.parse(srt);

        assertEquals(0L, result.getSegmentAt(0).getStartTime());
        assertEquals(2000L, result.getSegmentAt(0).getEndTime());
        assertEquals("First.", result.getSegmentAt(0).getWords());
    }

    @Test
    public void testParse() {
        String type = "application/srr";
        Transcript result;

        result = TranscriptParser.parse(srtStr, type);
        // There isn't a segment at 800L, so go backwards and get the segment at 0L
        assertEquals(result.getSegmentAtTime(800L).getWords(), "Promoting your podcast in a new");

        result = TranscriptParser.parse(null, type);
        assertEquals(result, null);

        // blank string
        String blankStr = "";
        result = TranscriptParser.parse(blankStr, type);
        assertNull(result);

        // All empty lines
        String allNewlinesStr = "\r\n\r\n\r\n\r\n";
        result = TranscriptParser.parse(allNewlinesStr, type);
        assertEquals(result, null);

        // first segment has invalid time formatting, so the entire segment will be thrown out
        String srtStrBad1 = "00:0000,000 --> 00:00:02,730\n"
                + "John Doe: Promoting your podcast in a new\n\n"
                + "2\n"
                + "00:00:02,730 --> 00:00:04,600\n"
                + "way. The latest from PogNews.";
        result = TranscriptParser.parse(srtStrBad1, type);
        assertEquals(result.getSegmentAtTime(2730L).getWords(), "way. The latest from PogNews.");

        // first segment has invalid time in end time, 2nd segment has invalid time in both start time and end time
        String srtStrBad2 = "00:00:00,000 --> 00:0002,730\n"
                + "Jane Doe: Promoting your podcast in a new\n\n"
                + "2\n"
                + "badstarttime --> badendtime\n"
                + "way. The latest from PogNews.\n"
                + "badstarttime -->\n"
                + "Jane Doe says something\n"
                + "00:00:00,000 --> 00:00:02,730\n"
                + "Jane Doe:";
        result = TranscriptParser.parse(srtStrBad2, type);
        assertNull(result);

        // Just plain text
        String strBad3 = "John Doe: Promoting your podcast in a new\n\n"
                + "way. The latest from PogNews.";
        result = TranscriptParser.parse(strBad3, type);
        assertNull(result);

        // passing the wrong type
        type = "application/json";
        result = TranscriptParser.parse(srtStr, type);
        assertEquals(result, null);

        type = "unknown";
        result = TranscriptParser.parse(srtStr, type);
        assertEquals(result, null);
    }
}

