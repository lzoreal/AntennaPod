package de.danoeh.antennapod.model.feed;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static de.danoeh.antennapod.model.feed.FeedMediaMother.anyFeedMedia;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FeedMediaTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FeedMedia media;

    @Before
    public void setUp() {
        media = anyFeedMedia();
    }

    @Test
    public void testDeleteTranscriptFiles() throws Exception {
        media.setLocalFileUrl(new File(temporaryFolder.getRoot(), "episode.mp3").getAbsolutePath());
        File transcriptFile = new File(media.getTranscriptFileUrl());
        File metadataFile = new File(media.getTranscriptMetadataFileUrl());
        File tempFile = new File(media.getTranscriptFileUrl() + ".tmp");
        Files.write(transcriptFile.toPath(), "transcript".getBytes(StandardCharsets.UTF_8));
        Files.write(metadataFile.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        Files.write(tempFile.toPath(), "temp".getBytes(StandardCharsets.UTF_8));

        media.deleteTranscriptFiles();

        assertFalse(transcriptFile.exists());
        assertFalse(metadataFile.exists());
        assertFalse(tempFile.exists());
    }

    /**
     * Downloading a media from a not new and not played item should not change the item state.
     */
    @Test
    public void testDownloadMediaOfNotNewAndNotPlayedItem_unchangedItemState() {
        FeedItem item = mock(FeedItem.class);
        when(item.isNew()).thenReturn(false);
        when(item.isPlayed()).thenReturn(false);

        media.setItem(item);
        media.setDownloaded(true, System.currentTimeMillis());

        verify(item, never()).setNew();
        verify(item, never()).setPlayed(true);
        verify(item, never()).setPlayed(false);
    }

    /**
     * Downloading a media from a played item (thus not new) should not change the item state.
     */
    @Test
    public void testDownloadMediaOfPlayedItem_unchangedItemState() {
        FeedItem item = mock(FeedItem.class);
        when(item.isNew()).thenReturn(false);
        when(item.isPlayed()).thenReturn(true);

        media.setItem(item);
        media.setDownloaded(true, System.currentTimeMillis());

        verify(item, never()).setNew();
        verify(item, never()).setPlayed(true);
        verify(item, never()).setPlayed(false);
    }

    /**
     * Downloading a media from a new item (thus not played) should change the item to not played.
     */
    @Test
    public void testDownloadMediaOfNewItem_changedToNotPlayedItem() {
        FeedItem item = mock(FeedItem.class);
        when(item.isNew()).thenReturn(true);
        when(item.isPlayed()).thenReturn(false);

        media.setItem(item);
        media.setDownloaded(true, System.currentTimeMillis());

        verify(item).setPlayed(false);
        verify(item, never()).setNew();
        verify(item, never()).setPlayed(true);
    }

}
