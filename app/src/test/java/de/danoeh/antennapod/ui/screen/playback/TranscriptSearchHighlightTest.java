package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;

/**
 * Search offsets must address the text that is actually drawn. With the default settings a
 * bilingual cue is re-ordered into EN/ZH pairs, so offsets measured on the raw segment text
 * would paint the highlight on the wrong language.
 */
@RunWith(RobolectricTestRunner.class)
public class TranscriptSearchHighlightTest {
    private static final String TEXT = "First sentence. Second sentence.\n第一句。第二句。";

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
        transcript.addSegment(new TranscriptSegment(0, 10_000, TEXT, "",
                Collections.emptyList()));
        media.setTranscript(transcript);
        adapter.setMedia(media);
        adapter.setLanguageMode(TranscriptAdapter.LANGUAGE_MODE_BOTH);
        adapter.setSentencePerLine(true);
    }

    @Test
    public void highlightFollowsRenderedInterleavedText() {
        assertEquals(1, adapter.setSearchQuery("第一句"));

        TranscriptViewholder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        adapter.onBindViewHolder(holder, 0);

        Spannable rendered = (Spannable) holder.viewContent.getText();
        String text = rendered.toString();
        assertTrue("expected interleaved rows, was: " + text,
                text.startsWith("First sentence.\n第一句。"));
        int expectedStart = text.indexOf("第一句");
        assertTrue(expectedStart >= 0);
        assertTrue("raw text offset must differ from rendered offset",
                TEXT.indexOf("第一句") != expectedStart);

        BackgroundColorSpan[] spans = rendered.getSpans(0, text.length(),
                BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        assertEquals(expectedStart, rendered.getSpanStart(spans[0]));
        assertEquals(expectedStart + "第一句".length(), rendered.getSpanEnd(spans[0]));
    }

    @Test
    public void noHighlightWhenQueryMissingFromRenderedText() {
        // The raw segment contains this text, but "仅原文" hides the translation.
        adapter.setLanguageMode(TranscriptAdapter.LANGUAGE_MODE_PRIMARY_ONLY);
        assertEquals(0, adapter.setSearchQuery("第一句"));

        TranscriptViewholder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        adapter.onBindViewHolder(holder, 0);
        Spannable rendered = (Spannable) holder.viewContent.getText();
        assertEquals(0, rendered.getSpans(0, rendered.length(),
                BackgroundColorSpan.class).length);
    }
}
