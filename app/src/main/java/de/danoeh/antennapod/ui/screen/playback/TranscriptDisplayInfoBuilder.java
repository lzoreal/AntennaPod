package de.danoeh.antennapod.ui.screen.playback;

import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
import de.danoeh.antennapod.ui.transcript.TranscriptTextUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds and caches {@link DisplayInfo} for transcript rows. */
final class TranscriptDisplayInfoBuilder {
    private static final int MAX_CACHE_SIZE = 512;

    private final Map<TranscriptSegment, DisplayInfo> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    void clear() {
        cache.clear();
    }

    DisplayInfo get(TranscriptSegment segment, int languageMode, boolean sentencePerLine) {
        DisplayInfo cached = cache.get(segment);
        if (cached != null) {
            return cached;
        }
        String text = segment.getWords();
        int split = TranscriptTextUtils.languageSplitIndex(text);
        boolean wordMappingAllowed = true;
        if (split >= 0) {
            if (languageMode == TranscriptAdapter.LANGUAGE_MODE_PRIMARY_ONLY) {
                text = text.substring(0, split);
                split = -1;
            } else if (languageMode == TranscriptAdapter.LANGUAGE_MODE_SECONDARY_ONLY) {
                text = text.substring(split + 1);
                split = -1;
                // 只有确实显示译文时才跳过词映射；英文单语转录在“仅译文”下仍显示原文
                wordMappingAllowed = false;
            }
        }
        DisplayInfo info;
        if (sentencePerLine && split >= 0
                && languageMode == TranscriptAdapter.LANGUAGE_MODE_BOTH) {
            info = buildInterleavedInfo(segment, text, split);
        } else {
            info = buildBlockInfo(segment, text, split, wordMappingAllowed, sentencePerLine);
        }
        cache.put(segment, info);
        if (cache.size() > MAX_CACHE_SIZE) {
            Iterator<TranscriptSegment> iterator = cache.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return info;
    }

    private DisplayInfo buildBlockInfo(TranscriptSegment segment, String text, int split,
                                       boolean wordMappingAllowed, boolean sentencePerLine) {
        String source = text;
        int sourceSplit = split;
        if (sentencePerLine) {
            text = TranscriptTextUtils.splitIntoSentences(text);
            if (split >= 0) {
                split = TranscriptTextUtils.languageSplitIndex(text);
            }
        }
        int primaryLength = split < 0 ? text.length() : split;
        int[] primaryStarts = TranscriptTextUtils.chunkStarts(text, primaryLength);
        int[] translationStarts = null;
        if (split >= 0 && split < text.length()) {
            String translation = text.substring(split + 1);
            translationStarts = TranscriptTextUtils.chunkStarts(translation, translation.length());
        }
        int[] wordStarts = null;
        int[] wordEnds = null;
        long[] wordEndTimes = null;
        if (wordMappingAllowed && segment.hasWordTimings()) {
            String rawPrimary = sourceSplit >= 0 ? source.substring(0, sourceSplit) : source;
            int[] rawSentenceStarts = TranscriptTextUtils.chunkStarts(
                    rawPrimary, rawPrimary.length());
            int[][] wordSpans = TranscriptWordMapper.map(segment, text,
                    new int[][] {{0, primaryLength}}, rawSentenceStarts);
            if (wordSpans != null) {
                wordStarts = wordSpans[0];
                wordEnds = wordSpans[1];
                wordEndTimes = computeWordEndTimes(segment);
            }
        }
        return new DisplayInfo(text, split, primaryLength, primaryStarts, translationStarts,
                wordStarts, wordEnds, wordEndTimes);
    }

    private DisplayInfo buildInterleavedInfo(TranscriptSegment segment, String text, int split) {
        String primaryBlock = TranscriptTextUtils.splitIntoSentences(text.substring(0, split));
        String translationBlock = TranscriptTextUtils.splitIntoSentences(text.substring(split + 1));
        List<String> primarySentences = sentencesOf(primaryBlock);
        List<String> translationSentences = sentencesOf(translationBlock);
        if (primarySentences.isEmpty() || translationSentences.isEmpty()) {
            return buildBlockInfo(segment, text, split, true, false);
        }
        int pairCount = primarySentences.size();
        StringBuilder display = new StringBuilder(text.length() + pairCount);
        int[] primaryDisplayStarts = new int[pairCount];
        int[] primaryDisplayEnds = new int[pairCount];
        int[] translationDisplayStarts = new int[pairCount];
        int[] translationDisplayEnds = new int[pairCount];
        int[] pairStarts = new int[pairCount];
        for (int i = 0; i < pairCount; i++) {
            if (i > 0) {
                display.append('\n');
            }
            pairStarts[i] = display.length();
            appendLine(display, primarySentences.get(i), primaryDisplayStarts, primaryDisplayEnds, i);
            String translation = translationLine(translationSentences, i, pairCount);
            if (!translation.isEmpty()) {
                display.append('\n');
                appendLine(display, translation, translationDisplayStarts, translationDisplayEnds, i);
            } else {
                translationDisplayStarts[i] = -1;
                translationDisplayEnds[i] = -1;
            }
        }
        int[] primaryStarts = TranscriptTextUtils.chunkStarts(primaryBlock, primaryBlock.length());
        int[] translationStarts = TranscriptTextUtils.chunkStarts(
                translationBlock, translationBlock.length());
        int[] wordStarts = null;
        int[] wordEnds = null;
        long[] wordEndTimes = null;
        if (segment.hasWordTimings()) {
            String rawPrimary = text.substring(0, split);
            int[] rawSentenceStarts = TranscriptTextUtils.chunkStarts(
                    rawPrimary, rawPrimary.length());
            int[][] ranges = new int[pairCount][];
            for (int i = 0; i < pairCount; i++) {
                ranges[i] = new int[] {primaryDisplayStarts[i], primaryDisplayEnds[i]};
            }
            int[][] wordSpans = TranscriptWordMapper.map(segment, display.toString(), ranges,
                    rawSentenceStarts);
            if (wordSpans != null) {
                wordStarts = wordSpans[0];
                wordEnds = wordSpans[1];
                wordEndTimes = computeWordEndTimes(segment);
            }
        }
        return new DisplayInfo(display.toString(), -1, primaryBlock.length(), primaryStarts,
                translationStarts, true, primaryDisplayStarts, primaryDisplayEnds,
                translationDisplayStarts, translationDisplayEnds, pairStarts,
                wordStarts, wordEnds, wordEndTimes);
    }

    static long[] computeWordEndTimes(TranscriptSegment segment) {
        List<TranscriptWord> words = segment.getWordTimings();
        long[] ends = new long[words.size()];
        long nextGreaterStart = Long.MAX_VALUE;
        for (int i = words.size() - 1; i >= 0; i--) {
            TranscriptWord word = words.get(i);
            long start = word.getStartTime();
            if (word.getEndTime() > start) {
                ends[i] = word.getEndTime();
            } else if (nextGreaterStart != Long.MAX_VALUE) {
                ends[i] = nextGreaterStart;
            } else {
                ends[i] = Math.max(start + 120, segment.getEndTime());
            }
            if (i > 0 && start > words.get(i - 1).getStartTime()) {
                nextGreaterStart = start;
            }
        }
        return ends;
    }

    private static List<String> sentencesOf(String block) {
        List<String> sentences = new ArrayList<>();
        int[] starts = TranscriptTextUtils.chunkStarts(block, block.length());
        for (int i = 0; i + 1 < starts.length; i++) {
            sentences.add(block.substring(starts[i], starts[i + 1]));
        }
        return sentences;
    }

    private static void appendLine(StringBuilder display, String sentence,
                                   int[] starts, int[] ends, int index) {
        String line = sentence.trim();
        starts[index] = display.length();
        display.append(line);
        ends[index] = display.length();
    }

    private static String translationLine(List<String> sentences, int index, int pairCount) {
        if (sentences.isEmpty()) {
            return "";
        }
        if (index == pairCount - 1 && sentences.size() > pairCount) {
            StringBuilder merged = new StringBuilder();
            for (int i = index; i < sentences.size(); i++) {
                if (merged.length() > 0) {
                    merged.append(' ');
                }
                merged.append(sentences.get(i).trim());
            }
            return merged.toString();
        }
        return sentences.get(Math.min(index, sentences.size() - 1)).trim();
    }
}
