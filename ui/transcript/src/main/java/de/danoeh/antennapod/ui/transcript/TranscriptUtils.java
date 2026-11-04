package de.danoeh.antennapod.ui.transcript;

import android.util.Log;
import org.apache.commons.io.FileUtils;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptType;
import de.danoeh.antennapod.parser.transcript.TranscriptJournal;
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang3.StringUtils;

public class TranscriptUtils {
    private static final String TAG = "Transcript";
    private static final int MAX_CACHED_TRANSCRIPTS = 8;
    private static final long MAX_TRANSCRIPT_BYTES = 5L * 1024 * 1024;
    private static final Map<String, Transcript> TRANSCRIPT_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final Object[] LOAD_LOCKS = new Object[8];
    static final String GENERATED_SOURCE = "generated";

    static {
        for (int i = 0; i < LOAD_LOCKS.length; i++) {
            LOAD_LOCKS[i] = new Object();
        }
    }

    public static String loadTranscriptFromUrl(String url, boolean forceRefresh) throws InterruptedIOException {
        if (forceRefresh) {
            return loadTranscriptFromUrl(url, CacheControl.FORCE_NETWORK);
        }
        String str = loadTranscriptFromUrl(url, CacheControl.FORCE_CACHE);
        if (str == null || str.length() <= 1) {
            // Some publishers use one dummy transcript before actual transcript are available
            return loadTranscriptFromUrl(url, CacheControl.FORCE_NETWORK);
        }
        return str;
    }

    private static String loadTranscriptFromUrl(String url, CacheControl cacheControl) throws InterruptedIOException {
        Response response = null;

        try {
            Log.d(TAG, "Downloading transcript URL " + url);
            Request request = new Request.Builder().url(url).cacheControl(cacheControl).build();
            response = AntennapodHttpClient.getHttpClient().newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                if (response.body().contentLength() > MAX_TRANSCRIPT_BYTES) {
                    Log.e(TAG, "Transcript too large: " + url);
                    return null;
                }
                BufferedSource source = response.body().source();
                if (source.request(MAX_TRANSCRIPT_BYTES + 1)) {
                    Log.e(TAG, "Transcript too large: " + url);
                    return null;
                }
                Charset charset = response.body().contentType() != null
                        ? response.body().contentType().charset(StandardCharsets.UTF_8)
                        : StandardCharsets.UTF_8;
                Log.d(TAG, "Done Downloading transcript URL " + url);
                return source.readString(charset);
            } else {
                Log.d(TAG, "Error Downloading transcript URL " + url + ": " + response.message());
            }
        } catch (InterruptedIOException e) {
            Log.d(TAG, "InterruptedIOException while downloading transcript URL " + url);
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Unable to download transcript URL " + url, e);
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return null;
    }

    public static Transcript loadTranscript(FeedMedia media, Boolean forceRefresh) throws InterruptedIOException {
        if (media == null || media.getItem() == null) {
            return null;
        }
        synchronized (loadLock(media)) {
            // Recompute inside the lock: another thread may have just written the file, which
            // changes the mtime part of the cache key.
            return loadTranscriptLocked(media, forceRefresh, cacheKey(media));
        }
    }

    private static Object loadLock(FeedMedia media) {
        String key = media.getId() + "|" + media.getItem().getTranscriptType() + "|"
                + media.getItem().getTranscriptUrl();
        return LOAD_LOCKS[(key.hashCode() & 0x7fffffff) % LOAD_LOCKS.length];
    }

    private static Transcript loadTranscriptLocked(FeedMedia media, Boolean forceRefresh,
                                                  String cacheKey) throws InterruptedIOException {
        String transcriptType = media.getItem().getTranscriptType();

        if (!forceRefresh) {
            Transcript cached = getCachedTranscript(cacheKey);
            if (cached != null) {
                media.setTranscript(cached);
                return cached;
            }
            if (media.getItem().getTranscript() != null) {
                return media.getTranscript();
            }
        }

        if (!forceRefresh && media.getGeneratedTranscriptFileUrl() != null) {
            File transcriptFile = new File(media.getGeneratedTranscriptFileUrl());
            if (transcriptFile.exists() || journalExists(transcriptFile)) {
                Transcript localTranscript = loadLocalTranscript(media, cacheKey);
                if (localTranscript != null) {
                    return localTranscript;
                }
            }
        }

        String transcriptUrl = media.getItem().getTranscriptUrl();
        if (transcriptUrl == null) {
            return fallbackToLocalOrCache(media, cacheKey, forceRefresh);
        }
        String t = loadTranscriptFromUrl(transcriptUrl, forceRefresh);
        if (StringUtils.isNotEmpty(t)) {
            Transcript transcript = parseTranscript(t, transcriptType);
            if (transcript != null) {
                boolean storeLocal = media.getTranscriptFileUrl() != null;
                if (storeLocal) {
                    storeTranscript(media, t);
                    cacheTranscript(cacheKey(media), transcript);
                } else {
                    cacheTranscript(cacheKey, transcript);
                }
                return transcript;
            }
        }
        return fallbackToLocalOrCache(media, cacheKey, forceRefresh);
    }

    private static Transcript loadLocalTranscript(FeedMedia media, String cacheKey) {
        if (media.getGeneratedTranscriptFileUrl() == null) {
            return null;
        }
        File transcriptFile = new File(media.getGeneratedTranscriptFileUrl());
        boolean hasBase = transcriptFile.exists();
        if ((!hasBase && !journalExists(transcriptFile))
                || (hasBase && transcriptFile.length() > MAX_TRANSCRIPT_BYTES)
                || !isTranscriptFileCurrent(media)) {
            return null;
        }
        try {
            Transcript transcript;
            if (hasBase) {
                String t = FileUtils.readFileToString(transcriptFile, StandardCharsets.UTF_8);
                if (StringUtils.isEmpty(t)) {
                    return null;
                }
                transcript = parseTranscript(t, media.getItem().getTranscriptType());
                if (transcript == null) {
                    return null;
                }
            } else {
                transcript = new Transcript();
            }
            TranscriptJournal.applyTo(transcript, transcriptFile);
            cacheLocalTranscript(media, cacheKey, transcript);
            media.setTranscript(transcript);
            return transcript;
        } catch (IOException e) {
            Log.e(TAG, "Unable to read transcript file", e);
            return null;
        }
    }

    private static void cacheLocalTranscript(FeedMedia media, String cacheKey,
                                             Transcript transcript) {
        if (transcript == null) {
            return;
        }
        cacheTranscript(cacheKey, transcript);
        if (!hasTranscriptMetadata(media)) {
            writeTranscriptMetadata(media);
        }
    }

    private static boolean hasTranscriptMetadata(FeedMedia media) {
        String metadataFileUrl = media.getTranscriptMetadataFileUrl();
        return metadataFileUrl != null && new File(metadataFileUrl).exists();
    }

    private static boolean isTranscriptFileCurrent(FeedMedia media) {
        String metadataFileUrl = media.getTranscriptMetadataFileUrl();
        if (metadataFileUrl == null) {
            return true;
        }
        File metadataFile = new File(metadataFileUrl);
        String officialUrl = media.getItem().getTranscriptUrl();
        if (!metadataFile.exists()) {
            // Legacy sidecars have no marker. They are only trusted while no published
            // transcript exists; otherwise the published one must win.
            return StringUtils.isEmpty(officialUrl);
        }
        try {
            String source = FileUtils.readFileToString(metadataFile, StandardCharsets.UTF_8).trim();
            if (GENERATED_SOURCE.equals(source)) {
                return StringUtils.isEmpty(officialUrl);
            }
            return transcriptSource(media).trim().equals(source);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read transcript metadata", e);
            return false;
        }
    }

    private static String transcriptSource(FeedMedia media) {
        String type = media.getItem().getTranscriptType();
        String url = media.getItem().getTranscriptUrl();
        return (type == null ? "" : type) + "\n" + (url == null ? "" : url);
    }

    private static void writeTranscriptMetadata(FeedMedia media) {
        String metadataFileUrl = media.getTranscriptMetadataFileUrl();
        if (metadataFileUrl == null) {
            return;
        }
        try {
            writeAtomically(new File(metadataFileUrl), transcriptSource(media));
        } catch (IOException e) {
            Log.e(TAG, "Unable to store transcript metadata", e);
        }
    }

    private static void writeAtomically(File target, String content) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temp = File.createTempFile("transcript-meta-", ".tmp", parent);
        try {
            FileUtils.writeStringToFile(temp, content, StandardCharsets.UTF_8);
            if (!temp.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    throw new IOException("cannot replace " + target);
                }
                if (!temp.renameTo(target)) {
                    throw new IOException("cannot install " + target);
                }
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static Transcript fallbackToLocalOrCache(FeedMedia media, String cacheKey,
                                                     Boolean forceRefresh) {
        if (!forceRefresh) {
            return null;
        }
        Transcript cached = getCachedTranscript(cacheKey);
        if (cached != null) {
            media.setTranscript(cached);
            return cached;
        }
        return loadLocalTranscript(media, cacheKey);
    }

    private static boolean journalExists(File transcriptFile) {
        File journal = TranscriptJournal.fileFor(transcriptFile);
        return journal.isFile() && journal.length() > 0;
    }

    private static String cacheKey(FeedMedia media) {
        String fileUrl = media.getGeneratedTranscriptFileUrl();
        long lastModified = 0;
        long length = 0;
        long journalLastModified = 0;
        long journalLength = 0;
        if (fileUrl != null) {
            File file = new File(fileUrl);
            if (file.exists()) {
                lastModified = file.lastModified();
                length = file.length();
            }
            File journal = TranscriptJournal.fileFor(file);
            if (journal.exists()) {
                journalLastModified = journal.lastModified();
                journalLength = journal.length();
            }
        }
        return media.getId() + "|" + media.getItem().getTranscriptType() + "|"
                + media.getItem().getTranscriptUrl() + "|" + lastModified + "|" + length
                + "|" + journalLastModified + "|" + journalLength;
    }

    private static synchronized Transcript getCachedTranscript(String key) {
        return TRANSCRIPT_CACHE.get(key);
    }

    private static synchronized void cacheTranscript(String key, Transcript transcript) {
        TRANSCRIPT_CACHE.put(key, transcript);
        if (TRANSCRIPT_CACHE.size() > MAX_CACHED_TRANSCRIPTS) {
            Iterator<String> iterator = TRANSCRIPT_CACHE.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    public static synchronized void clearCache() {
        TRANSCRIPT_CACHE.clear();
    }

    private static Transcript parseTranscript(String text, String transcriptType) {
        Transcript transcript = TranscriptParser.parse(text, transcriptType);
        if (transcript == null && TranscriptType.fromMime(transcriptType) == TranscriptType.NONE
                && text.trim().startsWith("{")) {
            transcript = TranscriptParser.parse(text, TranscriptType.JSON.canonicalMime);
        }
        if (transcript != null && !transcript.isBilingual()) {
            transcript.setBilingual(hasBilingualSegments(transcript));
        }
        return transcript;
    }

    private static boolean hasBilingualSegments(Transcript transcript) {
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            if (TranscriptTextUtils.languageSplitIndex(transcript.getSegmentAt(i).getWords()) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static void storeTranscript(FeedMedia media, String transcript) {
        if (media.getItem() == null || media.getTranscriptFileUrl() == null) {
            return;
        }
        if (parseTranscript(transcript, media.getItem().getTranscriptType()) == null) {
            Log.e(TAG, "Refusing to store invalid transcript");
            return;
        }
        File transcriptFile = new File(media.getTranscriptFileUrl());
        File parent = transcriptFile.getAbsoluteFile().getParentFile();
        File tempFile = null;
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create " + parent);
            }
            tempFile = File.createTempFile("transcript-", ".tmp", parent);
            FileUtils.writeStringToFile(tempFile, transcript, StandardCharsets.UTF_8);
            installFile(tempFile, transcriptFile);
            writeTranscriptMetadata(media);
        } catch (IOException e) {
            Log.e(TAG, "Unable to store transcript file", e);
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                Log.e(TAG, "Unable to delete temporary transcript file " + tempFile);
            }
        }
    }

    private static void installFile(File temp, File target) throws IOException {
        if (temp.renameTo(target)) {
            return;
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (temp.renameTo(target)) {
            return;
        }
        File copy = File.createTempFile("transcript-copy-", ".tmp", target.getParentFile());
        try {
            FileUtils.copyFile(temp, copy);
            if (!copy.renameTo(target)) {
                throw new IOException("cannot install " + target);
            }
        } finally {
            if (copy.exists() && !copy.delete()) {
                copy.deleteOnExit();
            }
        }
    }
}
