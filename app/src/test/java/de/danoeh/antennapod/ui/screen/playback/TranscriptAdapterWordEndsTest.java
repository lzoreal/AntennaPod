package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertArrayEquals;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
import java.util.Arrays;
import org.junit.Test;

public class TranscriptAdapterWordEndsTest {
    @Test
    public void missingEndUsesNextGreaterStartAndFallsBackToSegmentEnd() {
        TranscriptSegment segment = new TranscriptSegment(0, 5000, "a b c d", null, Arrays.asList(
                new TranscriptWord(0, 1, 1000, 1500),
                new TranscriptWord(2, 3, 1500, 0),
                new TranscriptWord(4, 5, 2000, 2500),
                new TranscriptWord(6, 7, 2000, 0)));

        assertArrayEquals(new long[] {1500, 2000, 2500, 5000},
                TranscriptDisplayInfoBuilder.computeWordEndTimes(segment));
    }

    @Test
    public void duplicateStartsShareTheNextStrictlyGreaterStart() {
        TranscriptSegment segment = new TranscriptSegment(0, 5000, "a b c", null, Arrays.asList(
                new TranscriptWord(0, 1, 3000, 0),
                new TranscriptWord(2, 3, 3000, 0),
                new TranscriptWord(4, 5, 4000, 4200)));

        assertArrayEquals(new long[] {4000, 4000, 4200},
                TranscriptDisplayInfoBuilder.computeWordEndTimes(segment));
    }

    @Test
    public void missingEndAfterLastWordUsesMinimumSpan() {
        TranscriptSegment segment = new TranscriptSegment(0, 1000, "a", null, Arrays.asList(
                new TranscriptWord(0, 1, 900, 0)));

        assertArrayEquals(new long[] {1020}, TranscriptDisplayInfoBuilder.computeWordEndTimes(segment));
    }
}
