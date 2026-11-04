package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import androidx.media3.common.C;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
public class CacheAudioSourceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsFromCachedSpanAtTheRequestedOffset() throws IOException {
        File file = writeSpan("0123456789");
        FakeCache cache = new FakeCache(10,
                new CacheSpan("key", 0, 10, 0, file));
        MediaDataSource dataSource = new CacheAudioSource(cache, "key", 1000).open();
        byte[] buffer = new byte[4];

        assertEquals(4, dataSource.readAt(3, buffer, 0, 4));
        assertEquals("3456", new String(buffer, 0, 4, StandardCharsets.UTF_8));
        assertEquals(2, dataSource.readAt(8, buffer, 0, 4));
        assertEquals("89", new String(buffer, 0, 2, StandardCharsets.UTF_8));
        assertEquals(-1, dataSource.readAt(10, buffer, 0, 4));
        assertEquals(10, dataSource.getSize());
    }

    @Test
    public void openEndedSpanUsesTheCurrentFileLength() throws IOException {
        File file = writeSpan("0123");
        FakeCache cache = new FakeCache(10,
                new CacheSpan("key", 0, C.LENGTH_UNSET, 0, file));
        MediaDataSource dataSource = new CacheAudioSource(cache, "key", 1000).open();
        byte[] buffer = new byte[4];

        assertEquals(2, dataSource.readAt(2, buffer, 0, 3));
        assertEquals("23", new String(buffer, 0, 2, StandardCharsets.UTF_8));
    }

    @Test
    public void missingContentLengthTimesOut() {
        FakeCache cache = new FakeCache(C.LENGTH_UNSET);

        assertThrows(IOException.class, () -> new CacheAudioSource(cache, "key", 100).open());
    }

    @Test
    public void missingSpanTimesOutOnRead() throws IOException {
        FakeCache cache = new FakeCache(10);
        MediaDataSource dataSource = new CacheAudioSource(cache, "key", 100).open();

        assertThrows(IOException.class, () -> dataSource.readAt(0, new byte[2], 0, 2));
    }

    @Test
    public void spanAfterTheRequestedPositionIsIgnored() throws IOException {
        File file = writeSpan("456");
        FakeCache cache = new FakeCache(10,
                new CacheSpan("key", 4, 3, 0, file));
        MediaDataSource dataSource = new CacheAudioSource(cache, "key", 100).open();
        byte[] buffer = new byte[4];

        assertEquals(3, dataSource.readAt(4, buffer, 0, 4));
        assertEquals("456", new String(buffer, 0, 3, StandardCharsets.UTF_8));
    }

    private File writeSpan(String content) throws IOException {
        File file = folder.newFile("span-" + System.nanoTime());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static final class FakeCache implements Cache {
        private final long contentLength;
        private final List<CacheSpan> spans = new ArrayList<>();

        FakeCache(long contentLength, CacheSpan... spans) {
            this.contentLength = contentLength;
            Collections.addAll(this.spans, spans);
        }

        @Override
        public long getUid() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NavigableSet<CacheSpan> addListener(String key, Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeListener(String key, Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NavigableSet<CacheSpan> getCachedSpans(String key) {
            TreeSet<CacheSpan> result = new TreeSet<>();
            for (CacheSpan span : spans) {
                if (span.key.equals(key)) {
                    result.add(span);
                }
            }
            return result;
        }

        @Override
        public Set<String> getKeys() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getCacheSpace() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CacheSpan startReadWrite(String key, long position, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CacheSpan startReadWriteNonBlocking(String key, long position, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File startFile(String key, long position, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commitFile(File file, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseHoleSpan(CacheSpan holeSpan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeResource(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeSpan(CacheSpan span) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCached(String key, long position, long length) {
            return !getCachedSpans(key).isEmpty();
        }

        @Override
        public long getCachedLength(String key, long position, long length) {
            return getCachedSpans(key).isEmpty() ? 0 : length;
        }

        @Override
        public long getCachedBytes(String key, long position, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyContentMetadataMutations(String key, ContentMetadataMutations mutations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContentMetadata getContentMetadata(String key) {
            return new Metadata(contentLength);
        }
    }

    private static final class Metadata implements ContentMetadata {
        private final long contentLength;

        Metadata(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public byte[] get(String key, byte[] defaultValue) {
            return defaultValue;
        }

        @Override
        public String get(String key, String defaultValue) {
            return defaultValue;
        }

        @Override
        public long get(String key, long defaultValue) {
            return ContentMetadata.KEY_CONTENT_LENGTH.equals(key) ? contentLength : defaultValue;
        }

        @Override
        public boolean contains(String key) {
            return ContentMetadata.KEY_CONTENT_LENGTH.equals(key);
        }
    }
}
