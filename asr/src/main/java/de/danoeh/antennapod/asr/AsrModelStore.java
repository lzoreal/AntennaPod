package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.io.File;

/** Resolves installed model packs below one app-owned directory. */
public final class AsrModelStore {
    private final File rootDir;
    private final int numThreads;

    public AsrModelStore(@NonNull File rootDir) {
        this(rootDir, 2);
    }

    public AsrModelStore(@NonNull File rootDir, int numThreads) {
        if (numThreads <= 0) {
            throw new IllegalArgumentException("numThreads must be positive: " + numThreads);
        }
        this.rootDir = rootDir;
        this.numThreads = numThreads;
    }

    @NonNull
    public File getRootDir() {
        return rootDir;
    }

    @NonNull
    public AsrModelDescriptor descriptor(@NonNull AsrModelLanguage language) {
        return AsrModelCatalog.forLanguage(language);
    }

    @NonNull
    public File modelDir(@NonNull AsrModelLanguage language) {
        return new File(rootDir, descriptor(language).getId());
    }

    public boolean isInstalled(@NonNull AsrModelLanguage language) {
        File dir = modelDir(language);
        for (String fileName : descriptor(language).getExpectedFiles()) {
            File file = new File(dir, fileName);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    public SherpaModelSpec spec(@NonNull AsrModelLanguage language) {
        if (!isInstalled(language)) {
            throw new IllegalStateException("model is not installed: " + language);
        }
        AsrModelDescriptor descriptor = descriptor(language);
        File dir = modelDir(language);
        return new SherpaModelSpec(
                new File(dir, descriptor.getEncoderFile()).getAbsolutePath(),
                new File(dir, descriptor.getDecoderFile()).getAbsolutePath(),
                new File(dir, descriptor.getJoinerFile()).getAbsolutePath(),
                new File(dir, descriptor.getTokensFile()).getAbsolutePath(),
                "",
                numThreads);
    }

    @NonNull
    public AsrModelLanguage languageForFeed(String feedLanguage, @NonNull AsrModelLanguage fallback) {
        return AsrModelLanguage.fromFeedLanguage(feedLanguage, fallback);
    }
}
