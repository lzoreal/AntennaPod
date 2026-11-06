package de.danoeh.antennapod.ad;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class FingerprintCodecTest {
    @Test
    public void roundTripKeepsOnlyStrideAnchors() throws IOException {
        Fingerprinter.Hashes h = Fingerprinter.fingerprint(Synth.speechLike(20, 5));
        byte[] blob = FingerprintCodec.encode(h, 8, 8000);
        FingerprintCodec.Decoded d = FingerprintCodec.decode(blob);
        int expected = 0;
        for (int f : h.frame) {
            if (f % 8 == 0) {
                expected++;
            }
        }
        assertEquals(expected, d.hashes.hash.length);
        assertEquals(8, d.stride);
        assertEquals(8000, d.sampleRate);
        for (int i = 0; i < d.hashes.frame.length; i++) {
            assertEquals(0, d.hashes.frame[i] % 8);
        }
    }

    @Test(expected = IOException.class)
    public void rejectsGarbage() throws IOException {
        FingerprintCodec.decode(new byte[]{1, 2, 3});
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedBlob() throws IOException {
        byte[] blob = FingerprintCodec.encode(
                Fingerprinter.fingerprint(Synth.speechLike(20, 5)), 4, 8000);
        FingerprintCodec.decode(Arrays.copyOf(blob, blob.length / 2));
    }

    @Test
    public void preservesHashValues() throws IOException {
        Fingerprinter.Hashes h = Fingerprinter.fingerprint(Synth.speechLike(10, 9));
        FingerprintCodec.Decoded d = FingerprintCodec.decode(FingerprintCodec.encode(h, 4, 8000));
        int j = 0;
        for (int i = 0; i < h.hash.length; i++) {
            if (h.frame[i] % 4 == 0) {
                assertEquals(h.hash[i], d.hashes.hash[j]);
                assertEquals(h.frame[i], d.hashes.frame[j]);
                j++;
            }
        }
    }
}
