package de.danoeh.antennapod.model.feed;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import static de.danoeh.antennapod.model.feed.FeedItemMother.anyFeedItemWithImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FeedItemTest {

    private static final String TEXT_LONG = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";
    private static final String TEXT_SHORT = "Lorem ipsum";

    private FeedItem original;
    private FeedItem changedFeedItem;

    @Before
    public void setUp() {
        original = anyFeedItemWithImage();
        changedFeedItem = anyFeedItemWithImage();
    }

    @Test
    public void testUpdateFromOther_feedItemImageDownloadUrlChanged() {
        setNewFeedItemImageDownloadUrl();
        original.updateFromOther(changedFeedItem);
        assertFeedItemImageWasUpdated();
    }

    @Test
    public void testUpdateFromOther_feedItemImageRemoved() {
        feedItemImageRemoved();
        original.updateFromOther(changedFeedItem);
        assertFeedItemImageWasNotUpdated();
    }

    @Test
    public void testUpdateFromOther_feedItemImageAdded() {
        original.setImageUrl(null);
        setNewFeedItemImageDownloadUrl();
        original.updateFromOther(changedFeedItem);
        assertFeedItemImageWasUpdated();
    }

    @Test
    public void testUpdateFromOther_dateChanged() throws Exception {
        Date originalDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("1952-03-11 00:00:00");
        Date changedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("1952-03-11 00:42:42");
        original.setPubDate(originalDate);
        changedFeedItem.setPubDate(changedDate);
        original.updateFromOther(changedFeedItem);
        assertEquals(changedDate.getTime(), original.getPubDate().getTime());
    }

    @Test
    public void testUpdateFromOther_transcriptUrlChanged_clearsParsedTranscript() {
        original.setTranscriptUrl("text/vtt", "https://example.com/old.vtt");
        original.setTranscript(new Transcript());
        changedFeedItem.setTranscriptUrl("text/vtt", "https://example.com/new.vtt");

        original.updateFromOther(changedFeedItem);

        assertEquals("https://example.com/new.vtt", original.getTranscriptUrl());
        assertEquals("text/vtt", original.getTranscriptType());
        assertNull(original.getTranscript());
    }

    @Test
    public void testUpdateFromOther_transcriptUrlUnchanged_keepsParsedTranscript() {
        Transcript transcript = new Transcript();
        original.setTranscriptUrl("text/vtt", "https://example.com/same.vtt");
        original.setTranscript(transcript);
        changedFeedItem.setTranscriptUrl("text/vtt", "https://example.com/same.vtt");

        original.updateFromOther(changedFeedItem);

        assertSame(transcript, original.getTranscript());
    }

    @Test
    public void testUpdateFromOther_transcriptTypeChanged_clearsParsedTranscript() {
        original.setTranscriptUrl("text/vtt", "https://example.com/same.vtt");
        original.setTranscript(new Transcript());
        changedFeedItem.setTranscriptUrl("application/json", "https://example.com/same.vtt");

        original.updateFromOther(changedFeedItem);

        assertEquals("application/json", original.getTranscriptType());
        assertNull(original.getTranscript());
    }

    @Test
    public void testSerializationIgnoresParsedTranscript() throws Exception {
        original.setTranscript(new Transcript());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ObjectOutputStream(bytes).writeObject(original);

        FeedItem restored = (FeedItem) new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray())).readObject();

        assertNull(restored.getTranscript());
    }

    /**
     * Test that a played item loses that state after being marked as new.
     */
    @Test
    public void testMarkPlayedItemAsNew_itemNotPlayed() {
        original.setPlayed(true);
        original.setNew();

        assertFalse(original.isPlayed());
    }

    /**
     * Test that a new item loses that state after being marked as played.
     */
    @Test
    public void testMarkNewItemAsPlayed_itemNotNew() {
        original.setNew();
        original.setPlayed(true);

        assertFalse(original.isNew());
    }

    /**
     * Test that a new item loses that state after being marked as not played.
     */
    @Test
    public void testMarkNewItemAsNotPlayed_itemNotNew() {
        original.setNew();
        original.setPlayed(false);

        assertFalse(original.isNew());
    }

    private void setNewFeedItemImageDownloadUrl() {
        changedFeedItem.setImageUrl("http://example.com/new_picture");
    }

    private void feedItemImageRemoved() {
        changedFeedItem.setImageUrl(null);
    }

    private void assertFeedItemImageWasUpdated() {
        assertEquals(original.getImageUrl(), changedFeedItem.getImageUrl());
    }

    private void assertFeedItemImageWasNotUpdated() {
        assertEquals(anyFeedItemWithImage().getImageUrl(), original.getImageUrl());
    }

    /**
     * If one of `description` or `content:encoded` is null, use the other one.
     */
    @Test
    public void testShownotesNullValues() {
        testShownotes(null, TEXT_LONG);
        testShownotes(TEXT_LONG, null);
    }

    /**
     * If `description` is reasonably longer than `content:encoded`, use `description`.
     */
    @Test
    public void testShownotesLength() {
        testShownotes(TEXT_SHORT, TEXT_LONG);
        testShownotes(TEXT_LONG, TEXT_SHORT);
    }

    /**
     * Checks if the shownotes equal TEXT_LONG, using the given `description` and `content:encoded`.
     *
     * @param description Description of the feed item
     * @param contentEncoded `content:encoded` of the feed item
     */
    private void testShownotes(String description, String contentEncoded) {
        FeedItem item = new FeedItem();
        item.setDescriptionIfLonger(description);
        item.setDescriptionIfLonger(contentEncoded);
        assertEquals(TEXT_LONG, item.getDescription());
    }
}
