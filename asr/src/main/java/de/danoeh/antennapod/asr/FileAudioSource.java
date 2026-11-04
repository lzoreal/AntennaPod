package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/** A local media file that can be decoded without touching the player. */
public final class FileAudioSource implements AudioSource {
    private final File file;

    public FileAudioSource(@NonNull File file) {
        if (!file.isFile()) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        this.file = file;
    }

    @NonNull
    @Override
    public MediaDataSource open() throws IOException {
        return new FileMediaDataSource(file);
    }
}
