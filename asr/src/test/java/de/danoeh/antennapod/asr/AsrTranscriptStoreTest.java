package de.danoeh.antennapod.asr;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AsrTranscriptStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void saveAndLoadRoundTripsSegmentsAndCheckpoint() throws IOException {
        File file = new File(folder.getRoot(), "episode.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        AsrSegment first = new AsrSegment("hello world", Arrays.asList(
                new AsrWord("hello", 100, 300, 0, 5),
                new AsrWord("world", 350, 500, 6, 11)));
        AsrSegment second = new AsrSegment("你好", Arrays.asList(
                new AsrWord("你", 600, 700, 0, 1),
                new AsrWord("好", 700, 1000, 1, 2)));

        store.save(Arrays.asList(first, second), 12500,
                Collections.singletonList(new MediaRange(0, 1000)), "zh-14m");

        assertTrue(store.hasTranscript());
        List<AsrSegment> segments = store.loadSegments();
        assertEquals(2, segments.size());
        assertEquals("hello world", segments.get(0).getText());
        assertEquals("world", segments.get(0).getWords().get(1).getText());
        assertEquals(350, segments.get(0).getWords().get(1).getStartTime());
        assertEquals("你好", segments.get(1).getText());
        assertEquals(2, segments.get(1).getWords().size());

        GeneratedTranscriptCheckpoint checkpoint = store.loadCheckpoint();
        assertEquals("zh-14m", checkpoint.getModelId());
        assertEquals(12500, checkpoint.getAudioDurationMs());
        // Coverage is derived from persisted words, not from the ranges handed to save().
        assertEquals(Collections.singletonList(new MediaRange(100, 1000)),
                checkpoint.getCoverage());
        assertFalse(new File(file.getPath() + ".tmp").exists());
    }

    @Test
    public void journalSegmentsAreLoadedAndCompactedBySave() throws IOException {
        File file = new File(folder.getRoot(), "journal.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        AsrSegment first = segment("hello", 0, 500);
        store.save(Collections.singletonList(first), 5000,
                Collections.singletonList(new MediaRange(0, 500)), "en");
        AsrSegment second = segment("world", 600, 900);

        store.append(second);

        assertTrue(store.hasTranscript());
        assertEquals(2, store.loadSegments().size());
        assertEquals(Collections.singletonList(new MediaRange(0, 900)),
                store.loadCheckpoint().getCoverage());
        File journal = new File(file.getPath() + ".journal");
        assertTrue(journal.exists());

        store.save(store.loadSegments(), 5000,
                Collections.singletonList(new MediaRange(0, 900)), "en");

        assertFalse(journal.exists());
        assertEquals(2, store.loadSegments().size());
    }

    @Test
    public void journalReplayDoesNotDuplicateSegmentsAlreadyInSnapshot() throws IOException {
        File file = new File(folder.getRoot(), "duplicate.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        AsrSegment first = segment("hello", 0, 500);
        AsrSegment second = segment("world", 600, 900);
        store.save(Arrays.asList(first, second), 5000,
                Collections.singletonList(new MediaRange(0, 900)), "en");

        // Simulate a crash after the snapshot was replaced but before the journal was cleared.
        store.append(second);

        assertEquals(2, store.loadSegments().size());
    }

    @Test
    public void tornJournalTailIsIgnored() throws IOException {
        File file = new File(folder.getRoot(), "torn.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        store.save(Collections.singletonList(segment("hello", 0, 500)), 5000,
                Collections.singletonList(new MediaRange(0, 500)), "en");
        store.append(segment("world", 600, 900));
        try (FileOutputStream output = new FileOutputStream(
                new File(file.getPath() + ".journal"), true)) {
            output.write("{\"text\":\"torn".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(2, store.loadSegments().size());
    }

    @Test
    public void emptySnapshotStillReplaysJournal() throws IOException {
        File file = new File(folder.getRoot(), "empty.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        store.save(Collections.emptyList(), 5000, Collections.emptyList(), "en");
        store.append(segment("world", 600, 900));

        List<AsrSegment> segments = store.loadSegments();

        assertEquals(1, segments.size());
        assertEquals("world", segments.get(0).getText());
    }

    @Test
    public void scannedRangesSurviveCheckpointRoundTrip() throws IOException {
        File file = new File(folder.getRoot(), "scanned.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        store.save(Collections.singletonList(segment("hello", 0, 500)), 5000,
                Collections.singletonList(new MediaRange(0, 500)), "en", 0, null,
                Arrays.asList(new MediaRange(0, 500), new MediaRange(500, 5000)));

        assertEquals(Arrays.asList(new MediaRange(0, 500), new MediaRange(500, 5000)),
                store.loadCheckpoint().getScanned());
    }

    private static AsrSegment segment(String text, long start, long end) {
        return new AsrSegment(text, Collections.singletonList(
                new AsrWord(text, start, end, 0, text.length())));
    }

    @Test
    public void missingFileLoadsEmptySegments() {
        AsrTranscriptStore store = new AsrTranscriptStore(
                new File(folder.getRoot(), "missing.transcript"));

        assertTrue(store.loadSegmentsOrEmpty().isEmpty());
        assertFalse(store.hasTranscript());
        assertThrows(IOException.class, store::loadSegments);
    }

    @Test
    public void deleteRemovesTheFile() throws IOException {
        File file = new File(folder.getRoot(), "episode.transcript");
        AsrTranscriptStore store = new AsrTranscriptStore(file);
        store.save(Collections.singletonList(new AsrSegment("hello",
                        Collections.singletonList(new AsrWord("hello", 0, 100, 0, 5)))),
                1000, Collections.singletonList(new MediaRange(0, 1000)), "en-20m");

        store.delete();

        assertFalse(store.hasTranscript());
        assertFalse(file.exists());
    }

    @Test
    public void checkpointParseFailureIsAnIoException() throws IOException {
        File file = new File(folder.getRoot(), "plain.transcript");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("{\"segments\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        AsrTranscriptStore store = new AsrTranscriptStore(file);

        assertThrows(IOException.class, store::loadCheckpoint);
    }
}
