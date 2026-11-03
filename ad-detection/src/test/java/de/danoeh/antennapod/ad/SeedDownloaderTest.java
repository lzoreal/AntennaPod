package de.danoeh.antennapod.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeedDownloaderTest {
    @Test
    public void headBytesScaleWithBitrate() {
        long size = 54_846_348L;
        long durationMs = 3_425_338L;
        long bytes = SeedDownloader.headBytes(size, durationMs, 180);
        assertEquals(size * 180_000 / durationMs, bytes);
    }

    @Test
    public void headBytesFallBackWhenSizeUnknown() {
        assertEquals(4L * 1024 * 1024, SeedDownloader.headBytes(0, 0, 180));
    }

    @Test
    public void headBytesAreCapped() {
        assertEquals(8L * 1024 * 1024, SeedDownloader.headBytes(2L * 1024 * 1024 * 1024, 3_600_000, 180));
    }

    @Test
    public void headBytesNeverDropBelowMinimum() {
        assertEquals(512L * 1024, SeedDownloader.headBytes(1000, 3_600_000, 180));
    }
}
