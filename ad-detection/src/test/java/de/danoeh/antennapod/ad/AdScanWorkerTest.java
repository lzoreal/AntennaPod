package de.danoeh.antennapod.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AdScanWorkerTest {
    @Test
    public void seedFileNamesDifferForEpisodesSharingTheSamePath() {
        String a = "https://cdn.example/audio.mp3?utm_source=Podcast&in_playlist=1";
        String b = "https://cdn.example/audio.mp3?utm_source=Podcast&in_playlist=2";
        assertNotEquals(AdScanWorker.fileName(11127, a), AdScanWorker.fileName(11128, b));
    }

    @Test
    public void seedFileNameKeepsExtensionAndDropsQuery() {
        assertEquals("42-episode.mp3",
                AdScanWorker.fileName(42, "https://cdn.example/episode.mp3?token=abc"));
    }
}
