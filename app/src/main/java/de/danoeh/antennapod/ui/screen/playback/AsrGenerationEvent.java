package de.danoeh.antennapod.ui.screen.playback;

import androidx.annotation.Nullable;

/** Cross-page progress events for generated transcript sessions. */
public final class AsrGenerationEvent {
    public enum Type {
        STARTED,
        SEGMENT,
        PROGRESS,
        COMPLETED,
        ERROR,
        STOPPED
    }

    public final Type type;
    public final long mediaId;
    public final long decodedEndMs;
    @Nullable
    public final Throwable error;

    public AsrGenerationEvent(Type type, long mediaId, long decodedEndMs,
                              @Nullable Throwable error) {
        this.type = type;
        this.mediaId = mediaId;
        this.decodedEndMs = decodedEndMs;
        this.error = error;
    }
}
