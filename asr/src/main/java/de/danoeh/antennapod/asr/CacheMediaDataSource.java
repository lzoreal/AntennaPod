package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Random-access MediaDataSource over the cached spans of one resource. */
@OptIn(markerClass = UnstableApi.class)
public final class CacheMediaDataSource extends MediaDataSource {
    private static final long RETRY_SLEEP_MS = 50;

    private final Cache cache;
    private final String key;
    private final long contentLength;
    private final long waitTimeoutMs;

    CacheMediaDataSource(@NonNull Cache cache, @NonNull String key, long contentLength,
                         long waitTimeoutMs) {
        this.cache = cache;
        this.key = key;
        this.contentLength = contentLength;
        this.waitTimeoutMs = waitTimeoutMs;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (position < 0 || offset < 0 || size < 0 || offset + size > buffer.length) {
            throw new IOException("invalid read window");
        }
        if (size == 0) {
            return 0;
        }
        if (position >= contentLength) {
            return -1;
        }
        int toRead = (int) Math.min(size, contentLength - position);
        long deadline = System.nanoTime() + waitTimeoutMs * 1_000_000L;
        while (true) {
            CacheSpan span = findSpan(position);
            if (span != null && span.file != null) {
                long available = span.isOpenEnded() ? span.file.length() : span.length;
                if (position < span.position + available) {
                    int count = (int) Math.min(toRead, span.position + available - position);
                    int read = readFromFile(span.file, position - span.position, buffer, offset, count);
                    if (read > 0) {
                        return read;
                    }
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AsrRetryableException(
                        "cache did not provide " + position + " of " + key);
            }
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading cache", e);
            }
        }
    }

    @Override
    public long getSize() {
        return contentLength;
    }

    @Override
    public void close() {
    }

    private CacheSpan findSpan(long position) {
        for (CacheSpan span : cache.getCachedSpans(key)) {
            if (!span.isCached || span.file == null) {
                continue;
            }
            long end = span.isOpenEnded() ? Long.MAX_VALUE : span.position + span.length;
            if (position >= span.position && position < end) {
                return span;
            }
        }
        return null;
    }

    private static int readFromFile(File file, long offset, byte[] buffer, int bufferOffset,
                                    int count) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            return input.read(buffer, bufferOffset, count);
        }
    }
}
