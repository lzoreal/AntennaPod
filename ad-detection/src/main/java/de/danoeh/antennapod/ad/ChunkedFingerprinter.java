package de.danoeh.antennapod.ad;

/** Fingerprints long 8 kHz PCM in frame-aligned chunks so heap use stays bounded. */
public final class ChunkedFingerprinter {
    // Multiple of the 6-frame peak bucket so chunk boundaries keep bucket alignment.
    static final int DEFAULT_CHUNK_FRAMES = 3750; // 1 minute

    private final int chunkSamples;
    private final float[] chunk;
    private final Fingerprinter.IntList hashes = new Fingerprinter.IntList(1 << 16);
    private final Fingerprinter.IntList frames = new Fingerprinter.IntList(1 << 16);
    private int buffered;
    private int frameOffset;
    private long totalSamples;

    public ChunkedFingerprinter() {
        this(DEFAULT_CHUNK_FRAMES);
    }

    ChunkedFingerprinter(int chunkFrames) {
        if (chunkFrames % 6 != 0) {
            throw new IllegalArgumentException("chunkFrames must be a multiple of 6");
        }
        chunkSamples = chunkFrames * Fingerprinter.HOP;
        chunk = new float[chunkSamples];
    }

    public void add(float[] pcm, int offset, int length) {
        totalSamples += length;
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            int take = Math.min(remaining, chunkSamples - buffered);
            System.arraycopy(pcm, off, chunk, buffered, take);
            buffered += take;
            off += take;
            remaining -= take;
            if (buffered == chunkSamples) {
                emit(chunk, 0, chunkSamples);
                buffered = 0;
            }
        }
    }

    public Fingerprinter.Hashes finish() {
        if (buffered >= Fingerprinter.FRAME) {
            emit(chunk, 0, buffered);
        }
        int totalFrames = totalSamples < Fingerprinter.FRAME
                ? 0 : (int) ((totalSamples - Fingerprinter.FRAME) / Fingerprinter.HOP + 1);
        return new Fingerprinter.Hashes(hashes.toArray(), frames.toArray(), totalFrames, 0);
    }

    private void emit(float[] pcm, int offset, int length) {
        float[] slice = offset == 0 && length == pcm.length
                ? pcm : java.util.Arrays.copyOfRange(pcm, offset, offset + length);
        Fingerprinter.Hashes h = Fingerprinter.fingerprint(slice);
        for (int i = 0; i < h.hash.length; i++) {
            hashes.add(h.hash[i]);
            frames.add(h.frame[i] + frameOffset);
        }
        frameOffset += length / Fingerprinter.HOP;
    }
}
