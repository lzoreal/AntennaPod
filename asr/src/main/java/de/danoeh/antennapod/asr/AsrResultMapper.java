package de.danoeh.antennapod.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Maps an OnlineRecognizerResult into a segment whose word times are media times. */
public final class AsrResultMapper {
    /**
     * Streaming zipformer reports a token at the encoder frame that emitted it, roughly one
     * chunk after the word onset. Measured against decoded audio onsets: 400 ms median on
     * Casefile 14056 and 434 ms on 14049 with the en-20M model.
     */
    static final long TOKEN_EMISSION_LAG_MS = 400;

    private AsrResultMapper() {
    }

    public static AsrSegment toSegment(String text, String[] tokens, float[] timestamps,
                                       long streamStartMs, long fallbackEndMs) {
        String segmentText = normalizeText(text);
        String[] effectiveTokens = tokens;
        if (isMostlyUppercase(segmentText)) {
            segmentText = segmentText.toLowerCase(Locale.ROOT);
            effectiveTokens = lowercaseTokens(tokens);
        }
        long startMs = Math.max(0, streamStartMs);
        long endMs = Math.max(startMs, fallbackEndMs);
        if (segmentText.isEmpty()) {
            return new AsrSegment(segmentText, Collections.emptyList());
        }
        List<AsrWord> words;
        if (effectiveTokens == null || effectiveTokens.length == 0
                || timestamps == null || timestamps.length == 0) {
            words = Collections.singletonList(
                    new AsrWord(segmentText, startMs, endMs, 0, segmentText.length()));
        } else {
            float[] absoluteTimestamps = new float[timestamps.length];
            float offsetSeconds = (startMs - TOKEN_EMISSION_LAG_MS) / 1000f;
            for (int i = 0; i < timestamps.length; i++) {
                absoluteTimestamps[i] = Math.max(0f, timestamps[i] + offsetSeconds);
            }
            words = AsrTokenizer.tokenize(segmentText, effectiveTokens, absoluteTimestamps, endMs);
        }
        return normalize(segmentText, words);
    }

    private static AsrSegment normalize(String text, List<AsrWord> words) {
        String normalized = applySentenceCase(text);
        if (!normalized.isEmpty() && !hasTerminalPunctuation(normalized)) {
            normalized += terminalPunctuation(normalized);
        }
        List<AsrWord> updatedWords = new ArrayList<>(words.size());
        for (AsrWord word : words) {
            int start = Math.min(word.getStartChar(), normalized.length());
            int end = Math.min(word.getEndChar(), normalized.length());
            if (start >= end) {
                updatedWords.add(word);
            } else {
                updatedWords.add(new AsrWord(normalized.substring(start, end),
                        word.getStartTime(), word.getEndTime(),
                        word.getStartChar(), word.getEndChar()));
            }
        }
        return new AsrSegment(normalized, updatedWords);
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.replaceAll("\\s+([,.;:!?，。！？；：、])", "$1");
    }

    private static String applySentenceCase(String text) {
        StringBuilder builder = new StringBuilder(text);
        boolean capitalizeNext = true;
        for (int i = 0; i < builder.length(); i++) {
            char character = builder.charAt(i);
            if (Character.isLetter(character)) {
                if (capitalizeNext) {
                    builder.setCharAt(i, Character.toUpperCase(character));
                    capitalizeNext = false;
                }
            } else if ("。！？.!?".indexOf(character) >= 0) {
                capitalizeNext = true;
            }
        }
        return builder.toString();
    }

    private static boolean hasTerminalPunctuation(String text) {
        int index = text.length() - 1;
        while (index >= 0 && (Character.isWhitespace(text.charAt(index))
                || text.charAt(index) == '"' || text.charAt(index) == '\''
                || text.charAt(index) == '”' || text.charAt(index) == '’')) {
            index--;
        }
        return index >= 0 && "。！？.!?…".indexOf(text.charAt(index)) >= 0;
    }

    private static String terminalPunctuation(String text) {
        return containsCjk(text) ? "。" : ".";
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMostlyUppercase(String text) {
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isLetter(character)) {
                letters++;
                if (Character.isUpperCase(character)) {
                    uppercase++;
                }
            }
        }
        return letters > 0 && uppercase * 10 >= letters * 9;
    }

    private static String[] lowercaseTokens(String[] tokens) {
        if (tokens == null) {
            return null;
        }
        String[] result = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            result[i] = tokens[i] == null ? null : tokens[i].toLowerCase(Locale.ROOT);
        }
        return result;
    }
}
