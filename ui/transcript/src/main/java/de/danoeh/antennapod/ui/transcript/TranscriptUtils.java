package de.danoeh.antennapod.ui.transcript;

import android.util.Log;
import org.apache.commons.io.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
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
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class TranscriptUtils {
    private static final String TAG = "Transcript";
    private static final int MAX_CACHED_TRANSCRIPTS = 8;
    private static final long MAX_TRANSCRIPT_BYTES = 5L * 1024 * 1024;
    private static final Map<String, Transcript> TRANSCRIPT_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

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
        StringBuilder str = new StringBuilder();
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
                str.append(source.readString(charset));
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
        return str.toString();
    }

    public static Transcript loadTranscript(FeedMedia media, Boolean forceRefresh) throws InterruptedIOException {
        if (media == null || media.getItem() == null) {
            return null;
        }
        String transcriptType = media.getItem().getTranscriptType();
        String cacheKey = cacheKey(media);

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

        boolean staleLocalTranscript = false;
        if (!forceRefresh && media.getTranscriptFileUrl() != null) {
            File transcriptFile = new File(media.getTranscriptFileUrl());
            if (transcriptFile.exists()) {
                Transcript localTranscript = loadLocalTranscript(media, cacheKey);
                if (localTranscript != null) {
                    return localTranscript;
                }
                staleLocalTranscript = true;
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
                boolean storeLocal = staleLocalTranscript
                        || (forceRefresh && media.getTranscriptFileUrl() != null);
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
        if (media.getTranscriptFileUrl() == null) {
            return null;
        }
        File transcriptFile = new File(media.getTranscriptFileUrl());
        if (!transcriptFile.exists() || transcriptFile.length() > MAX_TRANSCRIPT_BYTES
                || !isTranscriptFileCurrent(media)) {
            return null;
        }
        try {
            String t = FileUtils.readFileToString(transcriptFile, StandardCharsets.UTF_8);
            if (StringUtils.isEmpty(t)) {
                return null;
            }
            Transcript transcript = parseTranscript(t, media.getItem().getTranscriptType());
            if (transcript == null) {
                return null;
            }
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
        if (!metadataFile.exists()) {
            return true;
        }
        try {
            return transcriptSource(media).equals(
                    FileUtils.readFileToString(metadataFile, StandardCharsets.UTF_8));
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
            FileUtils.writeStringToFile(new File(metadataFileUrl),
                    transcriptSource(media), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "Unable to store transcript metadata", e);
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

    private static String cacheKey(FeedMedia media) {
        String fileUrl = media.getTranscriptFileUrl();
        long lastModified = 0;
        if (fileUrl != null) {
            File file = new File(fileUrl);
            if (file.exists()) {
                lastModified = file.lastModified();
            }
        }
        return media.getId() + "|" + media.getItem().getTranscriptType() + "|"
                + media.getItem().getTranscriptUrl() + "|" + lastModified;
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
        File tempFile = new File(transcriptFile.getPath() + ".tmp");
        FileOutputStream ostream = null;
        try {
            ostream = new FileOutputStream(tempFile);
            ostream.write(transcript.getBytes(StandardCharsets.UTF_8));
            ostream.close();
            ostream = null;
            if (!tempFile.renameTo(transcriptFile)) {
                if (transcriptFile.exists() && !transcriptFile.delete()) {
                    Log.e(TAG, "Failed to delete existing transcript file "
                            + transcriptFile.getAbsolutePath());
                    return;
                }
                if (!tempFile.renameTo(transcriptFile)) {
                    FileUtils.copyFile(tempFile, transcriptFile);
                }
            }
            writeTranscriptMetadata(media);
        } catch (IOException e) {
            Log.e(TAG, "Unable to store transcript file", e);
        } finally {
            IOUtils.closeQuietly(ostream);
            if (tempFile.exists() && !tempFile.delete()) {
                Log.e(TAG, "Unable to delete temporary transcript file " + tempFile);
            }
        }
    }
}
