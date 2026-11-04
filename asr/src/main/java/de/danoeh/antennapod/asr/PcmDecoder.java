package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/** Decodes a media source into 16 kHz mono PCM chunks ordered by media time. */
public interface PcmDecoder {
    void open(@NonNull AudioSource source, long startTimeMs) throws IOException;

    void seekTo(long timeMs) throws IOException;

    @Nullable
    PcmChunk read() throws IOException;

    long durationMs();

    void close();
}
