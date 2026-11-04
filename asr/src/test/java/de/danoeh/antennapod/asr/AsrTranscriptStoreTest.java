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
        assertEquals(Collections.singletonList(new MediaRange(0, 1000)),
                checkpoint.getCoverage());
        assertFalse(new File(file.getPath() + ".tmp").exists());
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
