package de.danoeh.antennapod.ui.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;

@RunWith(RobolectricTestRunner.class)
public class TranscriptUtilsTest {
    private static final String VTT = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nHello";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        AntennapodHttpClient.setCacheDirectory(temporaryFolder.newFolder());
        AntennapodHttpClient.reinit();
    }

    @Test
    public void testStoreWritesSourceMetadata() throws Exception {
        FeedMedia media = media(1, "text/vtt", "https://example.com/one.vtt");
        TranscriptUtils.storeTranscript(media, VTT);

        File metadataFile = new File(media.getTranscriptMetadataFileUrl());
        assertTrue(metadataFile.exists());
        assertEquals("text/vtt\nhttps://example.com/one.vtt",
                FileUtils.readFileToString(metadataFile, StandardCharsets.UTF_8));
        Transcript transcript = TranscriptUtils.loadTranscript(media, false);
        assertNotNull(transcript);
        assertEquals(1, transcript.getSegmentCount());
    }

    @Test
    public void testStoreReplacesExistingTranscriptWithoutTempFile() throws Exception {
        FeedMedia media = media(7, "text/vtt", "https://example.com/seven.vtt");
        TranscriptUtils.storeTranscript(media, VTT);
        String replacement = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nReplacement";

        TranscriptUtils.storeTranscript(media, replacement);

        File transcriptFile = new File(media.getTranscriptFileUrl());
        assertEquals(replacement, FileUtils.readFileToString(
                transcriptFile, StandardCharsets.UTF_8));
        assertFalse(new File(transcriptFile.getPath() + ".tmp").exists());
    }

    @Test
    public void testInvalidTranscriptIsNotStored() throws Exception {
        FeedMedia media = media(8, "text/vtt", "https://example.com/eight.vtt");

        TranscriptUtils.storeTranscript(media, "<html>not a transcript</html>");

        assertFalse(new File(media.getTranscriptFileUrl()).exists());
        assertFalse(new File(media.getTranscriptMetadataFileUrl()).exists());
    }

    @Test
    public void testStaleLocalTranscriptIsIgnored() throws Exception {
        FeedMedia oldMedia = media(2, "text/vtt", "https://example.com/old.vtt");
        TranscriptUtils.storeTranscript(oldMedia, VTT);

        FeedMedia mediaWithoutSource = media(2, null, null);
        assertNull(TranscriptUtils.loadTranscript(mediaWithoutSource, false));
    }

    @Test
    public void testLegacyLocalTranscriptIsParsedAndAdopted() throws Exception {
        FeedMedia media = media(3, "text/vtt", "https://example.com/legacy.vtt");
        File transcriptFile = new File(media.getTranscriptFileUrl());
        FileUtils.writeStringToFile(transcriptFile, VTT, StandardCharsets.UTF_8);
        assertFalse(new File(media.getTranscriptMetadataFileUrl()).exists());

        Transcript transcript = TranscriptUtils.loadTranscript(media, false);

        assertNotNull(transcript);
        assertTrue(new File(media.getTranscriptMetadataFileUrl()).exists());
    }

    @Test
    public void testUnparseableLocalFileFetchesCurrentUrl() throws Exception {
        TranscriptServer server = new TranscriptServer(VTT);
        try {
            String url = "http://127.0.0.1:" + server.getListeningPort() + "/transcript.vtt";
            FeedMedia media = media(4, "text/vtt", url);
            File transcriptFile = new File(media.getTranscriptFileUrl());
            FileUtils.writeStringToFile(transcriptFile, "<html>not a transcript</html>",
                    StandardCharsets.UTF_8);
            FileUtils.writeStringToFile(new File(media.getTranscriptMetadataFileUrl()),
                    "text/vtt\n" + url, StandardCharsets.UTF_8);

            Transcript transcript = TranscriptUtils.loadTranscript(media, false);

            assertNotNull(transcript);
            assertEquals("Hello", transcript.getSegmentAt(0).getWords());
            assertEquals(VTT, FileUtils.readFileToString(
                    transcriptFile, StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
    }

    private static class TranscriptServer extends NanoHTTPD {
        private final String body;

        TranscriptServer(String body) throws IOException {
            super("127.0.0.1", 0);
            this.body = body;
            start();
        }

        @Override
        public Response serve(IHTTPSession session) {
            return new Response(Response.Status.OK, "text/vtt", body);
        }
    }

    @Test
    public void testOversizedLocalTranscriptIsIgnored() throws Exception {
        FeedMedia media = media(6, "text/vtt", null);
        File transcriptFile = new File(media.getTranscriptFileUrl());
        FileUtils.writeStringToFile(transcriptFile, VTT + "x".repeat(5 * 1024 * 1024),
                StandardCharsets.UTF_8);

        assertNull(TranscriptUtils.loadTranscript(media, false));
    }

    @Test
    public void testForcedRefreshFailureFallsBackToCurrentLocalFile() throws Exception {
        FeedMedia media = media(5, "text/vtt", "http://127.0.0.1:1/missing.vtt");
        TranscriptUtils.storeTranscript(media, VTT);

        Transcript transcript = TranscriptUtils.loadTranscript(media, true);

        assertNotNull(transcript);
        assertEquals("Hello", transcript.getSegmentAt(0).getWords());
    }

    private FeedMedia media(long id, String type, String url) throws Exception {
        FeedItem item = new FeedItem();
        item.setId(id);
        if (url != null) {
            item.setTranscriptUrl(type, url);
        }
        FeedMedia media = new FeedMedia(item, "https://example.com/audio.mp3", 1, "audio/mpeg");
        media.setId(id);
        media.setLocalFileUrl(new File(temporaryFolder.getRoot(), "episode-" + id + ".mp3")
                .getAbsolutePath());
        return media;
    }
}
