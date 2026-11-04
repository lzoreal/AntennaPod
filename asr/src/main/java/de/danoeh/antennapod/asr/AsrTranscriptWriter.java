package de.danoeh.antennapod.asr;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Serializes recognized segments into the word-JSON format parsed by JsonTranscriptParser. */
public final class AsrTranscriptWriter {
    private AsrTranscriptWriter() {
    }

    public static String write(List<AsrSegment> segments, long audioDurationMs) {
        return write(segments, audioDurationMs, null, null);
    }

    public static String write(List<AsrSegment> segments, long audioDurationMs,
                               String modelId, List<MediaRange> coverage) {
        return write(segments, audioDurationMs, modelId, coverage, 0, null);
    }

    public static String write(List<AsrSegment> segments, long audioDurationMs,
                               String modelId, List<MediaRange> coverage,
                               long audioBytes, String fingerprint) {
        try {
            JSONObject root = new JSONObject();
            JSONArray segmentArray = new JSONArray();
            for (AsrSegment segment : segments) {
                segmentArray.put(toJson(segment));
            }
            root.put("segments", segmentArray);
            if (audioDurationMs > 0) {
                root.put("audio_seconds", audioDurationMs / 1000.0);
            }
            if (audioBytes > 0) {
                root.put("audio_bytes", audioBytes);
            }
            if (fingerprint != null) {
                root.put("fingerprint", fingerprint);
            }
            if (modelId != null) {
                root.put("version", GeneratedTranscriptCheckpoint.VERSION);
                root.put("model", modelId);
                JSONArray ranges = new JSONArray();
                if (coverage != null) {
                    for (MediaRange range : coverage) {
                        JSONArray pair = new JSONArray();
                        pair.put(range.startMs());
                        pair.put(range.endMs());
                        ranges.put(pair);
                    }
                }
                root.put("coverage", ranges);
            }
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to serialize transcript", e);
        }
    }

    private static JSONObject toJson(AsrSegment segment) throws JSONException {
        List<AsrWord> words = segment.getWords();
        JSONObject object = new JSONObject();
        object.put("text", segment.getText());
        if (!words.isEmpty()) {
            object.put("start", words.get(0).getStartTime());
            object.put("end", words.get(words.size() - 1).getEndTime());
            JSONArray wordArray = new JSONArray();
            for (AsrWord word : words) {
                JSONArray wordJson = new JSONArray();
                wordJson.put(word.getText());
                wordJson.put(word.getStartTime());
                wordJson.put(word.getEndTime() - word.getStartTime());
                wordArray.put(wordJson);
            }
            object.put("words", wordArray);
        }
        return object;
    }
}
