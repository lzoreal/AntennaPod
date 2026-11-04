package de.danoeh.antennapod.ui.screen.playback;

import java.util.HashSet;
import java.util.Set;

/**
 * Process-wide ownership of one generated-transcript session per media id. The live session and
 * the finalize worker both need it before they may read or write the same transcript file.
 */
final class AsrSessionLease {
    private static final Set<Long> OWNED = new HashSet<>();

    private AsrSessionLease() {
    }

    static synchronized boolean acquire(long mediaId) {
        return OWNED.add(mediaId);
    }

    static synchronized void release(long mediaId) {
        OWNED.remove(mediaId);
    }
}
