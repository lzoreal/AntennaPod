package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.widget.FrameLayout;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;

/** Pins the final span state of a word-graded row after the new single-setText bind path. */
@RunWith(RobolectricTestRunner.class)
public class TranscriptWordHighlightBindingTest {
    private Context context;
    private TranscriptAdapter adapter;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        adapter = new TranscriptAdapter(context, null);
        FeedMedia media = new FeedMedia(1L, new FeedItem(), 10_000, 0, 0L, "audio/mpeg",
                null, null, 0L, null, 0, 0L);
        Transcript transcript = new Transcript();
        transcript.addSegment(new TranscriptSegment(0, 1000, "hello world", "",
                Arrays.asList(new TranscriptWord(0, 5, 0, 200),
                        new TranscriptWord(6, 11, 200, 400))));
        media.setTranscript(transcript);
        adapter.setMedia(media);
        adapter.setLanguageMode(TranscriptAdapter.LANGUAGE_MODE_BOTH);
    }

    @Test
    public void onlyTheCurrentWordIsBoldAfterBinding() {
        adapter.setHighlightPosition(0, 100);

        TranscriptViewholder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        adapter.onBindViewHolder(holder, 0);

        Spannable rendered = (Spannable) holder.viewContent.getText();
        assertEquals("hello world", rendered.toString());

        boolean firstWordBold = false;
        boolean secondWordBold = false;
        for (CharacterStyle span : rendered.getSpans(0, rendered.length(),
                CharacterStyle.class)) {
            RecordingPaint paint = new RecordingPaint();
            span.updateDrawState(paint);
            if (!paint.fakeBold) {
                continue;
            }
            int start = rendered.getSpanStart(span);
            int end = rendered.getSpanEnd(span);
            if (start == 0 && end == 5) {
                firstWordBold = true;
            } else if (start == 6 && end == 11) {
                secondWordBold = true;
            }
        }
        assertTrue("current word must be bold", firstWordBold);
        assertFalse("unread word must not be bold", secondWordBold);
    }

    /** Robolectric's Paint does not report fake bold, so record the call instead. */
    private static final class RecordingPaint extends TextPaint {
        private boolean fakeBold;

        @Override
        public void setFakeBoldText(boolean fakeBoldText) {
            fakeBold = fakeBoldText;
            super.setFakeBoldText(fakeBoldText);
        }
    }
}
