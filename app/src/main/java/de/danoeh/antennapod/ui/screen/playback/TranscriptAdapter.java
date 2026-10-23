package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.elevation.SurfaceColors;
import de.danoeh.antennapod.databinding.TranscriptItemBinding;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.ObjectUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jsoup.internal.StringUtil;
import android.util.TypedValue;
public class TranscriptAdapter extends RecyclerView.Adapter<TranscriptViewholder> {
    private static final int PAST_SENTENCE_ALPHA = 0xA6;
    private static final int FUTURE_SENTENCE_ALPHA = 0x8C;
    private static final Object SENTENCE_HIGHLIGHT_PAYLOAD = new Object();
    private final SegmentClickListener segmentClickListener;
    private final Context context;
    private FeedMedia media;
    private int prevHighlightPosition = -1;
    private int highlightPosition = -1;
    private int highlightedSentence = -1;
    private long lastHighlightPositionMs = 0;
    private int languageMode = LANGUAGE_MODE_BOTH;
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    private boolean sentencePerLine = false;
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

    public static final int LANGUAGE_MODE_BOTH = 0;
    public static final int LANGUAGE_MODE_PRIMARY_ONLY = 1;
    public static final int LANGUAGE_MODE_SECONDARY_ONLY = 2;

    public void setLanguageMode(int mode) {
        this.languageMode = mode;
        notifyDataSetChanged();
    }

    public int getLanguageMode() {
        return languageMode;
    }

    public void setSentencePerLine(boolean split) {
        this.sentencePerLine = split;
        notifyDataSetChanged();
    }

    public boolean isSentencePerLine() {
        return sentencePerLine;
    }

    @NonNull
    @Override
    public TranscriptViewholder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        return new TranscriptViewholder(TranscriptItemBinding.inflate(LayoutInflater.from(context), viewGroup, false));
    }

    public void setMedia(Playable media) {
        if (!(media instanceof FeedMedia)) {
            return;
        }
        this.media = (FeedMedia) media;
        highlightedSentence = -1;
        lastHighlightPositionMs = Math.max(0, ((FeedMedia) media).getPosition());
        notifyDataSetChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull TranscriptViewholder holder, int position) {
        if (media == null || media.getTranscript() == null) {
            return;
        }

        TranscriptSegment seg = media.getTranscript().getSegmentAt(position);
        holder.viewContent.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false;
        });
        holder.viewContent.setOnClickListener(v -> {
            if (segmentClickListener != null)  {
                segmentClickListener.onTranscriptClicked(position, seg,
                        sentenceStartTimeAtTouch(holder, seg));
            }
        });

        holder.viewContent.setSelectionListener(selectedText -> {
            if (selectedText != null && !selectedText.isEmpty()) {
                if (segmentClickListener != null) {
                    segmentClickListener.onTranscriptWordSelected(position, seg, selectedText);
                }
            }
        });

        String timecode = Converter.getDurationStringLong((int) seg.getStartTime());

        String displayText = getDisplayText(seg);

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
        applyGradedHighlight(holder, position);
    }

    private String getDisplayText(TranscriptSegment seg) {
        String displayText = seg.getWords();
        if (languageMode != LANGUAGE_MODE_BOTH && seg.getTranslationLine() >= 0) {
            int lineBreak = displayText.indexOf('\n');
            displayText = languageMode == LANGUAGE_MODE_PRIMARY_ONLY
                    ? displayText.substring(0, lineBreak)
                    : displayText.substring(lineBreak + 1);
        }
        if (sentencePerLine) {
            displayText = TranscriptTextUtils.splitIntoSentences(displayText);
        }
        return displayText;
    }

    private void applyGradedHighlight(TranscriptViewholder holder, int position) {
        TranscriptSegment segment = media.getTranscript().getSegmentAt(position);
        if (!TranscriptTextUtils.isLongSegment(segment) || hasSecondLanguage(segment)) {
            return;
        }
        String text = getDisplayText(segment);
        int length = text.length();
        int[] starts = TranscriptTextUtils.chunkStarts(text, length);
        if (starts.length < 3) {
            return;
        }
        int sentence = sentenceIndexAt(position, lastHighlightPositionMs);
        SpannableString graded = new SpannableString(text);
        for (int i = 0; i + 1 < starts.length; i++) {
            int start = Math.min(starts[i], length);
            int end = Math.min(starts[i + 1], length);
            if (start >= end) {
                continue;
            }
            int color;
            if (i == sentence) {
                color = highlightTextColor;
            } else if (i < sentence) {
                color = ColorUtils.setAlphaComponent(highlightTextColor, PAST_SENTENCE_ALPHA);
            } else {
                color = ColorUtils.setAlphaComponent(highlightTextColor, FUTURE_SENTENCE_ALPHA);
            }
            graded.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == sentence) {
                graded.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        holder.viewContent.setText(graded);
    }

    private int sentenceIndexAt(int segmentIndex, long position) {
        if (segmentIndex < 0 || segmentIndex >= media.getTranscript().getSegmentCount()) {
            return -1;
        }
        TranscriptSegment segment = media.getTranscript().getSegmentAt(segmentIndex);
        if (!TranscriptTextUtils.isLongSegment(segment) || hasSecondLanguage(segment)) {
            return -1;
        }
        String text = getDisplayText(segment);
        return TranscriptTextUtils.sentenceIndexAt(text,
                TranscriptTextUtils.progressAt(segment, position), text.length());
    }

    private long sentenceStartTimeAtTouch(TranscriptViewholder holder, TranscriptSegment segment) {
        Layout layout = holder.viewContent.getLayout();
        if (layout == null) {
            return segment.getStartTime();
        }
        int line = layout.getLineForVertical((int) lastTouchY);
        int offset = layout.getOffsetForHorizontal(line, lastTouchX);
        String text = getDisplayText(segment);
        int sentenceIndex = TranscriptTextUtils.sentenceIndexAt(text,
                offset / (float) Math.max(1, text.length()), text.length());
        return TranscriptTextUtils.sentenceStartTime(segment, text, sentenceIndex);
    }

    private boolean hasSecondLanguage(TranscriptSegment segment) {
        return languageMode == LANGUAGE_MODE_BOTH && segment.getTranslationLine() >= 0;
    }

    private void highlightViewHolder(TranscriptViewholder holder, boolean highlight) {
        int fontStep = UserPreferences.getTranscriptFontSize();
        if (highlight) {
            holder.viewContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19 + fontStep * 2);
            holder.viewContent.setAlpha(1.0f);
            holder.viewContent.setTypeface(null, Typeface.BOLD);

            holder.viewContent.setTextColor(highlightTextColor);

            holder.viewTimecode.setAlpha(0.8f);

        } else {
            holder.viewContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 + fontStep * 2);
            holder.viewContent.setAlpha(0.45f);
            holder.viewContent.setTypeface(null, Typeface.NORMAL);

            holder.viewContent.setTextColor(normalTextColor);

            holder.viewTimecode.setAlpha(0.3f);
        }
    }

    public int getFontSizeStep() {
        return UserPreferences.getTranscriptFontSize();
    }

    public void setFontSizeStep(int step) {
        UserPreferences.setTranscriptFontSize(step);
        notifyDataSetChanged();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        if (media == null || media.getTranscript() == null) {
            return;
        }
        long position = event.getPosition() - UserPreferences.getTranscriptTimeOffset(media.getId());
        int index = media.getTranscript().findSegmentIndexBefore(position);
        if (index < 0 || index > media.getTranscript().getSegmentCount()) {
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
            notifyItemChanged(prevHighlightPosition);
            notifyItemChanged(highlightPosition);
        } else if (sentence != highlightedSentence) {
            highlightedSentence = sentence;
            notifyItemChanged(highlightPosition, SENTENCE_HIGHLIGHT_PAYLOAD);
        }
    }

    public void setHighlightPosition(int position) {
        if (position < 0 || position == highlightPosition) {
            return;
        }
        int previous = highlightPosition;
        highlightPosition = position;
        prevHighlightPosition = position;
        highlightedSentence = -1;
        if (previous >= 0) {
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
        void onTranscriptClicked(int position, TranscriptSegment seg, long sentenceStartTime);

        void onTranscriptWordSelected(int position, TranscriptSegment seg, String selectedText);
    }
}
