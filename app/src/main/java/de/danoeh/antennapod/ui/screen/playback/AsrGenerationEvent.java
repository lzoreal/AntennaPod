package de.danoeh.antennapod.ui.screen.playback;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

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
    /** String resource explaining a guard-triggered stop; 0 when none. */
    @StringRes
    public final int stopReason;

    public AsrGenerationEvent(Type type, long mediaId, long decodedEndMs,
                              @Nullable Throwable error) {
        this(type, mediaId, decodedEndMs, error, 0);
    }

    public AsrGenerationEvent(Type type, long mediaId, long decodedEndMs,
                              @Nullable Throwable error, @StringRes int stopReason) {
        this.type = type;
        this.mediaId = mediaId;
        this.decodedEndMs = decodedEndMs;
        this.error = error;
        this.stopReason = stopReason;
    }
}
