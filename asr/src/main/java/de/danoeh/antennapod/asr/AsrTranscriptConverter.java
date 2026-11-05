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
            int textLength = segment.getWords().length();
            for (TranscriptWord word : segment.getWordTimings()) {
                // A journal line may carry a stale character range; clamp it instead of
                // crashing every later generation attempt.
                int start = Math.max(0, Math.min(word.getStartChar(), textLength));
                int end = Math.max(start, Math.min(word.getEndChar(), textLength));
                if (end <= start) {
                    continue;
                }
                String text = segment.getWords().substring(start, end);
                words.add(new AsrWord(text, word.getStartTime(), word.getEndTime(),
                        start, end));
            }
            result.add(new AsrSegment(segment.getWords(), words));
        }
        return result;
    }
}
