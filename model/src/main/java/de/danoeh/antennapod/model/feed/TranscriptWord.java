package de.danoeh.antennapod.model.feed;

public class TranscriptWord {
    private final int startChar;
    private final int endChar;
    private final long startTime;
    private final long endTime;

    public TranscriptWord(int startChar, int endChar, long startTime, long endTime) {
        this.startChar = startChar;
        this.endChar = endChar;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public int getStartChar() {
        return startChar;
    }

    public int getEndChar() {
        return endChar;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }
}
