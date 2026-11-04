package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resume metadata for a generated transcript: model, audio duration and decoded ranges. */
public final class GeneratedTranscriptCheckpoint {
    public static final int VERSION = 1;
    private static final long DURATION_TOLERANCE_MS = 1000;

    private final String modelId;
    private final long audioDurationMs;
    private final List<MediaRange> coverage;
    private final long audioBytes;
    private final String fingerprint;

    public GeneratedTranscriptCheckpoint(@NonNull String modelId, long audioDurationMs,
                                         @NonNull List<MediaRange> coverage) {
        this(modelId, audioDurationMs, coverage, 0, null);
    }

    public GeneratedTranscriptCheckpoint(@NonNull String modelId, long audioDurationMs,
                                         @NonNull List<MediaRange> coverage, long audioBytes,
                                         @Nullable String fingerprint) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId is empty");
        }
        if (audioDurationMs < 0) {
            throw new IllegalArgumentException("audioDurationMs is negative: " + audioDurationMs);
        }
        List<MediaRange> ranges = new ArrayList<>(coverage);
        for (MediaRange range : ranges) {
            if (range == null) {
                throw new IllegalArgumentException("coverage contains a null range");
            }
        }
        this.modelId = modelId;
        this.audioDurationMs = audioDurationMs;
        this.coverage = Collections.unmodifiableList(ranges);
        this.audioBytes = audioBytes;
        this.fingerprint = fingerprint;
    }

    public String getModelId() {
        return modelId;
    }

    public long getAudioDurationMs() {
        return audioDurationMs;
    }

    public long getAudioBytes() {
        return audioBytes;
    }

    @Nullable
    public String getFingerprint() {
        return fingerprint;
    }

    @NonNull
    public List<MediaRange> getCoverage() {
        return coverage;
    }

    /** False when the saved checkpoint describes a different copy or length of the audio. */
    public boolean matchesAudio(long currentDurationMs, long currentBytes,
                                @Nullable String currentFingerprint) {
        if (audioDurationMs > 0 && currentDurationMs > 0
                && Math.abs(audioDurationMs - currentDurationMs) > DURATION_TOLERANCE_MS) {
            return false;
        }
        if (audioBytes > 0 && currentBytes > 0 && audioBytes != currentBytes) {
            return false;
        }
        return fingerprint == null || currentFingerprint == null
                || fingerprint.equals(currentFingerprint);
    }

    @NonNull
    public TranscriptCoverage toCoverage() {
        TranscriptCoverage result = new TranscriptCoverage();
        for (MediaRange range : coverage) {
            result.add(range);
        }
        return result;
    }

    @NonNull
    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", VERSION);
            object.put("model", modelId);
            if (audioDurationMs > 0) {
                object.put("audio_seconds", audioDurationMs / 1000.0);
            }
            if (audioBytes > 0) {
                object.put("audio_bytes", audioBytes);
            }
            if (fingerprint != null) {
                object.put("fingerprint", fingerprint);
            }
            JSONArray ranges = new JSONArray();
            for (MediaRange range : coverage) {
                JSONArray pair = new JSONArray();
                pair.put(range.startMs());
                pair.put(range.endMs());
                ranges.put(pair);
            }
            object.put("coverage", ranges);
            return object.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to serialize checkpoint", e);
        }
    }

    @NonNull
    public static GeneratedTranscriptCheckpoint fromJson(@NonNull String json) {
        if (json == null) {
            throw new IllegalArgumentException("json is null");
        }
        try {
            JSONObject object = new JSONObject(json);
            int version = object.getInt("version");
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported checkpoint version: " + version);
            }
            String modelId = object.getString("model");
            long audioDurationMs = Math.round(object.optDouble("audio_seconds", 0) * 1000.0);
            long audioBytes = object.optLong("audio_bytes", 0);
            String fingerprint = object.has("fingerprint")
                    ? object.getString("fingerprint") : null;
            List<MediaRange> ranges = new ArrayList<>();
            JSONArray coverage = object.optJSONArray("coverage");
            if (coverage != null) {
                for (int i = 0; i < coverage.length(); i++) {
                    JSONArray pair = coverage.getJSONArray(i);
                    ranges.add(new MediaRange(pair.getLong(0), pair.getLong(1)));
                }
            }
            return new GeneratedTranscriptCheckpoint(modelId, audioDurationMs, ranges,
                    audioBytes, fingerprint);
        } catch (JSONException e) {
            throw new IllegalArgumentException("invalid checkpoint", e);
        }
    }
}
