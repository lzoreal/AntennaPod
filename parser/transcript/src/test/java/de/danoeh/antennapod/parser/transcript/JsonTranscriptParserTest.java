package de.danoeh.antennapod.parser.transcript;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

@RunWith(RobolectricTestRunner.class)
public class JsonTranscriptParserTest {
    private static String jsonStr = "{'version': '1.0.0', "
            + "'segments': [ "
            + "{ 'speaker' : 'John Doe', 'startTime': 0.8, 'endTime': 1.9, 'body': 'And' },"
            + "{ 'speaker' : 'Sally Green', 'startTime': 1.91, 'endTime': 2.8, 'body': 'this merges' },"
            + "{ 'startTime': 2.9, 'endTime': 3.4, 'body': ' the' },"
            + "{ 'startTime': 3.5, 'endTime': 3.6, 'body': ' person' }]}";

    @Test
    public void testParseJson() {
        Transcript result = JsonTranscriptParser.parse(jsonStr);
        // TODO: for gaps in the transcript (ads, music) should we return null?
        assertEquals(result.getSegmentAtTime(0L).getStartTime(), 800L);
        assertEquals(result.getSegmentAtTime(800L).getSpeaker(), "John Doe");
        assertEquals(result.getSegmentAtTime(800L).getStartTime(), 800L);
        assertEquals(result.getSegmentAtTime(800L).getEndTime(), 1900L);
        assertEquals(result.getSegmentAtTime(1800L).getStartTime(), 800L);
        // 2 segments get merged into at least 5 second
        assertEquals(result.getSegmentAtTime(1800L).getWords(), "And");
    }

    @Test
    public void testMissingSpeakersAreNotAddedToSpeakerSet() {
        String json = "{'segments': ["
                + "{'startTime': 0, 'endTime': 5, 'body': 'Hello'},"
                + "{'startTime': 5, 'endTime': 10, 'body': 'World'}]}";
        Transcript result = JsonTranscriptParser.parse(json);
        assertTrue(result.getSpeakers().isEmpty());
    }

    @Test
    public void testNullSpeakerIsIgnored() {
        String json = "{\"segments\":[{\"speaker\":null,\"startTime\":0,"
                + "\"endTime\":5,\"body\":\"Hello\"}]}";
        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        assertEquals("", result.getSegmentAt(0).getSpeaker());
        assertTrue(result.getSpeakers().isEmpty());
    }

    @Test
    public void testInvalidTimeRangeIsSkipped() {
        String json = "{\"segments\":[{\"startTime\":10,\"endTime\":5,\"body\":\"Bad\"}]}";

        assertNull(JsonTranscriptParser.parse(json));
    }

    @Test
    public void testOutOfOrderSegmentsAreSorted() {
        String json = "{\"segments\":["
                + "{\"startTime\":5,\"endTime\":10,\"body\":\"Second\"},"
                + "{\"startTime\":0,\"endTime\":5,\"body\":\"First\"}]}";
        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        assertEquals(2, result.getSegmentCount());
        assertEquals("First", result.getSegmentAt(0).getWords());
        assertEquals("Second", result.getSegmentAt(1).getWords());
    }

    @Test
    public void testInvalidSegmentDoesNotBreakNextSpeakerChange() {
        String json = "{\"segments\":["
                + "{\"speaker\":\"Alice\",\"startTime\":0,\"endTime\":2,\"body\":\"First.\"},"
                + "{\"speaker\":\"Alice\",\"body\":\"Broken\"},"
                + "{\"speaker\":\"Bob\",\"startTime\":4,\"endTime\":6,\"body\":\"Third.\"},"
                + "{\"speaker\":\"Bob\",\"startTime\":6,\"endTime\":8,\"body\":\"Fourth.\"}]}";
        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        assertEquals(0L, result.getSegmentAt(0).getStartTime());
        assertEquals(2000L, result.getSegmentAt(0).getEndTime());
    }

    @Test
    public void testParseTimedJsonWithWordTimings() {
        String json = "{\"segments\":["
                + "{\"start\":880,\"end\":23400,\"speaker\":\"Ira Glass\","
                + "\"text\":\"From WBEZ Chicago, it's This American Life.\","
                + "\"words\":[[\"From\",1120,320],[\"WBEZ\",1440,400],"
                + "[\"Chicago,\",1840,560],[\"it's\",2540,80],[\"This\",2640,100],"
                + "[\"American\",2740,220],[\"Life.\",2960,400]]},"
                + "{\"start\":23400,\"end\":null,\"speaker\":\"Ira Glass\","
                + "\"text\":\"Yes.\",\"words\":[]}]}";

        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        assertTrue(result.hasWordTimings());
        assertEquals(2, result.getSegmentCount());
        TranscriptSegment segment = result.getSegmentAt(0);
        assertEquals(880L, segment.getStartTime());
        assertEquals(23400L, segment.getEndTime());
        assertEquals("Ira Glass", segment.getSpeaker());
        assertEquals(7, segment.getWordTimings().size());
        TranscriptWord first = segment.getWordTimings().get(0);
        assertEquals(0, first.getStartChar());
        assertEquals(4, first.getEndChar());
        assertEquals("From", segment.getWords().substring(
                first.getStartChar(), first.getEndChar()));
        assertEquals(1120L, first.getStartTime());
        assertEquals(1440L, first.getEndTime());
        TranscriptWord last = segment.getWordTimings().get(6);
        assertEquals("Life.", segment.getWords().substring(
                last.getStartChar(), last.getEndChar()));
        assertEquals(2960L, last.getStartTime());
        assertEquals(3360L, last.getEndTime());

        TranscriptSegment annotation = result.getSegmentAt(1);
        assertEquals(23400L, annotation.getStartTime());
        assertEquals(23900L, annotation.getEndTime());
        assertTrue(!annotation.hasWordTimings());
    }

    @Test
    public void testTimedJsonDropsUntrustedDuplicateWords() {
        String json = "{\"segments\":[{\"start\":0,\"end\":5000,\"speaker\":\"Alice\","
                + "\"text\":\"partner, you will get more.\","
                + "\"words\":[[\"partner,\",0,200],[\"partner,\",200,200],"
                + "[\"you\",400,100],[\"will\",500,100],[\"get\",600,100],"
                + "[\"more.\",700,400]]}]}";

        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        TranscriptSegment segment = result.getSegmentAt(0);
        assertEquals(5, segment.getWordTimings().size());
        String[] expected = {"partner,", "you", "will", "get", "more."};
        for (int i = 0; i < expected.length; i++) {
            TranscriptWord word = segment.getWordTimings().get(i);
            assertEquals(expected[i], segment.getWords().substring(
                    word.getStartChar(), word.getEndChar()));
        }
        assertEquals(700L, segment.getWordTimings().get(4).getStartTime());
    }

    @Test
    public void testTimedJsonRejectsInvalidSegments() {
        String json = "{\"segments\":["
                + "{\"start\":-1,\"end\":1000,\"text\":\"Bad start.\",\"words\":[]},"
                + "{\"start\":1000,\"text\":\"\",\"words\":[]},"
                + "{\"start\":2000,\"end\":null,\"text\":\"Good.\",\"words\":[]}]}";

        Transcript result = JsonTranscriptParser.parse(json);

        assertNotNull(result);
        assertEquals(1, result.getSegmentCount());
        assertEquals("Good.", result.getSegmentAt(0).getWords());
        assertEquals(2500L, result.getSegmentAt(0).getEndTime());
    }

    @Test
    public void testLegacyJsonHasNoWordTimings() {
        Transcript result = JsonTranscriptParser.parse(jsonStr);

        assertNotNull(result);
        assertTrue(!result.hasWordTimings());
        assertTrue(!result.getSegmentAt(0).hasWordTimings());
    }

    @Test
    public void testParse() {
        String type = "application/json";
        Transcript result = TranscriptParser.parse(jsonStr, type);
        // There isn't a segment at 900L, so go backwards and get the segment at 800L
        assertEquals(result.getSegmentAtTime(900L).getSpeaker(), "John Doe");
        assertEquals(result.getSegmentAtTime(930L).getWords(), "And");

        // blank string
        String blankStr = "";
        result = TranscriptParser.parse(blankStr, type);
        assertEquals(result, null);

        result = TranscriptParser.parse(null, type);
        assertEquals(result, null);

        // All blank lines
        String allNewlinesStr = "\r\n\r\n\r\n\r\n";
        result = TranscriptParser.parse(allNewlinesStr, type);
        assertEquals(result, null);

        // segments is missing
        String jsonStrBad1 = "{'version': '1.0.0', "
                + "'segmentsX': [ "
                + "{ 'speaker' : 'John Doe', 'startTime': 0.8, 'endTime': 1.9, 'body': 'And' },"
                + "{ 'startTime': 2.9, 'endTime': 3.4, 'body': 'the' },"
                + "{ 'startTime': 3.5, 'endTime': 3.6, 'body': 'person' }]}";
        result = TranscriptParser.parse(jsonStrBad1, type);
        assertEquals(result, null);

        // invalid time formatting
        String jsonStrBad2 = "{'version': '1.0.0', "
                + "'segments': [ "
                + "{ 'speaker' : 'XJohn Doe', 'startTime': stringTime, 'endTime': stringTime, 'body': 'And' },"
                + "{ 'XstartTime': 2.9, 'XendTime': 3.4, 'body': 'the' },"
                + "{ 'startTime': '-2.9', 'endTime': '-3.4', 'body': 'the' },"
                + "{ 'startTime': 'bad_time', 'endTime': '-3.4', 'body': 'the' }]}";
        result = TranscriptParser.parse(jsonStrBad2, type);
        assertNull(result);

        // Just plain text
        String strBad3 = "John Doe: Promoting your podcast in a new\n\n"
                + "way. The latest from PogNews.";
        result = TranscriptParser.parse(strBad3, type);
        assertNull(result);

        // passing the wrong type
        type = "application/srt";
        result = TranscriptParser.parse(jsonStr, type);
        assertEquals(result, null);
    }
}
