package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Cheap identity of a downloaded audio file: size plus its head and tail bytes. */
public final class AudioFingerprint {
    private static final int SAMPLE_BYTES = 64 * 1024;

    private AudioFingerprint() {
    }

    @Nullable
    public static String of(@Nullable File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(longBytes(length));
            byte[] buffer = new byte[(int) Math.min(SAMPLE_BYTES, length)];
            input.readFully(buffer);
            digest.update(buffer);
            if (length > SAMPLE_BYTES) {
                input.seek(Math.max(SAMPLE_BYTES, length - SAMPLE_BYTES));
                int tail = (int) Math.min(SAMPLE_BYTES, length - input.getFilePointer());
                if (tail > 0) {
                    byte[] tailBuffer = new byte[tail];
                    input.readFully(tailBuffer);
                    digest.update(tailBuffer);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static byte[] longBytes(long value) {
        return new byte[]{
                (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40),
                (byte) (value >>> 32), (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }
}
