package de.danoeh.antennapod.ui.screen.playback;

/** Display-ready text and span positions for one transcript row. */
final class DisplayInfo {
    final String text;
    final int split;
    final int primaryLength;
    final int[] primaryStarts;
    final int[] translationStarts;
    final boolean interleaved;
    final int[] primaryDisplayStarts;
    final int[] primaryDisplayEnds;
    final int[] translationDisplayStarts;
    final int[] translationDisplayEnds;
    final int[] pairStarts;
    final int[] wordStarts;
    final int[] wordEnds;
    final long[] wordEndTimes;

    DisplayInfo(String text, int split, int primaryLength,
                int[] primaryStarts, int[] translationStarts,
                int[] wordStarts, int[] wordEnds, long[] wordEndTimes) {
        this(text, split, primaryLength, primaryStarts, translationStarts, false,
                null, null, null, null, null, wordStarts, wordEnds, wordEndTimes);
    }

    DisplayInfo(String text, int split, int primaryLength,
                int[] primaryStarts, int[] translationStarts, boolean interleaved,
                int[] primaryDisplayStarts, int[] primaryDisplayEnds,
                int[] translationDisplayStarts, int[] translationDisplayEnds,
                int[] pairStarts, int[] wordStarts, int[] wordEnds,
                long[] wordEndTimes) {
        this.text = text;
        this.split = split;
        this.primaryLength = primaryLength;
        this.primaryStarts = primaryStarts;
        this.translationStarts = translationStarts;
        this.interleaved = interleaved;
        this.primaryDisplayStarts = primaryDisplayStarts;
        this.primaryDisplayEnds = primaryDisplayEnds;
        this.translationDisplayStarts = translationDisplayStarts;
        this.translationDisplayEnds = translationDisplayEnds;
        this.pairStarts = pairStarts;
        this.wordStarts = wordStarts;
        this.wordEnds = wordEnds;
        this.wordEndTimes = wordEndTimes;
    }
}
