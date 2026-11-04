package de.danoeh.antennapod.asr;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

/** Converts parsed transcript segments back into the ASR segment model. */
public final class AsrTranscriptConverter {
    private AsrTranscriptConverter() {
    }

    public static List<AsrSegment> fromTranscript(Transcript transcript) {
        List<AsrSegment> result = new ArrayList<>(transcript.getSegmentCount());
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            TranscriptSegment segment = transcript.getSegmentAt(i);
            List<AsrWord> words = new ArrayList<>(segment.getWordTimings().size());
            for (TranscriptWord word : segment.getWordTimings()) {
                String text = segment.getWords().substring(word.getStartChar(), word.getEndChar());
                words.add(new AsrWord(text, word.getStartTime(), word.getEndTime(),
                        word.getStartChar(), word.getEndChar()));
            }
            result.add(new AsrSegment(segment.getWords(), words));
        }
        return result;
    }
}
