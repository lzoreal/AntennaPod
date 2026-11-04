package de.danoeh.antennapod.parser.transcript;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

/**
 * Append-only sidecar for segments recognized after the last full transcript snapshot.
 * Each line is one segment; a torn tail is ignored so a crash can never corrupt the snapshot.
 */
public final class TranscriptJournal {
    public static final String SUFFIX = ".journal";

    private TranscriptJournal() {
    }

    @NonNull
    public static File fileFor(@NonNull File transcriptFile) {
        return new File(transcriptFile.getPath() + SUFFIX);
    }

    public static void append(@NonNull File transcriptFile, @NonNull TranscriptSegment segment)
            throws IOException {
        File journal = fileFor(transcriptFile);
        File parent = journal.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        byte[] line = (toJson(segment) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(journal, true)) {
            output.write(line);
            output.flush();
            output.getFD().sync();
        }
    }

    @NonNull
    public static List<TranscriptSegment> read(@NonNull File transcriptFile) {
        File journal = fileFor(transcriptFile);
        if (!journal.isFile()) {
            return Collections.emptyList();
        }
        List<TranscriptSegment> segments = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(journal), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                TranscriptSegment segment;
                try {
                    segment = fromJson(line);
                } catch (JSONException | IllegalArgumentException e) {
                    // Torn or corrupt tail: everything after it is unreadable as well.
                    break;
                }
                segments.add(segment);
            }
        } catch (IOException e) {
            return segments;
        }
        return segments;
    }

    public static void clear(@NonNull File transcriptFile) throws IOException {
        File journal = fileFor(transcriptFile);
        if (journal.exists() && !journal.delete()) {
            throw new IOException("cannot delete " + journal);
        }
    }

    /**
     * Appends the journal to {@code transcript}, skipping segments that are already in the
     * snapshot. A crash between writing the snapshot and clearing the journal replays that batch.
     */
    public static void applyTo(@NonNull Transcript transcript, @NonNull File transcriptFile) {
        List<TranscriptSegment> journal = read(transcriptFile);
        if (journal.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            existing.add(signature(transcript.getSegmentAt(i)));
        }
        for (TranscriptSegment segment : journal) {
            if (existing.add(signature(segment))) {
                transcript.addSegment(segment);
            }
        }
    }

    private static String signature(TranscriptSegment segment) {
        return segment.getStartTime() + "|" + segment.getEndTime() + "|"
                + segment.getWordTimings().size() + "|" + segment.getWords();
    }

    static String toJson(TranscriptSegment segment) {
        try {
            JSONObject object = new JSONObject();
            object.put("text", segment.getWords());
            if (segment.getSpeaker() != null) {
                object.put("speaker", segment.getSpeaker());
            }
            object.put("start", segment.getStartTime());
            object.put("end", segment.getEndTime());
            JSONArray words = new JSONArray();
            for (TranscriptWord word : segment.getWordTimings()) {
                JSONArray wordJson = new JSONArray();
                wordJson.put(segment.getWords().substring(
                        word.getStartChar(), word.getEndChar()));
                wordJson.put(word.getStartTime());
                wordJson.put(word.getEndTime());
                wordJson.put(word.getStartChar());
                wordJson.put(word.getEndChar());
                words.put(wordJson);
            }
            object.put("words", words);
            return object.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to serialize journal segment", e);
        }
    }

    static TranscriptSegment fromJson(String line) throws JSONException {
        JSONObject object = new JSONObject(line);
        String text = object.getString("text");
        long start = object.getLong("start");
        long end = object.getLong("end");
        String speaker = object.optString("speaker", null);
        List<TranscriptWord> words = new ArrayList<>();
        JSONArray wordArray = object.optJSONArray("words");
        if (wordArray != null) {
            for (int i = 0; i < wordArray.length(); i++) {
                JSONArray word = wordArray.getJSONArray(i);
                words.add(new TranscriptWord(word.getInt(3), word.getInt(4),
                        word.getLong(1), word.getLong(2)));
            }
        }
        return new TranscriptSegment(start, end, text, speaker, words);
    }
}
