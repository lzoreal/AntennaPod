package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Streaming recognizer that consumes 16 kHz mono chunks and emits finalized segments. */
public interface StreamingAsr extends AutoCloseable {
    void accept(@NonNull PcmChunk chunk);

    @Nullable
    AsrSegment pollSegment();

    void flush();

    long lastProcessedMs();

    @Override
    void close();
}
