package de.danoeh.antennapod.ui.screen.playback;

import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;

/** Maps segment word timings onto the character ranges of the displayed text. */
final class TranscriptWordMapper {

    private TranscriptWordMapper() {
    }

    static int[][] map(TranscriptSegment segment, String display, int[][] ranges,
                       int[] rawSentenceStarts) {
        List<TranscriptWord> timings = segment.getWordTimings();
        if (timings.isEmpty() || ranges.length == 0) {
            return null;
        }
        String raw = segment.getWords();
        int[] starts = new int[timings.size()];
        int[] ends = new int[timings.size()];
        Arrays.fill(starts, -1);
        Arrays.fill(ends, -1);
        int[] cursors = new int[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            cursors[i] = ranges[i][0];
        }
        for (int i = 0; i < timings.size(); i++) {
            TranscriptWord word = timings.get(i);
            if (word.getStartChar() < 0 || word.getEndChar() > raw.length()
                    || word.getEndChar() <= word.getStartChar()) {
                continue;
            }
            String token = raw.substring(word.getStartChar(), word.getEndChar());
            int range = sentenceIndexForOffset(rawSentenceStarts, word.getStartChar(),
                    ranges.length);
            int found = -1;
            for (int r = range; r < ranges.length && found < 0; r++) {
                found = findToken(display, token, cursors[r], ranges[r][1]);
                if (found >= 0) {
                    range = r;
                }
            }
            if (found < 0) {
                continue;
            }
            starts[i] = found;
            ends[i] = found + token.length();
            cursors[range] = ends[i];
        }
        return new int[][] {starts, ends};
    }

    private static int sentenceIndexForOffset(int[] sentenceStarts, int offset, int max) {
        int index = 0;
        for (int i = 1; i < sentenceStarts.length && i < max; i++) {
            if (sentenceStarts[i] <= offset) {
                index = i;
            }
        }
        return index;
    }

    private static int findToken(String text, String token, int from, int to) {
        int last = Math.min(to, text.length()) - token.length();
        for (int i = Math.max(from, 0); i <= last; i++) {
            if (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
                continue;
            }
            int end = i + token.length();
            if (end < text.length() && !Character.isWhitespace(text.charAt(end))
                    && Character.isLetterOrDigit(text.charAt(end))) {
                continue;
            }
            if (text.regionMatches(i, token, 0, token.length())
                    || text.regionMatches(true, i, token, 0, token.length())) {
                return i;
            }
        }
        return -1;
    }
}
