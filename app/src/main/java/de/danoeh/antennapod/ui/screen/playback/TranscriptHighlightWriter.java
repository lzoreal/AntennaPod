package de.danoeh.antennapod.ui.screen.playback;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

/** Writes the sentence and word grading spans for one transcript row. */
final class TranscriptHighlightWriter {
    private TranscriptHighlightWriter() {
    }

    static SpannableStringBuilder buildSentence(DisplayInfo info, int sentence,
                                                int currentColor, int strongColor,
                                                int mutedColor) {
        SpannableStringBuilder graded = new SpannableStringBuilder(info.text);
        if (info.interleaved) {
            for (int i = 0; i < info.pairStarts.length; i++) {
                colorPairLine(graded, info.primaryDisplayStarts, info.primaryDisplayEnds,
                        i, sentence, currentColor, strongColor, mutedColor);
                colorPairLine(graded, info.translationDisplayStarts,
                        info.translationDisplayEnds, i, sentence,
                        currentColor, strongColor, mutedColor);
            }
        } else {
            colorSentences(graded, info.primaryStarts, sentence, 0, info.primaryLength,
                    currentColor, strongColor, mutedColor);
            if (info.split >= 0 && info.translationStarts != null) {
                int translationLength = info.text.length() - info.split - 1;
                int translationSentence = Math.min(sentence,
                        info.translationStarts.length - 2);
                colorSentences(graded, info.translationStarts, translationSentence,
                        info.split + 1, translationLength, currentColor, strongColor, mutedColor);
            }
        }
        return graded;
    }

    private static void colorPairLine(Spannable graded, int[] starts, int[] ends,
                                      int index, int current, int currentColor,
                                      int strongColor, int mutedColor) {
        if (index >= starts.length || starts[index] < 0 || ends[index] <= starts[index]) {
            return;
        }
        int color;
        if (index == current) {
            color = currentColor;
        } else if (index < current) {
            color = strongColor;
        } else {
            color = mutedColor;
        }
        graded.setSpan(new ForegroundColorSpan(color),
                starts[index], ends[index], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (index == current) {
            graded.setSpan(new FakeBoldSpan(),
                    starts[index], ends[index], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void colorSentences(Spannable graded, int[] starts, int sentence,
                                       int offset, int length, int currentColor,
                                       int strongColor, int mutedColor) {
        for (int i = 0; i + 1 < starts.length; i++) {
            int start = offset + Math.min(starts[i], length);
            int end = offset + Math.min(starts[i + 1], length);
            if (start >= end) {
                continue;
            }
            int color;
            if (i == sentence) {
                color = currentColor;
            } else if (i < sentence) {
                color = strongColor;
            } else {
                color = mutedColor;
            }
            graded.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == sentence) {
                graded.setSpan(new FakeBoldSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** Per-word spans of one row; the colors are updated in place while the word changes. */
    static final class WordSpans {
        private final MutableColorSpan[] colors;
        private final MutableBoldSpan[] bolds;
        private int activeWord = Integer.MIN_VALUE;
        private boolean wordActive;
        private boolean beforeRow;
        private boolean highlighted;

        private WordSpans(MutableColorSpan[] colors, MutableBoldSpan[] bolds) {
            this.colors = colors;
            this.bolds = bolds;
        }

        static WordSpans build(SpannableStringBuilder content, DisplayInfo info,
                               TranscriptSegment segment, int mutedColor) {
            int count = info.wordStarts.length;
            MutableColorSpan[] colors = new MutableColorSpan[count];
            MutableBoldSpan[] bolds = new MutableBoldSpan[count];
            int words = Math.min(count, segment.getWordTimings().size());
            for (int i = 0; i < words; i++) {
                int start = info.wordStarts[i];
                int end = info.wordEnds[i];
                if (start < 0 || end <= start || end > content.length()) {
                    continue;
                }
                colors[i] = new MutableColorSpan(mutedColor);
                bolds[i] = new MutableBoldSpan(false);
                content.setSpan(colors[i], start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                content.setSpan(bolds[i], start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return new WordSpans(colors, bolds);
        }

        boolean update(DisplayInfo info, TranscriptSegment segment,
                       int position, boolean highlighted, long positionMs, int highlightPosition,
                       int currentColor, int strongColor, int mutedColor, boolean force) {
            int activeWord = highlighted ? segment.getWordIndexAt(positionMs) : -1;
            long[] wordEnds = info.wordEndTimes;
            long activeWordEnd = activeWord >= 0 && activeWord < wordEnds.length
                    ? wordEnds[activeWord] : Long.MAX_VALUE;
            boolean wordActive = highlighted && activeWord >= 0 && positionMs < activeWordEnd;
            boolean beforeRow = position < highlightPosition;
            if (!force && this.activeWord == activeWord && this.wordActive == wordActive
                    && this.beforeRow == beforeRow && this.highlighted == highlighted) {
                return false;
            }
            boolean changed = false;
            for (int i = 0; i < colors.length; i++) {
                MutableColorSpan colorSpan = colors[i];
                if (colorSpan == null) {
                    continue;
                }
                long wordEnd = i < wordEnds.length ? wordEnds[i] : Long.MAX_VALUE;
                boolean active = highlighted && i == activeWord && positionMs < wordEnd;
                boolean past = !active && (beforeRow || wordEnd <= positionMs);
                int color = active ? currentColor : past ? strongColor : mutedColor;
                if (colorSpan.getColor() != color) {
                    colorSpan.setColor(color);
                    changed = true;
                }
                boolean bold = active;
                if (bolds[i].isBold() != bold) {
                    bolds[i].setBold(bold);
                    changed = true;
                }
            }
            this.activeWord = activeWord;
            this.wordActive = wordActive;
            this.beforeRow = beforeRow;
            this.highlighted = highlighted;
            return changed;
        }
    }

    private static final class MutableColorSpan extends CharacterStyle {
        private int color;

        private MutableColorSpan(int color) {
            this.color = color;
        }

        private int getColor() {
            return color;
        }

        private void setColor(int color) {
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint textPaint) {
            textPaint.setColor(color);
        }
    }

    private static final class MutableBoldSpan extends CharacterStyle {
        private boolean bold;

        private MutableBoldSpan(boolean bold) {
            this.bold = bold;
        }

        private boolean isBold() {
            return bold;
        }

        private void setBold(boolean bold) {
            this.bold = bold;
        }

        @Override
        public void updateDrawState(TextPaint textPaint) {
            textPaint.setFakeBoldText(bold);
        }
    }

    private static final class FakeBoldSpan extends CharacterStyle {
        @Override
        public void updateDrawState(TextPaint textPaint) {
            textPaint.setFakeBoldText(true);
        }
    }
}
