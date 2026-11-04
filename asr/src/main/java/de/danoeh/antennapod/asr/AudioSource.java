package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;

import java.io.IOException;

/** Opens the audio bytes that the pre-reader decodes. */
public interface AudioSource {
    @NonNull
    MediaDataSource open() throws IOException;
}
