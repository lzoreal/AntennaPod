package de.danoeh.antennapod.ui.screen.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class AsrFinalizeWorkerTest {
    private static final long DURATION_MS = 60_000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void coverageReachingTheEndIsComplete() throws Exception {
        assertTrue(AsrFinalizeWorker.isComplete(write(56_000), DURATION_MS));
    }

    @Test
    public void coverageStoppingEarlyIsIncomplete() throws Exception {
        assertFalse(AsrFinalizeWorker.isComplete(write(54_000), DURATION_MS));
    }

    @Test
    public void missingTranscriptIsIncomplete() {
        assertFalse(AsrFinalizeWorker.isComplete(
                new File(folder.getRoot(), "missing.transcript"), DURATION_MS));
    }

    @Test
    public void unknownDurationIsIncomplete() throws Exception {
        assertFalse(AsrFinalizeWorker.isComplete(write(56_000), 0));
    }

    private File write(long coverageEndMs) throws Exception {
        File file = folder.newFile("episode.transcript");
        String json = "{\"version\":1,\"model\":\"test\",\"audio_seconds\":60,"
                + "\"coverage\":[[0," + coverageEndMs + "]],\"segments\":[]}";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
