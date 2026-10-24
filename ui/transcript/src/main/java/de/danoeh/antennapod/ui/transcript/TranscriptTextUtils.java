package de.danoeh.antennapod.ui.transcript;

import de.danoeh.antennapod.model.feed.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;

public final class TranscriptTextUtils {

    private TranscriptTextUtils() {
    }

    public static float progressAt(TranscriptSegment segment, long position) {
        long duration = segment.getEndTime() - segment.getStartTime();
        if (duration <= 0) {
            return 0f;
        }
        float progress = (position - segment.getStartTime()) / (float) duration;
        return Math.max(0f, Math.min(1f, progress));
    }

    public static int primaryLength(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text.length() : newline;
    }

    public static int languageSplitIndex(String text) {
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return -1;
        }
        String[] lines = text.split("\n");
        int firstScript = -1;
        for (String line : lines) {
            firstScript = scriptOf(line);
            if (firstScript >= 0) {
                break;
            }
        }
        if (firstScript < 0) {
            return firstBreak;
        }
        for (int k = lines.length - 1; k >= 0; k--) {
            if (scriptOf(lines[k]) == firstScript) {
                return k == lines.length - 1 ? -1 : nthNewline(text, k);
            }
        }
        return firstBreak;
    }

    private static int nthNewline(String text, int n) {
        int cursor = -1;
        for (int i = 0; i <= n; i++) {
            cursor = text.indexOf('\n', cursor + 1);
        }
        return cursor;
    }

    private static int scriptOf(String line) {
        int latin = 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) {
                latin++;
            } else if ((c >= '\u3040' && c <= '\u9fff') || (c >= '\uac00' && c <= '\ud7af')) { // CJK and Hangul
                cjk++;
            } else {
                other++;
            }
        }
        if (cjk > 0) {
            return 1;
        }
        if (latin > 0) {
            return 0;
        }
        return other > 0 ? 2 : -1;
    }

    public static int charIndexAt(String text, float progress) {
        int split = languageSplitIndex(text);
        return Math.round(progress * (split < 0 ? text.length() : split));
    }

    public static int[] chunkStarts(String text) {
        return chunkStarts(text, primaryLength(text));
    }

    public static int[] chunkStarts(String text, int length) {
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

    public static int sentenceIndexAt(String text, float progress, int length) {
        int[] starts = chunkStarts(text, length);
        int charIndex = Math.round(progress * length);
        int index = 0;
        for (int i = 1; i + 1 < starts.length; i++) {
            if (starts[i] <= charIndex) {
                index = i;
            }
        }
        return index;
    }

    public static long sentenceStartTime(TranscriptSegment segment, String text, int sentenceIndex, int length) {
        int[] starts = chunkStarts(text, length);
        int count = starts.length - 1;
        if (count <= 1) {
            return segment.getStartTime();
        }
        int index = Math.max(0, Math.min(sentenceIndex, count - 1));
        long duration = segment.getEndTime() - segment.getStartTime();
        return segment.getStartTime() + duration * index / count;
    }

    private static boolean isTrailingChar(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '!' || c == '?' || c == ')'
                || c == '）' || c == ']' || c == '】' || c == '」' || c == '』' || c == '》'
                || c == '。' || c == '！' || c == '？' || c == '…';
    }

    public static String splitIntoSentences(String text) {
        String primary = text;
        String translation = "";
        int split = languageSplitIndex(text);
        if (split >= 0) {
            primary = text.substring(0, split);
            translation = text.substring(split + 1);
        }
        String result = splitSentencesInBlock(primary);
        if (!translation.isEmpty()) {
            result = result + "\n" + translation;
        }
        return result;
    }

    private static String splitSentencesInBlock(String text) {
        int[] starts = chunkStarts(text, text.length());
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
