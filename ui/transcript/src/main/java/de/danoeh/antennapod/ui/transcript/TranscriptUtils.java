package de.danoeh.antennapod.ui.transcript;

import android.util.Log;
import org.apache.commons.io.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
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
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class TranscriptUtils {
    private static final String TAG = "Transcript";
    private static final int MAX_CACHED_TRANSCRIPTS = 8;
    private static final Map<String, Transcript> TRANSCRIPT_CACHE = new LinkedHashMap<>();

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
                Log.d(TAG, "Done Downloading transcript URL " + url);
                str.append(response.body().string());
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

        if (!forceRefresh && media.getTranscriptFileUrl() != null) {
            File transcriptFile = new File(media.getTranscriptFileUrl());
            try {
                if (transcriptFile.exists()) {
                    String t = FileUtils.readFileToString(transcriptFile, (String) null);
                    if (StringUtils.isNotEmpty(t)) {
                        Transcript transcript = parseTranscript(t, transcriptType);
                        if (transcript != null) {
                            cacheTranscript(cacheKey, transcript);
                        }
                        media.setTranscript(transcript);
                        return transcript;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to read transcript file", e);
            }
        }

        String transcriptUrl = media.getItem().getTranscriptUrl();
        if (transcriptUrl == null) {
            return null;
        }
        String t = loadTranscriptFromUrl(transcriptUrl, forceRefresh);
        if (StringUtils.isNotEmpty(t)) {
            Transcript transcript = parseTranscript(t, transcriptType);
            if (transcript != null) {
                cacheTranscript(cacheKey, transcript);
            }
            return transcript;
        }
        return null;
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
        return media.getId() + "|" + media.getItem().getTranscriptUrl() + "|" + lastModified;
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

    private static Transcript parseTranscript(String text, String transcriptType) {
        Transcript transcript = TranscriptParser.parse(text, transcriptType);
        if (transcript != null && !transcript.isBilingual()) {
            transcript.setBilingual(hasBilingualSegments(transcript));
        }
        return transcript;
    }

    private static boolean hasBilingualSegments(Transcript transcript) {
        int bilingualSegments = 0;
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            if (TranscriptTextUtils.languageSplitIndex(transcript.getSegmentAt(i).getWords()) >= 0) {
                bilingualSegments++;
            }
        }
        return bilingualSegments > 0 && bilingualSegments * 2 > transcript.getSegmentCount();
    }

    public static void storeTranscript(FeedMedia media, String transcript) {
        if (media.getTranscriptFileUrl() == null) {
            return;
        }
        File transcriptFile = new File(media.getTranscriptFileUrl());
        FileOutputStream ostream = null;
        try {
            if (transcriptFile.exists() && !transcriptFile.delete()) {
                Log.e(TAG, "Failed to delete existing transcript file " + transcriptFile.getAbsolutePath());
            }
            if (transcriptFile.createNewFile()) {
                ostream = new FileOutputStream(transcriptFile);
                ostream.write(transcript.getBytes(Charset.forName("UTF-8")));
                ostream.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to store transcript file", e);
        } finally {
            IOUtils.closeQuietly(ostream);
        }
    }
}
