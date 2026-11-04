package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Download location, archive layout and expected files of one model pack. */
public final class AsrModelDescriptor {
    private final String id;
    private final String downloadUrl;
    private final long downloadSizeBytes;
    private final String encoderFile;
    private final String decoderFile;
    private final String joinerFile;
    private final String tokensFile;
    private final Map<String, String> sha256ByFile;

    public AsrModelDescriptor(@NonNull String id,
                              @NonNull String downloadUrl, long downloadSizeBytes,
                              @NonNull String encoderFile,
                              @NonNull String decoderFile, @NonNull String joinerFile,
                              @NonNull String tokensFile, @NonNull Map<String, String> sha256ByFile) {
        this.id = requireText(id, "id");
        this.downloadUrl = requireText(downloadUrl, "downloadUrl");
        this.downloadSizeBytes = Math.max(0, downloadSizeBytes);
        this.encoderFile = requireText(encoderFile, "encoderFile");
        this.decoderFile = requireText(decoderFile, "decoderFile");
        this.joinerFile = requireText(joinerFile, "joinerFile");
        this.tokensFile = requireText(tokensFile, "tokensFile");
        this.sha256ByFile = Collections.unmodifiableMap(new LinkedHashMap<>(sha256ByFile));
    }

    public String getId() {
        return id;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public long getDownloadSizeBytes() {
        return downloadSizeBytes;
    }

    public String getEncoderFile() {
        return encoderFile;
    }

    public String getDecoderFile() {
        return decoderFile;
    }

    public String getJoinerFile() {
        return joinerFile;
    }

    public String getTokensFile() {
        return tokensFile;
    }

    @NonNull
    public List<String> getExpectedFiles() {
        return Collections.unmodifiableList(Arrays.asList(
                encoderFile, decoderFile, joinerFile, tokensFile));
    }

    @Nullable
    public String sha256For(String fileName) {
        return sha256ByFile.get(fileName);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }
}
