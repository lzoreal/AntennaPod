package de.danoeh.antennapod.asr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Downloads one model archive from GitHub, validates it and installs it below the model store. */
public final class ModelDownloader {
    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private static final String TAG = "AsrModelDownloader";
    private static final String USER_AGENT = "AntennaPod-ASR";
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    private final AsrModelStore store;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private volatile boolean cancelled;

    public ModelDownloader(@NonNull AsrModelStore store) {
        this(store, 15000, 30000);
    }

    public ModelDownloader(@NonNull AsrModelStore store, int connectTimeoutMs, int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.store = store;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public void cancel() {
        cancelled = true;
    }

    public void download(@Nullable ProgressListener listener) throws IOException {
        AsrModelDescriptor descriptor = store.descriptor();
        download(descriptor, descriptor.getDownloadUrl(), listener);
    }

    void download(@NonNull AsrModelDescriptor descriptor, @NonNull String url,
                  @Nullable ProgressListener listener) throws IOException {
        cancelled = false;
        File modelDir = new File(store.getRootDir(), descriptor.getId());
        if (isComplete(modelDir, descriptor)) {
            return;
        }
        File tempDir = new File(store.getRootDir(),
                ".download-" + descriptor.getId() + "-" + System.nanoTime());
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            throw new IOException("cannot create " + tempDir);
        }
        try {
            File archive = new File(tempDir, "model.tar.bz2");
            long expected = descriptor.getDownloadSizeBytes();
            downloadFile(url, archive, expected > 0
                    ? expected + 16L * 1024 * 1024 : 0, listener);
            extract(archive, tempDir, descriptor);
            verifyChecksums(tempDir, descriptor);
            install(tempDir, modelDir, descriptor);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static boolean isComplete(File modelDir, AsrModelDescriptor descriptor) {
        for (String name : descriptor.getExpectedFiles()) {
            File file = new File(modelDir, name);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private void downloadFile(String urlString, File target, long maxBytes,
                              @Nullable ProgressListener listener) throws IOException {
        URL current = new URL(urlString);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!"https".equals(current.getProtocol()) && !"http".equals(current.getProtocol())) {
                // A redirect must not downgrade to file:/ftp: and break the connection cast.
                throw new IOException("unsupported URL: " + current);
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int code = connection.getResponseCode();
                if (isRedirect(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("redirect without Location");
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code + " for " + current);
                }
                copyWithProgress(connection, target, contentLength(connection), maxBytes,
                        listener);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private void copyWithProgress(HttpURLConnection connection, File target, long totalBytes,
                                  long maxBytes, @Nullable ProgressListener listener)
            throws IOException {
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancelled) {
                    throw new IOException("download cancelled");
                }
                downloaded += read;
                if (maxBytes > 0 && downloaded > maxBytes) {
                    throw new IOException("model archive larger than expected");
                }
                output.write(buffer, 0, read);
                if (listener != null) {
                    listener.onProgress(downloaded, totalBytes);
                }
            }
        }
    }

    private static void extract(File archive, File targetDir,
                                AsrModelDescriptor descriptor) throws IOException {
        Set<String> expected = new HashSet<>(descriptor.getExpectedFiles());
        Set<String> extracted = new HashSet<>();
        try (InputStream fileInput = new BufferedInputStream(new FileInputStream(archive));
                BZip2CompressorInputStream bzipInput = new BZip2CompressorInputStream(fileInput);
                TarArchiveInputStream tarInput = new TarArchiveInputStream(bzipInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                String name = baseName(entry.getName());
                if (!expected.contains(name) || extracted.contains(name)) {
                    continue;
                }
                File outputFile = new File(targetDir, name);
                if (entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new IOException("archive entry too large: " + name);
                }
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                    copy(tarInput, output);
                }
                extracted.add(name);
            }
        }
        for (String name : descriptor.getExpectedFiles()) {
            if (!extracted.contains(name)) {
                throw new IOException("archive is missing " + name);
            }
        }
    }

    private static void verifyChecksums(File sourceDir, AsrModelDescriptor descriptor)
            throws IOException {
        for (String name : descriptor.getExpectedFiles()) {
            String expected = descriptor.sha256For(name);
            if (expected == null) {
                continue;
            }
            String actual = sha256(new File(sourceDir, name));
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("checksum mismatch for " + name);
            }
        }
    }

    private static void install(File sourceDir, File modelDir, AsrModelDescriptor descriptor)
            throws IOException {
        if (!modelDir.isDirectory() && !modelDir.mkdirs() && !modelDir.isDirectory()) {
            throw new IOException("cannot create " + modelDir);
        }
        for (String name : descriptor.getExpectedFiles()) {
            File source = new File(sourceDir, name);
            File staging = new File(modelDir, name + ".tmp");
            File target = new File(modelDir, name);
            if (!source.renameTo(staging)) {
                copyFile(source, staging);
            }
            if (!staging.renameTo(target)) {
                deleteIfExists(target);
                if (!staging.renameTo(target)) {
                    throw new IOException("cannot install " + name);
                }
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static String baseName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash >= 0 ? entryName.substring(slash + 1) : entryName;
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static long contentLength(HttpURLConnection connection) {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot delete " + file);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "cannot delete " + file);
        }
    }
}
