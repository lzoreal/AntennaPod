package de.danoeh.antennapod.model.feed;

/** Fingerprints of one playback chunk. Blob frame numbers are relative to {@link #startFrame}. */
public class AdChunkFingerprintRow {
    private long mediaId;
    private int startFrame;
    private int frames;
    private int stride;
    private byte[] blob;

    public long getMediaId() {
        return mediaId;
    }

    public void setMediaId(long mediaId) {
        this.mediaId = mediaId;
    }

    public int getStartFrame() {
        return startFrame;
    }

    public void setStartFrame(int startFrame) {
        this.startFrame = startFrame;
    }

    public int getFrames() {
        return frames;
    }

    public void setFrames(int frames) {
        this.frames = frames;
    }

    public int getStride() {
        return stride;
    }

    public void setStride(int stride) {
        this.stride = stride;
    }

    public byte[] getBlob() {
        return blob;
    }

    public void setBlob(byte[] blob) {
        this.blob = blob;
    }
}
