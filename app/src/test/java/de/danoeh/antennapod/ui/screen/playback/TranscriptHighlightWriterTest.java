package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TranscriptHighlightWriterTest {
    private static final int CURRENT = 0xFF000001;
    private static final int STRONG = 0xFF000002;
    private static final int MUTED = 0xFF000003;

    @Test
    public void sentenceGradingMarksPastCurrentAndUpcoming() {
        DisplayInfo info = new DisplayInfo("One. Two. Three.", -1, 16,
                new int[] {0, 5, 10, 16}, null, null, null, null);

        SpannableStringBuilder graded = TranscriptHighlightWriter.buildSentence(
                info, 1, CURRENT, STRONG, MUTED);

        assertEquals(STRONG, colorAt(graded, 1));
        assertEquals(CURRENT, colorAt(graded, 6));
        assertEquals(MUTED, colorAt(graded, 12));
    }

    @Test
    public void sentenceGradingColorsTranslationSeparately() {
        // Primary "One. Two." then a newline and the translation "Un. Deux."
        String text = "One. Two.\nUn. Deux.";
        DisplayInfo info = new DisplayInfo(text, 9, 9,
                new int[] {0, 5, 9}, new int[] {0, 4, 9}, null, null, null);

        SpannableStringBuilder graded = TranscriptHighlightWriter.buildSentence(
                info, 1, CURRENT, STRONG, MUTED);

        assertEquals(STRONG, colorAt(graded, 1));
        assertEquals(CURRENT, colorAt(graded, 6));
        // Translation sentences are graded on their own index, clamped to their last sentence.
        assertEquals(STRONG, colorAt(graded, 10));
        assertEquals(CURRENT, colorAt(graded, 15));
    }

    @Test
    public void wordGradingFollowsTheCurrentWordAndSkipsUnchangedUpdates() {
        TranscriptSegment segment = new TranscriptSegment(0, 3000, "aa bb cc", null, Arrays.asList(
                new TranscriptWord(0, 2, 1000, 1500),
                new TranscriptWord(3, 5, 1500, 2000),
                new TranscriptWord(6, 8, 2000, 2500)));
        DisplayInfo info = new DisplayInfo("aa bb cc", -1, 8,
                new int[] {0, 8}, null,
                new int[] {0, 3, 6}, new int[] {2, 5, 8},
                new long[] {1500, 2000, 2500});
        SpannableStringBuilder content = new SpannableStringBuilder(info.text);
        TranscriptHighlightWriter.WordSpans spans = TranscriptHighlightWriter.WordSpans.build(
                content, info, segment, MUTED);

        assertTrue(spans.update(info, segment, 0, true, 1700, 0,
                STRONG, MUTED, true));
        assertEquals(STRONG, colorAt(content, 1));
        assertEquals(STRONG, colorAt(content, 4));
        assertEquals(MUTED, colorAt(content, 7));

        assertFalse(spans.update(info, segment, 0, true, 1700, 0,
                STRONG, MUTED, false));

        assertTrue(spans.update(info, segment, 0, false, 2600, 1,
                STRONG, MUTED, false));
        assertEquals(STRONG, colorAt(content, 1));
        assertEquals(STRONG, colorAt(content, 4));
        assertEquals(STRONG, colorAt(content, 7));
    }

    private static int colorAt(SpannableStringBuilder text, int offset) {
        TextPaint paint = new TextPaint();
        for (CharacterStyle span : text.getSpans(offset, offset + 1, CharacterStyle.class)) {
            span.updateDrawState(paint);
        }
        return paint.getColor();
    }
}
