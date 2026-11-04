package de.danoeh.antennapod.asr;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Turns SentencePiece tokens from the recognizer into words with character ranges. */
public final class AsrTokenizer {
    static final char WORD_BOUNDARY = '▁';

    private AsrTokenizer() {
    }

    @NonNull
    public static List<AsrWord> tokenize(String text, String[] tokens, float[] timestamps,
                                         long offsetMs) {
        List<AsrWord> result = new ArrayList<>();
        if (text == null || tokens == null || timestamps == null || tokens.length == 0) {
            return result;
        }
        int count = Math.min(tokens.length, timestamps.length);
        int cursor = 0;
        int wordStartChar = -1;
        long wordStartMs = -1;
        boolean pendingBoundary = false;
        for (int i = 0; i < count; i++) {
            String token = tokens[i];
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (token.length() == 1
                    && (token.charAt(0) == WORD_BOUNDARY || token.charAt(0) == ' ')) {
                // Some models emit a standalone ▁ for a word gap instead of prefixing the next word.
                pendingBoundary = true;
                continue;
            }
            boolean prefixed = token.charAt(0) == WORD_BOUNDARY || token.charAt(0) == ' ';
            final boolean boundary = pendingBoundary
                    || (prefixed && token.length() > 1);
            pendingBoundary = false;
            String piece = prefixed && token.length() > 1 ? token.substring(1) : token;
            if (isPunctuationPiece(piece)) {
                // Punctuation must sit right after the current cursor; a stray leading punctuation
                // token (common after a stream reset) must not jump the cursor to a later period.
                int attached = attachPunctuation(text, piece, cursor);
                if (attached >= 0) {
                    cursor = attached;
                }
                continue;
            }
            int index = text.indexOf(piece, cursor);
            if (index < 0) {
                index = indexOfIgnoreCase(text, piece, cursor);
            }
            if (index < 0) {
                if (!result.isEmpty() || isSkippablePiece(piece)) {
                    continue;
                }
                return wholeText(text, timestamps, offsetMs);
            }
            long startMs = Math.round(timestamps[i] * 1000f);
            int endChar = index + piece.length();
            if (isCjk(piece.charAt(0))) {
                if (wordStartChar >= 0) {
                    result.add(new AsrWord(text.substring(wordStartChar, cursor), wordStartMs,
                            startMs, wordStartChar, cursor));
                }
                result.add(new AsrWord(piece, startMs,
                        Math.max(startMs, nextStartMs(timestamps, i, offsetMs)), index, endChar));
                wordStartChar = -1;
                wordStartMs = -1;
            } else if (wordStartChar < 0) {
                wordStartChar = index;
                wordStartMs = startMs;
            } else if (boundary) {
                result.add(new AsrWord(text.substring(wordStartChar, cursor), wordStartMs,
                        startMs, wordStartChar, cursor));
                wordStartChar = index;
                wordStartMs = startMs;
            }
            cursor = endChar;
        }
        if (wordStartChar >= 0) {
            result.add(new AsrWord(text.substring(wordStartChar, cursor), wordStartMs,
                    Math.max(wordStartMs, offsetMs), wordStartChar, cursor));
        }
        return result;
    }

    private static List<AsrWord> wholeText(String text, float[] timestamps, long offsetMs) {
        List<AsrWord> result = new ArrayList<>(1);
        long startMs = timestamps != null && timestamps.length > 0
                ? Math.round(timestamps[0] * 1000f) : 0;
        result.add(new AsrWord(text, startMs, Math.max(startMs, offsetMs), 0, text.length()));
        return result;
    }

    private static long nextStartMs(float[] timestamps, int index, long offsetMs) {
        if (index + 1 < timestamps.length) {
            return Math.round(timestamps[index + 1] * 1000f);
        }
        return Math.max(0, offsetMs);
    }

    private static boolean isCjk(char character) {
        Character.UnicodeScript script = Character.UnicodeScript.of(character);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isSkippablePiece(String piece) {
        if (piece.isEmpty()) {
            return true;
        }
        if (piece.startsWith("<") && piece.endsWith(">")) {
            return true;
        }
        for (int i = 0; i < piece.length(); i++) {
            if (Character.isLetterOrDigit(piece.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfIgnoreCase(String text, String piece, int from) {
        int last = text.length() - piece.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, piece, 0, piece.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPunctuationPiece(String piece) {
        if (piece.isEmpty()) {
            return false;
        }
        for (int i = 0; i < piece.length(); i++) {
            if (",.;:!?，。！？；：、…\"'”’)]}".indexOf(piece.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the cursor after {@code piece} if it directly follows {@code cursor}.
     */
    private static int attachPunctuation(String text, String piece, int cursor) {
        int i = cursor;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return text.startsWith(piece, i) ? i + piece.length() : -1;
    }
}
