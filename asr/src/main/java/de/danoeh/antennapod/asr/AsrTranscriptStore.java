package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.parser.transcript.JsonTranscriptParser;
import de.danoeh.antennapod.parser.transcript.TranscriptJournal;

/** Persists the word-JSON transcript and its ASR checkpoint in one atomically replaced file. */
public final class AsrTranscriptStore {
    /** Word ranges separated by a short pause still describe one decoded stretch. */
    private static final long COVERAGE_MERGE_GAP_MS = 2000;

    private final File transcriptFile;

    public AsrTranscriptStore(@NonNull File transcriptFile) {
        this.transcriptFile = transcriptFile;
    }

    public boolean hasTranscript() {
        if (transcriptFile.isFile() && transcriptFile.length() > 0) {
            return true;
        }
        return hasJournal();
    }

    public boolean hasJournal() {
        File journal = TranscriptJournal.fileFor(transcriptFile);
        return journal.isFile() && journal.length() > 0;
    }

    /** Appends one finalized segment without rewriting the whole snapshot. */
    public void append(@NonNull AsrSegment segment) throws IOException {
        TranscriptJournal.append(transcriptFile, segment.toTranscriptSegment(null));
    }

    public void save(@NonNull List<AsrSegment> segments, long audioDurationMs,
                     @NonNull List<MediaRange> coverage, @NonNull String modelId) throws IOException {
        save(segments, audioDurationMs, coverage, modelId, 0, null);
    }

    public void save(@NonNull List<AsrSegment> segments, long audioDurationMs,
                     @NonNull List<MediaRange> coverage, @NonNull String modelId,
                     long audioBytes, String fingerprint) throws IOException {
        save(segments, audioDurationMs, coverage, modelId, audioBytes, fingerprint,
                Collections.emptyList());
    }

    public void save(@NonNull List<AsrSegment> segments, long audioDurationMs,
                     @NonNull List<MediaRange> coverage, @NonNull String modelId,
                     long audioBytes, String fingerprint,
                     @NonNull List<MediaRange> scanned) throws IOException {
        writeAtomically(transcriptFile, AsrTranscriptWriter.write(segments, audioDurationMs,
                modelId, coverage, audioBytes, fingerprint, scanned));
        File source = new File(transcriptFile.getPath() + ".source");
        if (!source.exists()) {
            writeAtomically(source, "generated");
        }
        // The snapshot now holds every journal segment; dropping the journal is safe because a
        // crash between the two steps only replays segments that are already in the snapshot.
        TranscriptJournal.clear(transcriptFile);
    }

    @NonNull
    public List<AsrSegment> loadSegments() throws IOException {
        return AsrTranscriptConverter.fromTranscript(loadTranscript());
    }

    @NonNull
    private Transcript loadTranscript() throws IOException {
        Transcript transcript;
        if (transcriptFile.isFile()) {
            String json = read(transcriptFile);
            transcript = JsonTranscriptParser.parse(json);
            if (transcript == null) {
                if (!isEmptySnapshot(json)) {
                    throw new IOException("invalid generated transcript");
                }
                // A session without any recognized words still writes a valid empty snapshot;
                // its journal must still be replayed.
                transcript = new Transcript();
            }
        } else if (hasJournal()) {
            transcript = new Transcript();
        } else {
            throw new IOException("file is missing: " + transcriptFile);
        }
        addJournalSegments(transcript);
        return transcript;
    }

    private static boolean isEmptySnapshot(String json) {
        try {
            JSONArray segments = new JSONObject(json).optJSONArray("segments");
            return segments != null && segments.length() == 0;
        } catch (JSONException e) {
            return false;
        }
    }

    @NonNull
    public GeneratedTranscriptCheckpoint loadCheckpoint() throws IOException {
        GeneratedTranscriptCheckpoint stored;
        try {
            stored = GeneratedTranscriptCheckpoint.fromJson(read(transcriptFile));
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid generated checkpoint", e);
        }
        return new GeneratedTranscriptCheckpoint(stored.getModelId(), stored.getAudioDurationMs(),
                coverageOf(loadSegments()), stored.getScanned(), stored.getAudioBytes(),
                stored.getFingerprint());
    }

    /**
     * Coverage must never claim audio that has no persisted words, otherwise a process death
     * skips that audio forever. Deriving it from the snapshot (plus journal) keeps the two in
     * step; adjacent word ranges are merged across short pauses.
     */
    @NonNull
    public static List<MediaRange> coverageOf(@NonNull List<AsrSegment> segments) {
        long start = -1;
        long end = -1;
        List<MediaRange> ranges = new ArrayList<>();
        for (AsrSegment segment : segments) {
            for (AsrWord word : segment.getWords()) {
                long wordStart = word.getStartTime();
                long wordEnd = word.getEndTime();
                if (wordEnd <= wordStart) {
                    continue;
                }
                if (start < 0) {
                    start = wordStart;
                    end = wordEnd;
                } else if (wordStart <= end + COVERAGE_MERGE_GAP_MS) {
                    end = Math.max(end, wordEnd);
                } else {
                    ranges.add(new MediaRange(start, end));
                    start = wordStart;
                    end = wordEnd;
                }
            }
        }
        if (start >= 0) {
            ranges.add(new MediaRange(start, end));
        }
        return ranges;
    }

    private void addJournalSegments(Transcript transcript) {
        TranscriptJournal.applyTo(transcript, transcriptFile);
    }

    @NonNull
    public List<AsrSegment> loadSegmentsOrEmpty() {
        try {
            return loadSegments();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public void delete() throws IOException {
        if (transcriptFile.exists() && !transcriptFile.delete()) {
            throw new IOException("cannot delete " + transcriptFile);
        }
        TranscriptJournal.clear(transcriptFile);
    }

    private static void writeAtomically(File target, String content) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temp = File.createTempFile("transcript-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
                // The journal is cleared right after this snapshot is installed; without the
                // sync a power loss could leave a truncated snapshot and no journal to replay.
                output.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                // Some file systems refuse to replace an existing target.
                if (!target.delete() || !temp.renameTo(target)) {
                    throw new IOException("cannot install " + target);
                }
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static String read(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("file is missing: " + file);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
