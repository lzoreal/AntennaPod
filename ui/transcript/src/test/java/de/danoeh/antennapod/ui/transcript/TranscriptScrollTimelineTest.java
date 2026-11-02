package de.danoeh.antennapod.ui.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptWord;

public class TranscriptScrollTimelineTest {
    private static final List<TranscriptWord> WORDS = Arrays.asList(
            new TranscriptWord(0, 2, 0, 500),
            new TranscriptWord(3, 5, 500, 1000));

    @Test
    public void charIndexFollowsWordTimings() {
        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(WORDS, 0, 5, 0, 1000);

        assertEquals(0f, timeline.charIndexAt(0), 0.01f);
        assertEquals(1.5625f, timeline.charIndexAt(250), 0.01f);
        assertEquals(3f, timeline.charIndexAt(500), 0.01f);
        assertEquals(4.0625f, timeline.charIndexAt(750), 0.01f);
        assertEquals(5f, timeline.charIndexAt(1000), 0.01f);
        assertEquals(5f, timeline.charIndexAt(2000), 0.01f);
    }

    @Test
    public void charIndexMovesMonotonicallyWithoutSpeedJumps() {
        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(WORDS, 0, 5, 0, 1000);

        float previous = timeline.charIndexAt(0);
        float previousSpeed = Float.NaN;
        for (long time = 10; time <= 1000; time += 10) {
            float current = timeline.charIndexAt(time);
            float speed = (current - previous) / 10f;
            assertTrue("char index must not go backwards at " + time, current >= previous - 0.0001f);
            if (!Float.isNaN(previousSpeed)) {
                assertTrue("speed jump at " + time + ": " + previousSpeed + " -> " + speed,
                        Math.abs(speed - previousSpeed) < 0.0005f);
            }
            previous = current;
            previousSpeed = speed;
        }
    }

    @Test
    public void timelineStartsAtCurrentWordWhenStartingMidSentence() {
        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(WORDS, 0, 5, 500, 1000);

        assertEquals(1000, timeline.getEndTime());
        assertEquals(3f, timeline.charIndexAt(500), 0.01f);
        assertEquals(4f, timeline.charIndexAt(750), 0.01f);
    }

    @Test
    public void charIndexIsRelativeToDisplayedSentence() {
        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(WORDS, 3, 2, 500, 1000);

        assertEquals(0f, timeline.charIndexAt(500), 0.01f);
        assertEquals(1f, timeline.charIndexAt(750), 0.01f);
        assertEquals(2f, timeline.charIndexAt(1000), 0.01f);
    }

    @Test
    public void charIndexIsClampedToSentenceLength() {
        List<TranscriptWord> words = Arrays.asList(
                new TranscriptWord(0, 2, 0, 500),
                new TranscriptWord(40, 45, 500, 1000));

        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(words, 0, 10, 0, 1000);

        assertEquals(0f, timeline.charIndexAt(0), 0.01f);
        assertEquals(6.25f, timeline.charIndexAt(250), 0.01f);
        assertEquals(10f, timeline.charIndexAt(500), 0.01f);
        assertEquals(10f, timeline.charIndexAt(1000), 0.01f);
    }

    @Test
    public void emptyWordListScrollsLinearly() {
        TranscriptScrollTimeline timeline = TranscriptScrollTimeline.of(
                java.util.Collections.emptyList(), 0, 10, 0, 1000);

        assertEquals(0f, timeline.charIndexAt(0), 0.01f);
        assertEquals(5f, timeline.charIndexAt(500), 0.01f);
        assertEquals(10f, timeline.charIndexAt(1000), 0.01f);
    }
}
