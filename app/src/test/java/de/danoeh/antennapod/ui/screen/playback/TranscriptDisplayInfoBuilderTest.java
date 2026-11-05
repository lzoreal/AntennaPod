package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

public class TranscriptDisplayInfoBuilderTest {
    private static final String BILINGUAL = "First sentence. Second sentence.\n第一句。第二句。";

    private final TranscriptDisplayInfoBuilder builder = new TranscriptDisplayInfoBuilder();
    private final TranscriptSegment segment = new TranscriptSegment(0, 10_000, BILINGUAL, "",
            Collections.emptyList());

    @Test
    public void cacheSeparatesLanguageModes() {
        DisplayInfo both = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true);
        DisplayInfo primaryOnly = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY, true);
        DisplayInfo bothAgain = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true);

        assertTrue(both.text.contains("第一句"));
        assertFalse(primaryOnly.text.contains("第一句"));
        assertEquals(both.text, bothAgain.text);
    }

    @Test
    public void cacheSeparatesSentencePerLine() {
        DisplayInfo interleaved = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true);
        DisplayInfo block = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, false);
        DisplayInfo interleavedAgain = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true);

        assertTrue(interleaved.text.startsWith("First sentence.\n第一句。"));
        assertEquals(BILINGUAL, block.text);
        assertEquals(interleaved.text, interleavedAgain.text);
    }

    @Test
    public void searchTextMatchesTheRenderedText() {
        DisplayInfo both = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true);
        DisplayInfo primary = builder.get(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY, false);

        assertEquals(both.text, builder.textFor(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH, true));
        assertEquals(primary.text, builder.textFor(segment,
                UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY, false));
    }
}
