package de.danoeh.antennapod.parser.transcript;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.feed.TranscriptSegment;

public final class TranscriptSentenceUtils {
    public static final int SCRIPT_UNKNOWN = -1;
    public static final int SCRIPT_LATIN = 0;
    public static final int SCRIPT_CJK = 1;
    public static final int SCRIPT_OTHER = 2;

    private TranscriptSentenceUtils() {
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
        int firstScript = SCRIPT_UNKNOWN;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            firstScript = scriptOf(text, lineStart, lineEnd);
            if (firstScript != SCRIPT_UNKNOWN) {
                break;
            }
            if (lineEnd == text.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        if (firstScript == SCRIPT_UNKNOWN) {
            return firstBreak;
        }
        int logicalEnd = text.length();
        while (logicalEnd > 0 && text.charAt(logicalEnd - 1) == '\n') {
            logicalEnd--;
        }
        int lastLineEnd = logicalEnd;
        while (lastLineEnd > 0) {
            int lastLineStart = text.lastIndexOf('\n', lastLineEnd - 1) + 1;
            if (scriptOf(text, lastLineStart, lastLineEnd) == firstScript) {
                return lastLineEnd == logicalEnd ? -1 : lastLineEnd;
            }
            if (lastLineStart == 0) {
                break;
            }
            lastLineEnd = lastLineStart - 1;
        }
        return firstBreak;
    }

    public static int scriptOf(String line) {
        return scriptOf(line, 0, line.length());
    }

    private static int scriptOf(String text, int start, int end) {
        int latin = 0;
        int cjk = 0;
        int other = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) {
                latin++;
            } else if ((c >= 0x3040 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)) {
                cjk++;
            } else {
                other++;
            }
        }
        if (latin == 0 && cjk == 0) {
            return other > 0 ? SCRIPT_OTHER : SCRIPT_UNKNOWN;
        }
        if (cjk >= latin && cjk >= other) {
            return SCRIPT_CJK;
        }
        if (latin >= cjk && latin >= other) {
            return SCRIPT_LATIN;
        }
        return SCRIPT_OTHER;
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
            if (boundary > last) {
                starts.add(boundary);
                last = boundary;
            }
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
        return sentenceIndexAt(chunkStarts(text, length), progress, length);
    }

    public static int sentenceIndexAt(int[] starts, float progress, int length) {
        int charIndex = Math.round(progress * length);
        int index = 0;
        for (int i = 1; i + 1 < starts.length; i++) {
            if (starts[i] <= charIndex) {
                index = i;
            }
        }
        return index;
    }

    public static long sentenceEndTime(TranscriptSegment segment, String text, int sentenceIndex, int length) {
        return sentenceEndTime(segment, chunkStarts(text, length), sentenceIndex);
    }

    public static long sentenceEndTime(TranscriptSegment segment, int[] starts, int sentenceIndex) {
        int count = starts.length - 1;
        if (count <= 1) {
            return segment.getEndTime();
        }
        int index = Math.max(0, Math.min(sentenceIndex, count - 1));
        return index + 1 < count
                ? sentenceStartTime(segment, starts, index + 1)
                : segment.getEndTime();
    }

    public static long sentenceStartTime(TranscriptSegment segment, String text, int sentenceIndex, int length) {
        return sentenceStartTime(segment, chunkStarts(text, length), sentenceIndex);
    }

    public static long sentenceStartTime(TranscriptSegment segment, int[] starts, int sentenceIndex) {
        int count = starts.length - 1;
        if (count <= 1) {
            return segment.getStartTime();
        }
        int index = Math.max(0, Math.min(sentenceIndex, count - 1));
        int totalLength = starts[starts.length - 1];
        if (totalLength <= 0) {
            return segment.getStartTime();
        }
        long duration = segment.getEndTime() - segment.getStartTime();
        return segment.getStartTime() + duration * starts[index] / totalLength;
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
            result = result + "\n" + splitSentencesInBlock(translation);
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

    private static boolean isTrailingChar(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '!' || c == '?'
                || c == ')' || c == '）' || c == ']' || c == '】' || c == '」' || c == '』'
                || c == '》' || c == '。' || c == '！' || c == '？' || c == '…';
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
        if (index + 1 >= length) {
            return true;
        }
        char next = text.charAt(index + 1);
        if (index > 0 && Character.isDigit(text.charAt(index - 1)) && Character.isDigit(next)) {
            return false;
        }
        return Character.isWhitespace(next) || Character.isSpaceChar(next)
                || next == '"' || next == '”' || next == '\'';
    }
}
