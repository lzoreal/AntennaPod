package de.danoeh.antennapod.asr;

/** One recognized word with its media time and character range inside a segment. */
public final class AsrWord {
    private final String text;
    private final long startTime;
    private final long endTime;
    private final int startChar;
    private final int endChar;

    public AsrWord(String text, long startTime, long endTime, int startChar, int endChar) {
        this.text = text;
        this.startTime = startTime;
        this.endTime = endTime;
        this.startChar = startChar;
        this.endChar = endChar;
    }

    public String getText() {
        return text;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public int getStartChar() {
        return startChar;
    }

    public int getEndChar() {
        return endChar;
    }
}
