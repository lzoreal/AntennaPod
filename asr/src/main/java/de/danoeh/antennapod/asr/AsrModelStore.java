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
    public AsrModelDescriptor descriptor() {
        return AsrModelCatalog.model();
    }

    @NonNull
    public File modelDir() {
        return new File(rootDir, descriptor().getId());
    }

    public boolean isInstalled() {
        File dir = modelDir();
        for (String fileName : descriptor().getExpectedFiles()) {
            File file = new File(dir, fileName);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    public SherpaModelSpec spec() {
        if (!isInstalled()) {
            throw new IllegalStateException("model is not installed");
        }
        AsrModelDescriptor descriptor = descriptor();
        File dir = modelDir();
        return new SherpaModelSpec(
                new File(dir, descriptor.getEncoderFile()).getAbsolutePath(),
                new File(dir, descriptor.getDecoderFile()).getAbsolutePath(),
                new File(dir, descriptor.getJoinerFile()).getAbsolutePath(),
                new File(dir, descriptor.getTokensFile()).getAbsolutePath(),
                "",
                numThreads);
    }
}
