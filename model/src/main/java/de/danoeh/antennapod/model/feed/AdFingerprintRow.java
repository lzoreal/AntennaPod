package de.danoeh.antennapod.model.feed;

public class AdFingerprintRow {
    private long mediaId;
    private long durationMs;
    private int algoVersion;
    private int stride;
    private int frames;
    private long audioSize;
    private long audioModified;
    private byte[] blob;

    public long getMediaId() {
        return mediaId;
    }

    public void setMediaId(long mediaId) {
        this.mediaId = mediaId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getAlgoVersion() {
        return algoVersion;
    }

    public void setAlgoVersion(int algoVersion) {
        this.algoVersion = algoVersion;
    }

    public int getStride() {
        return stride;
    }

    public void setStride(int stride) {
        this.stride = stride;
    }

    public int getFrames() {
        return frames;
    }

    public void setFrames(int frames) {
        this.frames = frames;
    }

    public long getAudioSize() {
        return audioSize;
    }

    public void setAudioSize(long audioSize) {
        this.audioSize = audioSize;
    }

    public long getAudioModified() {
        return audioModified;
    }

    public void setAudioModified(long audioModified) {
        this.audioModified = audioModified;
    }

    public byte[] getBlob() {
        return blob;
    }

    public void setBlob(byte[] blob) {
        this.blob = blob;
    }
}
