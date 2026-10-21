package de.danoeh.antennapod.ui.screen.playback;

import de.danoeh.antennapod.model.feed.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;

final class TranscriptTextUtils {
    private static final long LONG_SEGMENT_MS = 15000;

    private TranscriptTextUtils() {
    }

    static boolean isLongSegment(TranscriptSegment segment) {
        return segment.getEndTime() - segment.getStartTime() >= LONG_SEGMENT_MS;
    }

    static float progressAt(TranscriptSegment segment, long position) {
        long duration = segment.getEndTime() - segment.getStartTime();
        if (duration <= 0) {
            return 0f;
        }
        float progress = (position - segment.getStartTime()) / (float) duration;
        return Math.max(0f, Math.min(1f, progress));
    }

    static int primaryLength(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text.length() : newline;
    }

    static int charIndexAt(String text, float progress) {
        return Math.round(progress * primaryLength(text));
    }

    static int[] chunkStarts(String text) {
        int length = primaryLength(text);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int last = 0;
        for (int i = 0; i < length; i++) {
            if (!isSentenceEnd(text, i, length)) {
                continue;
            }
            int boundary = i + 1;
            while (boundary < length && isTrailingChar(text.charAt(boundary))) {
                boundary++;
            }
            starts.add(boundary);
            last = boundary;
        }
        if (length > last) {
            starts.add(length);
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    private static boolean isTrailingChar(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '!' || c == '?' || c == ')'
                || c == '）' || c == ']' || c == '】' || c == '」' || c == '』' || c == '》'
                || c == '。' || c == '！' || c == '？' || c == '…';
    }

    static String splitIntoSentences(String text) {
        int length = primaryLength(text);
        int[] starts = chunkStarts(text);
        if (starts.length < 3) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i + 1 < starts.length; i++) {
            String line = text.substring(starts[i], starts[i + 1]).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        if (length < text.length()) {
            result.append(text.substring(length));
        }
        return result.toString();
    }

    private static boolean isSentenceEnd(String text, int index, int length) {
        char c = text.charAt(index);
        if (c == '。' || c == '！' || c == '？' || c == '…') {
            return true;
        }
        if (c == '!' || c == '?') {
            return true;
        }
        if (c != '.') {
            return false;
        }
        if (index > 0 && Character.isDigit(text.charAt(index - 1))) {
            return false;
        }
        if (index + 1 >= length) {
            return true;
        }
        char next = text.charAt(index + 1);
        return Character.isWhitespace(next) || Character.isSpaceChar(next)
                || next == '"' || next == '”' || next == '\'';
    }
}
