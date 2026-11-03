package de.danoeh.antennapod.ad;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Streams an enclosure into the app cache, following redirects. */
final class SeedDownloader {
    private static final int TIMEOUT_MS = 30_000;

    private SeedDownloader() {
    }

    static File download(String url, File dir, String fileName) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File out = new File(dir, fileName);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AntennaPod");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                 FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out;
        } finally {
            connection.disconnect();
        }
    }

    /** Fetches at most maxBytes from the start; servers that ignore Range are cut off locally. */
    static File downloadHead(String url, File dir, String fileName, long maxBytes) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File out = new File(dir, fileName);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AntennaPod");
            connection.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                 FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while (total < maxBytes && (n = in.read(buf)) > 0) {
                    int write = (int) Math.min(n, maxBytes - total);
                    os.write(buf, 0, write);
                    total += write;
                }
            }
            return out;
        } finally {
            connection.disconnect();
        }
    }

    /** Bytes needed to cover a prefix of the given duration, clamped to a sane range. */
    static long headBytes(long size, long durationMs, int seconds) {
        if (size <= 0 || durationMs <= 0) {
            return 4L * 1024 * 1024;
        }
        long bytes = size * seconds * 1000L / durationMs;
        return Math.max(512L * 1024, Math.min(8L * 1024 * 1024, bytes));
    }
}
