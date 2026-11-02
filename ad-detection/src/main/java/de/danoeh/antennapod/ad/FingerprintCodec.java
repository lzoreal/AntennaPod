package de.danoeh.antennapod.ad;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Sparse fingerprint blob: header + (hash, frame) pairs, zlib-compressed. */
public final class FingerprintCodec {
    public static final int ALGO_VERSION = 1;
    private static final int MAGIC = 0x41444650;

    public static final class Decoded {
        public final int algoVersion;
        public final int stride;
        public final int frames;
        public final int sampleRate;
        public final Fingerprinter.Hashes hashes;

        Decoded(int algoVersion, int stride, int frames, int sampleRate, Fingerprinter.Hashes hashes) {
            this.algoVersion = algoVersion;
            this.stride = stride;
            this.frames = frames;
            this.sampleRate = sampleRate;
            this.hashes = hashes;
        }
    }

    private FingerprintCodec() {
    }

    public static byte[] encode(Fingerprinter.Hashes h, int stride, int sampleRate) {
        int count = 0;
        for (int f : h.frame) {
            if (f % stride == 0) {
                count++;
            }
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(20 + count * 8);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(MAGIC);
            out.writeShort(ALGO_VERSION);
            out.writeShort(stride);
            out.writeInt(h.frames);
            out.writeInt(sampleRate);
            out.writeInt(count);
            for (int i = 0; i < h.hash.length; i++) {
                if (h.frame[i] % stride == 0) {
                    out.writeInt(h.hash[i]);
                    out.writeInt(h.frame[i]);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] in = raw.toByteArray();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(in);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 2);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    public static Decoded decode(byte[] blob) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(blob);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(blob.length * 2);
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && inflater.needsInput()) {
                    throw new IOException("truncated blob");
                }
                raw.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("corrupt blob", e);
        } finally {
            inflater.end();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("bad magic");
            }
            int version = in.readUnsignedShort();
            int stride = in.readUnsignedShort();
            int frames = in.readInt();
            int sampleRate = in.readInt();
            int count = in.readInt();
            if (version != ALGO_VERSION || stride <= 0 || count < 0) {
                throw new IOException("unsupported blob");
            }
            int[] hash = new int[count];
            int[] frame = new int[count];
            for (int i = 0; i < count; i++) {
                hash[i] = in.readInt();
                frame[i] = in.readInt();
            }
            return new Decoded(version, stride, frames, sampleRate,
                    new Fingerprinter.Hashes(hash, frame, frames, 0));
        }
    }
}
