package de.danoeh.antennapod.asr;

import java.io.IOException;

/**
 * A transient failure (cache still filling, decoder stall) that should be retried instead of
 * ending the generation session.
 */
public final class AsrRetryableException extends IOException {
    public AsrRetryableException(String message) {
        super(message);
    }

    public AsrRetryableException(String message, Throwable cause) {
        super(message, cause);
    }

    public static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AsrRetryableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
