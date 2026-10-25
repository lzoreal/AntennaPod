package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class TranscriptSearchTest {

    @Test
    public void emptyQueryHasNoMatches() {
        assertTrue(TranscriptSearch.findAll("hello world", "").isEmpty());
        assertTrue(TranscriptSearch.findAll("hello world", "   ").isEmpty());
        assertTrue(TranscriptSearch.findAll("hello world", null).isEmpty());
    }

    @Test
    public void matchingIsCaseInsensitive() {
        List<TranscriptSearch.Match> matches = TranscriptSearch.findAll("Greatness and GREATNESS", "greatness");

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).start);
        assertEquals(9, matches.get(0).end);
        assertEquals(14, matches.get(1).start);
        assertEquals(23, matches.get(1).end);
    }

    @Test
    public void findsCjkSubstrings() {
        List<TranscriptSearch.Match> matches = TranscriptSearch.findAll("这是一个伟大的故事，伟大的人。", "伟大");

        assertEquals(2, matches.size());
        assertEquals(4, matches.get(0).start);
        assertEquals(6, matches.get(0).end);
        assertEquals(10, matches.get(1).start);
        assertEquals(12, matches.get(1).end);
    }

    @Test
    public void findsMultipleOccurrencesWithoutOverlap() {
        List<TranscriptSearch.Match> matches = TranscriptSearch.findAll("aaaa", "aa");

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).start);
        assertEquals(2, matches.get(1).start);
    }

    @Test
    public void queryIsTrimmedBeforeMatching() {
        List<TranscriptSearch.Match> matches = TranscriptSearch.findAll("hello world", "  world  ");

        assertEquals(1, matches.size());
        assertEquals(6, matches.get(0).start);
        assertEquals(11, matches.get(0).end);
    }

    @Test
    public void noMatchReturnsEmptyList() {
        assertTrue(TranscriptSearch.findAll("hello world", "missing").isEmpty());
    }
}
