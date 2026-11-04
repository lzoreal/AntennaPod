package de.danoeh.antennapod.asr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.parser.transcript.JsonTranscriptParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
public class AsrPipelineSeamTest {
    @Test
    public void latinWordsSurviveTheWholePipe() {
        String text = "hello world";
        List<AsrWord> words = AsrTokenizer.tokenize(text,
                new String[]{"▁hello", "▁world"}, new float[]{0.1f, 0.5f}, 1000);
        AsrSegment segment = new AsrSegment(text, words);

        String json = AsrTranscriptWriter.write(Collections.singletonList(segment), 1000);
        Transcript transcript = JsonTranscriptParser.parse(json);

        assertNotNull(transcript);
        assertEquals(1, transcript.getSegmentCount());
        TranscriptSegment parsed = transcript.getSegmentAt(0);
        assertEquals(text, parsed.getWords());
        assertEquals(2, parsed.getWordTimings().size());
        assertEquals(0, parsed.getWordTimings().get(0).getStartChar());
        assertEquals(5, parsed.getWordTimings().get(0).getEndChar());
        assertEquals(6, parsed.getWordTimings().get(1).getStartChar());
        assertEquals(11, parsed.getWordTimings().get(1).getEndChar());
        assertEquals(100, parsed.getWordTimings().get(0).getStartTime());
        assertEquals(1000, transcript.getAudioDurationMs());
    }
}
