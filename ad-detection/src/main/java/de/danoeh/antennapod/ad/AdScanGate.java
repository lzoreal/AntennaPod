package de.danoeh.antennapod.ad;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Serializes head scans so prefetching does not decode several episodes at once. */
final class AdScanGate {
    private static final Semaphore LOCK = new Semaphore(1);

    private AdScanGate() {
    }

    static boolean acquire() {
        try {
            return LOCK.tryAcquire(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void release() {
        LOCK.release();
    }
}
