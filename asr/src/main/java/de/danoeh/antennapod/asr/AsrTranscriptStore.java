package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.parser.transcript.JsonTranscriptParser;

/** Persists the word-JSON transcript and its ASR checkpoint in one atomically replaced file. */
public final class AsrTranscriptStore {
    private final File transcriptFile;

    public AsrTranscriptStore(@NonNull File transcriptFile) {
        this.transcriptFile = transcriptFile;
    }

    public boolean hasTranscript() {
        return transcriptFile.isFile() && transcriptFile.length() > 0;
    }

    public void save(@NonNull List<AsrSegment> segments, long audioDurationMs,
                     @NonNull List<MediaRange> coverage, @NonNull String modelId) throws IOException {
        writeAtomically(transcriptFile,
                AsrTranscriptWriter.write(segments, audioDurationMs, modelId, coverage));
    }

    @NonNull
    public List<AsrSegment> loadSegments() throws IOException {
        String json = read(transcriptFile);
        Transcript transcript = JsonTranscriptParser.parse(json);
        if (transcript == null) {
            throw new IOException("invalid generated transcript");
        }
        return AsrTranscriptConverter.fromTranscript(transcript);
    }

    @NonNull
    public GeneratedTranscriptCheckpoint loadCheckpoint() throws IOException {
        try {
            return GeneratedTranscriptCheckpoint.fromJson(read(transcriptFile));
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid generated checkpoint", e);
        }
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
    }

    private static void writeAtomically(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temp = new File(target.getPath() + ".tmp");
        try (OutputStream output = new FileOutputStream(temp)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("cannot install " + target);
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
