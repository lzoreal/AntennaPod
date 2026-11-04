package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.ContentMetadata;

import java.io.IOException;

/** Reads an audio resource from the player's SimpleCache without downloading it again. */
@OptIn(markerClass = UnstableApi.class)
public final class CacheAudioSource implements AudioSource {
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30000;

    private final Cache cache;
    private final String key;
    private final long waitTimeoutMs;

    public CacheAudioSource(@NonNull Cache cache, @NonNull String key) {
        this(cache, key, DEFAULT_WAIT_TIMEOUT_MS);
    }

    public CacheAudioSource(@NonNull Cache cache, @NonNull String key, long waitTimeoutMs) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key is empty");
        }
        if (waitTimeoutMs <= 0) {
            throw new IllegalArgumentException("waitTimeoutMs must be positive: " + waitTimeoutMs);
        }
        this.cache = cache;
        this.key = key;
        this.waitTimeoutMs = waitTimeoutMs;
    }

    @NonNull
    @Override
    public CacheMediaDataSource open() throws IOException {
        long contentLength = waitForContentLength();
        return new CacheMediaDataSource(cache, key, contentLength, waitTimeoutMs);
    }

    private long waitForContentLength() throws IOException {
        long deadline = System.nanoTime() + waitTimeoutMs * 1_000_000L;
        while (true) {
            long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
            if (length != C.LENGTH_UNSET && length > 0) {
                return length;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("cache has no content length for " + key);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for cache", e);
            }
        }
    }
}
