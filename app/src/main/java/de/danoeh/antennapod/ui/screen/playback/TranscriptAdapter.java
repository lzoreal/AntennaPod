package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.databinding.TranscriptItemBinding;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.transcript.TranscriptTextUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jsoup.internal.StringUtil;

public class TranscriptAdapter extends RecyclerView.Adapter<TranscriptViewholder> {
    private static final int FUTURE_SENTENCE_ALPHA = 0x8C;
    private static final Object SENTENCE_HIGHLIGHT_PAYLOAD = new Object();
    private static final Object SEGMENT_HIGHLIGHT_PAYLOAD = new Object();
    private final SegmentClickListener segmentClickListener;
    private final Context context;
    private FeedMedia media;
    private int prevHighlightPosition = -1;
    private int highlightPosition = -1;
    private int highlightedSentence = -1;
    private long lastHighlightPositionMs = 0;
    private int languageMode = UserPreferences.getTranscriptLanguageMode();
    private int fontSizeStep = UserPreferences.getTranscriptFontSize();
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    private boolean sentencePerLine = UserPreferences.isTranscriptSentencePerLine();
    private static final int MAX_DISPLAY_CACHE_SIZE = 64;
    private final Map<TranscriptSegment, DisplayInfo> displayCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final int highlightTextColor;
    private final int normalTextColor;

    public TranscriptAdapter(Context context, SegmentClickListener segmentClickListener) {
        this.context = context;
        this.segmentClickListener = segmentClickListener;
        TypedArray typedArray = context.obtainStyledAttributes(new int[]{androidx.appcompat.R.attr.colorAccent});
        highlightTextColor = typedArray.getColor(0, 0);
        typedArray.recycle();
        typedArray = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        normalTextColor = typedArray.getColor(0, 0);
        typedArray.recycle();
    }

    public static final int LANGUAGE_MODE_BOTH = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH;
    public static final int LANGUAGE_MODE_PRIMARY_ONLY = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY;
    public static final int LANGUAGE_MODE_SECONDARY_ONLY = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_SECONDARY_ONLY;

    public void setLanguageMode(int mode) {
        this.languageMode = mode;
        UserPreferences.setTranscriptLanguageMode(mode);
        displayCache.clear();
        notifyDataSetChanged();
    }

    public int getLanguageMode() {
        return languageMode;
    }

    public void setSentencePerLine(boolean split) {
        this.sentencePerLine = split;
        UserPreferences.setTranscriptSentencePerLine(split);
        displayCache.clear();
        notifyDataSetChanged();
    }

    public boolean isSentencePerLine() {
        return sentencePerLine;
    }

    @NonNull
    @Override
    public TranscriptViewholder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        TranscriptViewholder holder = new TranscriptViewholder(
                TranscriptItemBinding.inflate(LayoutInflater.from(context), viewGroup, false));
        holder.viewContent.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false;
        });
        holder.viewContent.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            TranscriptSegment segment = segmentAt(position);
            if (segment != null && segmentClickListener != null) {
                long[] sentenceTimes = sentenceTimesAtTouch(holder, segment);
                segmentClickListener.onTranscriptClicked(position, segment,
                        sentenceTimes[0], sentenceTimes[1]);
            }
        });
        holder.viewContent.setSelectionListener(selectedText -> {
            int position = holder.getBindingAdapterPosition();
            TranscriptSegment segment = segmentAt(position);
            if (segment != null && segmentClickListener != null
                    && selectedText != null && !selectedText.isEmpty()) {
                segmentClickListener.onTranscriptWordSelected(position, segment, selectedText);
            }
        });
        return holder;
    }

    private TranscriptSegment segmentAt(int position) {
        if (position == RecyclerView.NO_POSITION || media == null
                || media.getTranscript() == null
                || position >= media.getTranscript().getSegmentCount()) {
            return null;
        }
        return media.getTranscript().getSegmentAt(position);
    }

    public void setMedia(Playable media) {
        if (!(media instanceof FeedMedia)) {
            return;
        }
        this.media = (FeedMedia) media;
        highlightPosition = -1;
        prevHighlightPosition = -1;
        highlightedSentence = -1;
        displayCache.clear();
        long offset = UserPreferences.getTranscriptTimeOffset(((FeedMedia) media).getId());
        lastHighlightPositionMs = Math.max(0, ((FeedMedia) media).getPosition() - offset);
        notifyDataSetChanged();
    }

    public void clear() {
        media = null;
        highlightPosition = -1;
        prevHighlightPosition = -1;
        highlightedSentence = -1;
        lastHighlightPositionMs = 0;
        displayCache.clear();
        notifyDataSetChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull TranscriptViewholder holder, int position) {
        if (media == null || media.getTranscript() == null) {
            return;
        }

        TranscriptSegment seg = media.getTranscript().getSegmentAt(position);
        String timecode = Converter.getDurationStringLong((int) seg.getStartTime());

        String displayText = getDisplayInfo(seg).text;

        if (!StringUtil.isBlank(seg.getSpeaker())) {
            if (position > 0 && media.getTranscript()
                    .getSegmentAt(position - 1).getSpeaker().equals(seg.getSpeaker())) {
                holder.viewTimecode.setVisibility(View.GONE);
                holder.viewContent.setText(displayText);
            } else {
                holder.viewTimecode.setVisibility(View.VISIBLE);
                holder.viewTimecode.setText(timecode + " • " + seg.getSpeaker());
                holder.viewContent.setText(displayText);
            }
        } else {
            Set<String> speakers = media.getTranscript().getSpeakers();
            if (speakers.isEmpty() && (position % 5 == 0)) {
                holder.viewTimecode.setVisibility(View.VISIBLE);
                holder.viewTimecode.setText(timecode);
            } else {
                holder.viewTimecode.setVisibility(View.GONE);
            }
            holder.viewContent.setText(displayText);
        }

        highlightViewHolder(holder, position == highlightPosition);

        if (position == highlightPosition) {
            applyGradedHighlight(holder, position);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull TranscriptViewholder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || media == null || media.getTranscript() == null) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        if (payloads.contains(SEGMENT_HIGHLIGHT_PAYLOAD)) {
            highlightViewHolder(holder, position == highlightPosition);
            if (position != highlightPosition) {
                holder.viewContent.setText(getDisplayInfo(
                        media.getTranscript().getSegmentAt(position)).text);
            }
        }
        if (position == highlightPosition) {
            applyGradedHighlight(holder, position);
        }
    }

    private DisplayInfo getDisplayInfo(TranscriptSegment segment) {
        DisplayInfo cached = displayCache.get(segment);
        if (cached != null) {
            return cached;
        }
        String text = segment.getWords();
        int split = TranscriptTextUtils.languageSplitIndex(text);
        if (split >= 0) {
            if (languageMode == LANGUAGE_MODE_PRIMARY_ONLY) {
                text = text.substring(0, split);
                split = -1;
            } else if (languageMode == LANGUAGE_MODE_SECONDARY_ONLY) {
                text = text.substring(split + 1);
                split = -1;
            }
        }
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
        DisplayInfo info = new DisplayInfo(text, split, primaryLength, primaryStarts, translationStarts);
        displayCache.put(segment, info);
        if (displayCache.size() > MAX_DISPLAY_CACHE_SIZE) {
            Iterator<TranscriptSegment> iterator = displayCache.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return info;
    }

    private void applyGradedHighlight(TranscriptViewholder holder, int position) {
        TranscriptSegment segment = media.getTranscript().getSegmentAt(position);
        DisplayInfo info = getDisplayInfo(segment);
        if (info.primaryStarts.length < 3) {
            return;
        }
        int sentence = sentenceIndexAt(segment, info, lastHighlightPositionMs);
        SpannableString graded = new SpannableString(info.text);
        colorSentences(graded, info.primaryStarts, sentence, 0, info.primaryLength);
        if (info.split >= 0 && info.translationStarts != null) {
            int translationLength = info.text.length() - info.split - 1;
            int translationSentence = Math.min(sentence, info.translationStarts.length - 2);
            colorSentences(graded, info.translationStarts, translationSentence,
                    info.split + 1, translationLength);
        }
        holder.viewContent.setText(graded);
    }

    private void colorSentences(SpannableString graded, int[] starts, int sentence, int offset, int length) {
        if (starts.length < 3) {
            return;
        }
        for (int i = 0; i + 1 < starts.length; i++) {
            int start = offset + Math.min(starts[i], length);
            int end = offset + Math.min(starts[i + 1], length);
            if (start >= end) {
                continue;
            }
            int color = i == sentence
                    ? highlightTextColor
                    : ColorUtils.setAlphaComponent(highlightTextColor, FUTURE_SENTENCE_ALPHA);
            graded.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == sentence) {
                graded.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private int sentenceIndexAt(int segmentIndex, long position) {
        if (segmentIndex < 0 || segmentIndex >= media.getTranscript().getSegmentCount()) {
            return -1;
        }
        TranscriptSegment segment = media.getTranscript().getSegmentAt(segmentIndex);
        return sentenceIndexAt(segment, getDisplayInfo(segment), position);
    }

    private int sentenceIndexAt(TranscriptSegment segment, DisplayInfo info, long position) {
        return TranscriptTextUtils.sentenceIndexAt(info.primaryStarts,
                TranscriptTextUtils.progressAt(segment, position), info.primaryLength);
    }

    private long[] sentenceTimesAtTouch(TranscriptViewholder holder, TranscriptSegment segment) {
        Layout layout = holder.viewContent.getLayout();
        if (layout == null) {
            return new long[] {segment.getStartTime(), segment.getEndTime()};
        }
        int line = layout.getLineForVertical((int) lastTouchY);
        int offset = layout.getOffsetForHorizontal(line, lastTouchX);
        DisplayInfo info = getDisplayInfo(segment);
        if (info.split >= 0 && info.translationStarts != null && offset > info.split) {
            int translationLength = info.text.length() - info.split - 1;
            float progress = (offset - info.split - 1) / (float) Math.max(1, translationLength);
            int sentenceIndex = TranscriptTextUtils.sentenceIndexAt(info.translationStarts, progress,
                    translationLength);
            return new long[] {
                    TranscriptTextUtils.sentenceStartTime(segment, info.translationStarts, sentenceIndex),
                    TranscriptTextUtils.sentenceEndTime(segment, info.translationStarts, sentenceIndex)};
        }
        float progress = offset / (float) Math.max(1, info.primaryLength);
        int sentenceIndex = TranscriptTextUtils.sentenceIndexAt(info.primaryStarts, progress,
                info.primaryLength);
        return new long[] {
                TranscriptTextUtils.sentenceStartTime(segment, info.primaryStarts, sentenceIndex),
                TranscriptTextUtils.sentenceEndTime(segment, info.primaryStarts, sentenceIndex)};
    }

    private void highlightViewHolder(TranscriptViewholder holder, boolean highlight) {
        if (highlight) {
            holder.viewContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19 + fontSizeStep * 2);
            holder.viewContent.setAlpha(1.0f);
            holder.viewContent.setTypeface(null, Typeface.BOLD);

            holder.viewContent.setTextColor(highlightTextColor);

            holder.viewTimecode.setAlpha(0.8f);

        } else {
            holder.viewContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 + fontSizeStep * 2);
            holder.viewContent.setAlpha(0.45f);
            holder.viewContent.setTypeface(null, Typeface.NORMAL);

            holder.viewContent.setTextColor(normalTextColor);

            holder.viewTimecode.setAlpha(0.3f);
        }
    }

    public int getFontSizeStep() {
        return fontSizeStep;
    }

    public void setFontSizeStep(int step) {
        fontSizeStep = step;
        UserPreferences.setTranscriptFontSize(step);
        notifyDataSetChanged();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        updatePlaybackPosition(event.getPosition());
    }

    public void updatePlaybackPosition(long positionMs) {
        if (positionMs < 0 || media == null || media.getTranscript() == null) {
            return;
        }
        long position = positionMs - UserPreferences.getTranscriptTimeOffset(media.getId());
        int index = media.getTranscript().findSegmentIndexBefore(position);
        if (index < 0 || index >= media.getTranscript().getSegmentCount()) {
            return;
        }
        if (prevHighlightPosition != highlightPosition) {
            prevHighlightPosition = highlightPosition;
        }
        lastHighlightPositionMs = position;
        int sentence = sentenceIndexAt(index, position);
        if (index != highlightPosition) {
            highlightPosition = index;
            highlightedSentence = sentence;
            if (prevHighlightPosition >= 0) {
                notifyItemChanged(prevHighlightPosition, SEGMENT_HIGHLIGHT_PAYLOAD);
            }
            notifyItemChanged(highlightPosition, SEGMENT_HIGHLIGHT_PAYLOAD);
        } else if (sentence != highlightedSentence) {
            highlightedSentence = sentence;
            notifyItemChanged(highlightPosition, SENTENCE_HIGHLIGHT_PAYLOAD);
        }
    }

    public void setHighlightPosition(int position) {
        if (position < 0 || position == highlightPosition) {
            return;
        }
        final int previous = highlightPosition;
        highlightPosition = position;
        prevHighlightPosition = position;
        highlightedSentence = -1;
        if (previous >= 0) {
            notifyItemChanged(previous);
        }
        notifyItemChanged(position);
    }

    public void setHighlightPosition(int position, long positionMs) {
        lastHighlightPositionMs = Math.max(0, positionMs);
        if (position < 0) {
            return;
        }
        final int previous = highlightPosition;
        highlightPosition = position;
        prevHighlightPosition = position;
        highlightedSentence = -1;
        if (previous >= 0 && previous != position) {
            notifyItemChanged(previous);
        }
        notifyItemChanged(position);
    }

    @Override
    public int getItemCount() {
        if (media == null) {
            return 0;
        }

        if (media.getTranscript() == null) {
            return 0;
        }
        return media.getTranscript().getSegmentCount();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        fontSizeStep = UserPreferences.getTranscriptFontSize();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        EventBus.getDefault().unregister(this);
    }

    public int getHighlightPosition() {
        return highlightPosition;
    }

    public interface SegmentClickListener {
        void onTranscriptClicked(int position, TranscriptSegment seg,
                                 long sentenceStartTime, long sentenceEndTime);

        void onTranscriptWordSelected(int position, TranscriptSegment seg, String selectedText);
    }

    private static final class DisplayInfo {
        private final String text;
        private final int split;
        private final int primaryLength;
        private final int[] primaryStarts;
        private final int[] translationStarts;

        private DisplayInfo(String text, int split, int primaryLength,
                            int[] primaryStarts, int[] translationStarts) {
            this.text = text;
            this.split = split;
            this.primaryLength = primaryLength;
            this.primaryStarts = primaryStarts;
            this.translationStarts = translationStarts;
        }
    }
}
