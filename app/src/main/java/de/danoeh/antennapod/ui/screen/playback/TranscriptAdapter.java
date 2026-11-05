package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.R;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.databinding.TranscriptItemBinding;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.model.feed.TranscriptWord;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.transcript.TranscriptTextUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptTiming;
import de.danoeh.antennapod.ui.transcript.TranscriptViewholder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TranscriptAdapter extends RecyclerView.Adapter<TranscriptViewholder> {
    private static final int MAX_CONTENT_WIDTH_DP = 720;
    private static final Object SEGMENT_HIGHLIGHT_PAYLOAD = new Object();
    private static final Object SEARCH_PAYLOAD = new Object();
    private static final int KIND_PLAIN = 0;
    private static final int KIND_WORD = 1;
    private static final int KIND_SENTENCE = 2;
    private final SegmentClickListener segmentClickListener;
    private final Context context;
    private FeedMedia media;
    private int highlightPosition = -1;
    private int highlightedSentence = -1;
    private int highlightedWord = -1;
    private long lastHighlightPositionMs = 0;
    private boolean positionEventsSuppressed = false;
    private RecyclerView recyclerView;
    private Runnable followRequester;
    private int languageMode = UserPreferences.getTranscriptLanguageMode();
    private int fontSizeStep = UserPreferences.getTranscriptFontSize();
    private float lineSpacingMultiplier;
    private int horizontalPaddingPx;
    private int availableWidthPx;
    private int verticalSpacingPx;
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    private boolean sentencePerLine = UserPreferences.isTranscriptSentencePerLine();
    private final TranscriptDisplayInfoBuilder displayInfoBuilder =
            new TranscriptDisplayInfoBuilder();
    private final Map<TranscriptViewholder, RowState> rowStates = new IdentityHashMap<>();
    private final int highlightTextColor;
    private final int searchMatchColor;
    private final int currentSearchMatchColor;
    private final int normalTextColor;
    private final int transcriptStrongColor;
    private final int transcriptMutedColor;
    private final List<SearchHit> searchHits = new ArrayList<>();
    private final Map<Integer, List<SearchHit>> searchHitsByPosition = new HashMap<>();
    private String[] searchTexts;
    private String searchQuery = "";
    private int currentSearchHit = -1;
    private int renderVersion;
    private int layoutVersion;

    public TranscriptAdapter(Context context, SegmentClickListener segmentClickListener) {
        this.context = context;
        this.segmentClickListener = segmentClickListener;
        setHasStableIds(true);
        TypedArray typedArray = context.obtainStyledAttributes(new int[]{R.attr.colorAccent});
        highlightTextColor = typedArray.getColor(0, 0);
        typedArray.recycle();
        searchMatchColor = withAlpha(highlightTextColor, 0x33);
        currentSearchMatchColor = withAlpha(highlightTextColor, 0x66);
        typedArray = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        normalTextColor = typedArray.getColor(0, 0);
        typedArray.recycle();
        typedArray = context.obtainStyledAttributes(new int[]{
                de.danoeh.antennapod.ui.common.R.attr.transcript_text_strong,
                de.danoeh.antennapod.ui.common.R.attr.transcript_text_muted});
        transcriptStrongColor = typedArray.getColor(0, normalTextColor);
        transcriptMutedColor = typedArray.getColor(1, normalTextColor);
        typedArray.recycle();
        readLayoutSettings();
    }

    private void readLayoutSettings() {
        float density = context.getResources().getDisplayMetrics().density;
        lineSpacingMultiplier = UserPreferences.getTranscriptLineSpacingMultiplier();
        horizontalPaddingPx = Math.round(UserPreferences.getTranscriptSideMarginsDp() * density);
        verticalSpacingPx = Math.round(UserPreferences.getTranscriptVerticalSpacingDp() * density);
    }

    public void refreshLayout() {
        Log.d("AsrJitter", "adapter refreshLayout");
        readLayoutSettings();
        layoutVersion++;
        notifyDataSetChanged();
    }

    public void setAvailableWidth(int widthPx) {
        if (availableWidthPx == widthPx) {
            return;
        }
        availableWidthPx = widthPx;
        notifyDataSetChanged();
    }

    private int contentPaddingPx() {
        if (availableWidthPx <= 0) {
            return horizontalPaddingPx;
        }
        int maxContentPx = Math.round(MAX_CONTENT_WIDTH_DP
                * context.getResources().getDisplayMetrics().density);
        return Math.max(horizontalPaddingPx, (availableWidthPx - maxContentPx) / 2);
    }

    private void applyLayout(TranscriptViewholder holder) {
        TextView content = holder.viewContent;
        TextView timecode = holder.viewTimecode;
        if (content.getLineSpacingMultiplier() != lineSpacingMultiplier) {
            content.setLineSpacing(0, lineSpacingMultiplier);
        }
        int paddingPx = contentPaddingPx();
        if (content.getPaddingLeft() != paddingPx
                || content.getPaddingRight() != paddingPx) {
            content.setPadding(paddingPx, 0, paddingPx, 0);
        }
        float contentTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                16 + fontSizeStep * 2, content.getResources().getDisplayMetrics());
        if (content.getTextSize() != contentTextSize) {
            content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 + fontSizeStep * 2);
        }
        float timecodeTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                14 + fontSizeStep * 2, content.getResources().getDisplayMetrics());
        if (timecode.getTextSize() != timecodeTextSize) {
            timecode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 + fontSizeStep * 2);
        }
        if (timecode.getPaddingLeft() != paddingPx
                || timecode.getPaddingRight() != paddingPx) {
            timecode.setPadding(paddingPx, 0, paddingPx, 0);
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) timecode.getLayoutParams();
        if (params.topMargin != verticalSpacingPx || params.bottomMargin != 0) {
            params.topMargin = verticalSpacingPx;
            params.bottomMargin = 0;
            timecode.setLayoutParams(params);
        }
    }

    public static final int LANGUAGE_MODE_BOTH = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_BOTH;
    public static final int LANGUAGE_MODE_PRIMARY_ONLY = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_PRIMARY_ONLY;
    public static final int LANGUAGE_MODE_SECONDARY_ONLY = UserPreferences.TRANSCRIPT_LANGUAGE_MODE_SECONDARY_ONLY;

    public void setLanguageMode(int mode) {
        this.languageMode = mode;
        UserPreferences.setTranscriptLanguageMode(mode);
        renderVersion++;
        searchTexts = null;
        displayInfoBuilder.clear();
        rebuildSearchHits();
        notifyDataSetChanged();
    }

    public int getLanguageMode() {
        return languageMode;
    }

    public void setSentencePerLine(boolean split) {
        this.sentencePerLine = split;
        UserPreferences.setTranscriptSentencePerLine(split);
        renderVersion++;
        displayInfoBuilder.clear();
        rebuildSearchHits();
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
                lastTouchX = event.getX() - holder.viewContent.getTotalPaddingLeft();
                lastTouchY = event.getY() - holder.viewContent.getTotalPaddingTop();
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
        highlightedSentence = -1;
        highlightedWord = -1;
        renderVersion++;
        searchTexts = null;
        displayInfoBuilder.clear();
        searchQuery = "";
        searchHits.clear();
        searchHitsByPosition.clear();
        currentSearchHit = -1;
        long position = Math.max(0, ((FeedMedia) media).getPosition());
        long offset = TranscriptTiming.offsetAt(((FeedMedia) media).getTranscript(),
                ((FeedMedia) media).getId(), position);
        lastHighlightPositionMs = Math.max(0, position - offset);
        Log.d("AsrJitter", "adapter setMedia id=" + ((FeedMedia) media).getId()
                + " segments=" + (((FeedMedia) media).getTranscript() == null
                    ? -1 : ((FeedMedia) media).getTranscript().getSegmentCount()));
        notifyDataSetChanged();
    }

    /** Appends a segment produced by a running ASR session without reloading the whole file. */
    public boolean appendTranscriptSegment(TranscriptSegment segment) {
        if (media == null || media.getTranscript() == null || segment == null) {
            return false;
        }
        Transcript transcript = media.getTranscript();
        int previousCount = transcript.getSegmentCount();
        transcript.addSegment(segment);
        searchTexts = null;
        if (!searchQuery.trim().isEmpty()) {
            rebuildSearchHits();
        }
        if (transcript.getSegmentCount() == previousCount + 1
                && transcript.getSegmentAt(previousCount) == segment) {
            Log.d("AsrJitter", "adapter insert idx=" + previousCount
                    + " count=" + transcript.getSegmentCount());
            notifyItemInserted(previousCount);
            if (recyclerView != null) {
                // Do not drop this: the reading-line gap is only added to the last row and
                // RecyclerView caches decoration insets per child, so without the invalidation
                // the row that stops being last keeps its old gap and a blank block appears.
                recyclerView.invalidateItemDecorations();
            }
        } else {
            Log.d("AsrJitter", "adapter append bulk count=" + transcript.getSegmentCount()
                    + " prev=" + previousCount);
            notifyDataSetChanged();
        }
        return true;
    }

    public void clear() {
        Log.d("AsrJitter", "adapter clear");
        media = null;
        highlightPosition = -1;
        highlightedSentence = -1;
        highlightedWord = -1;
        lastHighlightPositionMs = 0;
        renderVersion++;
        searchTexts = null;
        displayInfoBuilder.clear();
        searchQuery = "";
        searchHits.clear();
        searchHitsByPosition.clear();
        currentSearchHit = -1;
        rowStates.clear();
        notifyDataSetChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull TranscriptViewholder holder, int position) {
        if (media == null || media.getTranscript() == null) {
            return;
        }
        TranscriptSegment seg = media.getTranscript().getSegmentAt(position);
        RowState state = stateFor(holder);
        if (state.layoutVersion != layoutVersion) {
            applyLayout(holder);
            state.layoutVersion = layoutVersion;
        }
        bindTimecode(holder, position, seg);
        bindText(holder, position, position == highlightPosition);
    }

    private void bindTimecode(TranscriptViewholder holder, int position, TranscriptSegment seg) {
        String timecode = Converter.getDurationStringLong((int) seg.getStartTime());
        if (seg.getSpeaker() != null && !seg.getSpeaker().trim().isEmpty()) {
            if (position > 0 && seg.getSpeaker().equals(media.getTranscript()
                    .getSegmentAt(position - 1).getSpeaker())) {
                holder.viewTimecode.setVisibility(View.GONE);
            } else {
                holder.viewTimecode.setVisibility(View.VISIBLE);
                holder.viewTimecode.setText(timecode + " • " + seg.getSpeaker());
            }
        } else {
            Set<String> speakers = media.getTranscript().getSpeakers();
            if (speakers.isEmpty() && (position % 5 == 0)) {
                holder.viewTimecode.setVisibility(View.VISIBLE);
                holder.viewTimecode.setText(timecode);
            } else {
                holder.viewTimecode.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull TranscriptViewholder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || media == null || media.getTranscript() == null) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        if (payloads.contains(SEGMENT_HIGHLIGHT_PAYLOAD) || payloads.contains(SEARCH_PAYLOAD)
                || position == highlightPosition) {
            bindText(holder, position, position == highlightPosition);
        }
    }

    private void bindText(TranscriptViewholder holder, int position, boolean highlighted) {
        TranscriptSegment segment = segmentAt(position);
        if (segment == null) {
            return;
        }
        DisplayInfo info = displayInfoBuilder.get(segment, languageMode, sentencePerLine);
        if (info.wordStarts != null && info.wordEndTimes != null) {
            bindWordGradedHighlight(holder, position, segment, info, highlighted);
        } else if (highlighted) {
            applySentenceGradedHighlight(holder, position, segment, info);
        } else {
            RowState state = stateFor(holder);
            state.kind = KIND_PLAIN;
            state.version = renderVersion;
            state.position = position;
            state.text = info.text;
            holder.viewContent.setAlpha(1f);
            holder.viewContent.setTypeface(null, Typeface.NORMAL);
            holder.viewContent.setTextColor(
                    segment.getStartTime() > lastHighlightPositionMs
                            ? transcriptMutedColor : transcriptStrongColor);
            holder.viewTimecode.setTextColor(normalTextColor);
            setContentText(holder, position, info.text);
        }
    }

    private RowState stateFor(TranscriptViewholder holder) {
        RowState state = rowStates.get(holder);
        if (state == null) {
            state = new RowState();
            rowStates.put(holder, state);
        }
        return state;
    }

    private void applySentenceGradedHighlight(TranscriptViewholder holder, int position,
                                              TranscriptSegment segment, DisplayInfo info) {
        holder.viewContent.setAlpha(1f);
        holder.viewContent.setTypeface(null, Typeface.NORMAL);
        holder.viewContent.setTextColor(transcriptMutedColor);
        holder.viewTimecode.setTextColor(transcriptMutedColor);
        int sentence = sentenceIndexAt(segment, info, lastHighlightPositionMs);
        SpannableStringBuilder graded = TranscriptHighlightWriter.buildSentence(
                info, sentence, highlightTextColor, transcriptStrongColor,
                transcriptMutedColor);
        RowState state = stateFor(holder);
        state.kind = KIND_SENTENCE;
        state.version = renderVersion;
        state.position = position;
        state.text = info.text;
        setContentText(holder, position, graded);
    }

    private void bindWordGradedHighlight(TranscriptViewholder holder, int position,
                                         TranscriptSegment segment, DisplayInfo info,
                                         boolean highlighted) {
        RowState state = stateFor(holder);
        boolean fresh = state.kind == KIND_WORD && state.version == renderVersion
                && state.position == position && state.text == info.text
                && state.content != null;
        if (!fresh) {
            buildWordContent(holder, state, position, segment, info);
            holder.viewContent.setText(state.content);
        }
        holder.viewContent.setAlpha(1f);
        holder.viewContent.setTypeface(null, Typeface.NORMAL);
        holder.viewContent.setTextColor(transcriptMutedColor);
        holder.viewTimecode.setTextColor(transcriptMutedColor);
        if (state.wordSpans != null) {
            boolean changed = state.wordSpans.update(info, segment, position, highlighted,
                    lastHighlightPositionMs, highlightPosition,
                    highlightTextColor, transcriptStrongColor, transcriptMutedColor, !fresh);
            if (changed) {
                // Re-applying the text rebuilds the TextView display list. Mutating the span
                // colours alone does not, so the previously drawn colours would stay on screen.
                holder.viewContent.setText(state.content);
            }
        }
    }

    private void buildWordContent(TranscriptViewholder holder, RowState state, int position,
                                  TranscriptSegment segment, DisplayInfo info) {
        SpannableStringBuilder content = new SpannableStringBuilder(info.text);
        state.wordSpans = TranscriptHighlightWriter.WordSpans.build(
                content, info, segment, transcriptMutedColor);
        applySearchHighlights(position, content);
        state.kind = KIND_WORD;
        state.version = renderVersion;
        state.position = position;
        state.text = info.text;
        state.content = content;
    }

    private static final class RowState {
        private int kind = -1;
        private int version = -1;
        private int position = RecyclerView.NO_POSITION;
        private String text;
        private SpannableStringBuilder content;
        private TranscriptHighlightWriter.WordSpans wordSpans;
        private int layoutVersion = -1;
    }

    private int sentenceIndexAt(int segmentIndex, long position) {
        if (segmentIndex < 0 || segmentIndex >= media.getTranscript().getSegmentCount()) {
            return -1;
        }
        TranscriptSegment segment = media.getTranscript().getSegmentAt(segmentIndex);
        return sentenceIndexAt(segment, displayInfoBuilder.get(segment, languageMode, sentencePerLine), position);
    }

    private int sentenceIndexAt(TranscriptSegment segment, DisplayInfo info, long position) {
        int word = segment.getWordIndexAt(position);
        if (word >= 0 && info.wordStarts != null && word < info.wordStarts.length) {
            int wordOffset = info.wordStarts[word];
            if (wordOffset >= 0) {
                return sentenceIndexAtOffset(info, wordOffset);
            }
        }
        return TranscriptTextUtils.sentenceIndexAt(info.primaryStarts,
                TranscriptTextUtils.progressAt(segment, position), info.primaryLength);
    }

    private int sentenceIndexAtOffset(DisplayInfo info, int offset) {
        if (info.interleaved) {
            for (int i = 0; i < info.primaryDisplayStarts.length; i++) {
                if (offset >= info.primaryDisplayStarts[i]
                        && offset < info.primaryDisplayEnds[i]) {
                    return i;
                }
            }
            return pairIndexAt(info.pairStarts, offset);
        }
        int index = 0;
        for (int i = 1; i + 1 < info.primaryStarts.length; i++) {
            if (info.primaryStarts[i] <= offset) {
                index = i;
            }
        }
        return index;
    }

    private int pairIndexAt(int[] pairStarts, int offset) {
        int index = 0;
        for (int i = 0; i < pairStarts.length; i++) {
            if (pairStarts[i] <= offset) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    public int[] getSentenceBounds(TranscriptViewholder holder) {
        int position = holder.getAbsoluteAdapterPosition();
        if (position == RecyclerView.NO_POSITION || position != highlightPosition) {
            return null;
        }
        TranscriptSegment segment = segmentAt(position);
        if (segment == null) {
            return null;
        }
        DisplayInfo info = displayInfoBuilder.get(segment, languageMode, sentencePerLine);
        int sentence = sentenceIndexAt(segment, info, lastHighlightPositionMs);
        if (sentence < 0) {
            return null;
        }
        int start;
        int end;
        int word = segment.getWordIndexAt(lastHighlightPositionMs);
        if (word >= 0 && info.wordStarts != null && word < info.wordStarts.length
                && info.wordStarts[word] >= 0
                && info.wordEnds[word] > info.wordStarts[word]) {
            // Word-level rows can hold very long sentences; centre the word being read.
            start = info.wordStarts[word];
            end = info.wordEnds[word];
        } else if (info.interleaved) {
            if (sentence >= info.pairStarts.length) {
                return null;
            }
            start = info.primaryDisplayStarts[sentence];
            end = info.primaryDisplayEnds[sentence];
            int translationStart = info.translationDisplayStarts[sentence];
            int translationEnd = info.translationDisplayEnds[sentence];
            if (translationStart >= 0 && translationEnd > translationStart) {
                start = Math.min(start, translationStart);
                end = Math.max(end, translationEnd);
            }
        } else {
            if (sentence + 1 >= info.primaryStarts.length) {
                return null;
            }
            start = info.primaryStarts[sentence];
            end = info.primaryStarts[sentence + 1];
        }
        Layout layout = holder.viewContent.getLayout();
        if (layout == null || layout.getText() == null
                || start < 0 || end <= start || start >= layout.getText().length()) {
            return null;
        }
        int lastOffset = Math.min(end - 1, layout.getText().length() - 1);
        int firstLine = layout.getLineForOffset(start);
        int lastLine = layout.getLineForOffset(lastOffset);
        int top = holder.viewContent.getTop() + layout.getLineTop(firstLine);
        int bottom = holder.viewContent.getTop() + layout.getLineBottom(lastLine);
        return new int[]{top, bottom};
    }

    private long[] sentenceTimesAtTouch(TranscriptViewholder holder, TranscriptSegment segment) {
        Layout layout = holder.viewContent.getLayout();
        if (layout == null) {
            return new long[] {segment.getStartTime(), segment.getEndTime()};
        }
        int line = layout.getLineForVertical((int) lastTouchY);
        int offset = layout.getOffsetForHorizontal(line, lastTouchX);
        DisplayInfo info = displayInfoBuilder.get(segment, languageMode, sentencePerLine);
        long[] wordTimes = wordTimesAtOffset(segment, info, offset);
        if (wordTimes != null) {
            return wordTimes;
        }
        if (info.interleaved) {
            int pair = pairIndexAt(info.pairStarts, offset);
            return new long[] {
                    TranscriptTextUtils.sentenceStartTime(segment, info.primaryStarts, pair),
                    TranscriptTextUtils.sentenceEndTime(segment, info.primaryStarts, pair)};
        }
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

    private long[] wordTimesAtOffset(TranscriptSegment segment, DisplayInfo info, int offset) {
        if (info.wordStarts == null || !isPrimaryOffset(info, offset)) {
            return null;
        }
        int word = -1;
        for (int i = 0; i < info.wordStarts.length; i++) {
            int start = info.wordStarts[i];
            int end = info.wordEnds[i];
            if (start < 0 || end <= start) {
                continue;
            }
            if (offset >= start && offset < end) {
                word = i;
                break;
            }
            if (start <= offset) {
                word = i;
            }
        }
        if (word < 0) {
            return null;
        }
        TranscriptWord timing = segment.getWordTimings().get(word);
        return new long[] {timing.getStartTime(), timing.getEndTime()};
    }

    private boolean isPrimaryOffset(DisplayInfo info, int offset) {
        if (info.interleaved) {
            for (int i = 0; i < info.primaryDisplayStarts.length; i++) {
                if (offset >= info.primaryDisplayStarts[i] && offset < info.primaryDisplayEnds[i]) {
                    return true;
                }
            }
            return false;
        }
        int limit = info.split < 0 ? info.primaryLength : info.split;
        return offset >= 0 && offset < limit;
    }

    private void setContentText(TranscriptViewholder holder, int position, CharSequence text) {
        SpannableStringBuilder spannable = text instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) text : new SpannableStringBuilder(text);
        applySearchHighlights(position, spannable);
        holder.viewContent.setText(spannable);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void applySearchHighlights(int position, Spannable spannable) {
        List<SearchHit> hits = searchHitsByPosition.get(position);
        if (hits == null) {
            return;
        }
        for (SearchHit hit : hits) {
            int start = Math.max(0, hit.start);
            int end = Math.min(hit.end, spannable.length());
            if (start >= end) {
                continue;
            }
            int color = hit.index == currentSearchHit
                    ? currentSearchMatchColor : searchMatchColor;
            spannable.setSpan(new BackgroundColorSpan(color), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public int setSearchQuery(String query) {
        String next = query == null ? "" : query;
        if (next.equals(searchQuery)) {
            return searchHits.size();
        }
        Set<Integer> affected = hitPositions();
        searchQuery = next;
        renderVersion++;
        rebuildSearchHits();
        affected.addAll(hitPositions());
        notifySearchRowsChanged(affected);
        return searchHits.size();
    }

    public void clearSearch() {
        if (searchHits.isEmpty() && searchQuery.isEmpty()) {
            return;
        }
        Set<Integer> affected = hitPositions();
        searchQuery = "";
        renderVersion++;
        searchHits.clear();
        searchHitsByPosition.clear();
        currentSearchHit = -1;
        notifySearchRowsChanged(affected);
    }

    public int getSearchHitCount() {
        return searchHits.size();
    }

    public int getCurrentSearchHit() {
        return currentSearchHit;
    }

    public void setCurrentSearchHit(int index) {
        if (searchHits.isEmpty()) {
            currentSearchHit = -1;
            return;
        }
        int previous = currentSearchHit;
        currentSearchHit = Math.max(0, Math.min(index, searchHits.size() - 1));
        renderVersion++;
        Set<Integer> affected = new HashSet<>();
        if (previous >= 0) {
            affected.add(searchHits.get(previous).position);
        }
        affected.add(searchHits.get(currentSearchHit).position);
        notifySearchRowsChanged(affected);
    }

    private Set<Integer> hitPositions() {
        Set<Integer> positions = new HashSet<>();
        for (SearchHit hit : searchHits) {
            positions.add(hit.position);
        }
        return positions;
    }

    private void notifySearchRowsChanged(Set<Integer> positions) {
        if (positions.isEmpty()) {
            return;
        }
        RecyclerView.LayoutManager layoutManager =
                recyclerView == null ? null : recyclerView.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) {
            notifyDataSetChanged();
            return;
        }
        int first = ((LinearLayoutManager) layoutManager).findFirstVisibleItemPosition();
        int last = ((LinearLayoutManager) layoutManager).findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            notifyDataSetChanged();
            return;
        }
        for (int position : positions) {
            if (position >= first && position <= last) {
                notifyItemChanged(position, SEARCH_PAYLOAD);
            }
        }
    }

    public boolean hasTextSelection() {
        if (recyclerView == null) {
            return false;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (holder instanceof TranscriptViewholder
                    && ((TranscriptViewholder) holder).viewContent.hasSelection()) {
                return true;
            }
        }
        return false;
    }

    public int getSearchHitPosition(int index) {
        if (index < 0 || index >= searchHits.size()) {
            return -1;
        }
        return searchHits.get(index).position;
    }

    public int stepSearch(boolean forward) {
        if (searchHits.isEmpty()) {
            return -1;
        }
        int previous = currentSearchHit;
        if (forward) {
            currentSearchHit = (currentSearchHit + 1) % searchHits.size();
        } else {
            currentSearchHit = (currentSearchHit - 1 + searchHits.size()) % searchHits.size();
        }
        renderVersion++;
        if (previous >= 0) {
            notifyItemChanged(searchHits.get(previous).position, SEARCH_PAYLOAD);
        }
        notifyItemChanged(searchHits.get(currentSearchHit).position, SEARCH_PAYLOAD);
        return currentSearchHit;
    }

    public int[] getSearchHitBounds(TranscriptViewholder holder, int index) {
        if (index < 0 || index >= searchHits.size()) {
            return null;
        }
        SearchHit hit = searchHits.get(index);
        if (holder.getAbsoluteAdapterPosition() != hit.position) {
            return null;
        }
        Layout layout = holder.viewContent.getLayout();
        if (layout == null || layout.getText() == null) {
            return null;
        }
        int length = layout.getText().length();
        int start = Math.min(hit.start, length - 1);
        int end = Math.min(Math.max(hit.end - 1, start), length - 1);
        if (start < 0) {
            return null;
        }
        int firstLine = layout.getLineForOffset(start);
        int lastLine = layout.getLineForOffset(end);
        int top = holder.viewContent.getTop() + layout.getLineTop(firstLine);
        int bottom = holder.viewContent.getTop() + layout.getLineBottom(lastLine);
        return new int[]{top, bottom};
    }

    private void rebuildSearchHits() {
        searchHits.clear();
        searchHitsByPosition.clear();
        currentSearchHit = -1;
        if (searchQuery.trim().isEmpty() || media == null || media.getTranscript() == null) {
            return;
        }
        ensureSearchTexts();
        for (int i = 0; i < searchTexts.length; i++) {
            for (TranscriptSearch.Match match : TranscriptSearch.findAll(searchTexts[i], searchQuery)) {
                SearchHit hit = new SearchHit(i, match.start, match.end, searchHits.size());
                searchHits.add(hit);
                List<SearchHit> hits = searchHitsByPosition.get(i);
                if (hits == null) {
                    hits = new ArrayList<>();
                    searchHitsByPosition.put(i, hits);
                }
                hits.add(hit);
            }
        }
        if (!searchHits.isEmpty()) {
            currentSearchHit = 0;
        }
    }

    private void ensureSearchTexts() {
        int count = media.getTranscript().getSegmentCount();
        if (searchTexts != null && searchTexts.length == count) {
            return;
        }
        searchTexts = new String[count];
        for (int i = 0; i < count; i++) {
            searchTexts[i] = searchableText(media.getTranscript().getSegmentAt(i));
        }
    }

    /** Text the user can see, without building the whole display layout for every segment. */
    private String searchableText(TranscriptSegment segment) {
        String text = segment.getWords();
        int split = TranscriptTextUtils.languageSplitIndex(text);
        if (split >= 0) {
            if (languageMode == LANGUAGE_MODE_PRIMARY_ONLY) {
                return text.substring(0, split);
            }
            if (languageMode == LANGUAGE_MODE_SECONDARY_ONLY) {
                return text.substring(split + 1);
            }
        }
        return text;
    }

    public int getFontSizeStep() {
        return fontSizeStep;
    }

    public void setFontSizeStep(int step) {
        fontSizeStep = step;
        UserPreferences.setTranscriptFontSize(step);
        layoutVersion++;
        notifyDataSetChanged();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        if (positionEventsSuppressed) {
            return;
        }
        updatePlaybackPosition(event.getPosition());
    }

    public void setPositionEventsSuppressed(boolean suppressed) {
        positionEventsSuppressed = suppressed;
    }

    public void updatePlaybackPosition(long positionMs) {
        if (positionMs < 0 || media == null || media.getTranscript() == null) {
            return;
        }
        long offset = media.getTranscript().isGenerated()
                ? 0 : UserPreferences.getTranscriptTimeOffset(media.getId(), positionMs);
        long position = positionMs - offset;
        int index = media.getTranscript().findSegmentIndexBefore(position);
        if (index < 0 || index >= media.getTranscript().getSegmentCount()) {
            return;
        }
        lastHighlightPositionMs = position;
        int sentence = sentenceIndexAt(index, position);
        int word = wordIndexAt(index, position);
        boolean changed = false;
        if (index != highlightPosition) {
            final int previous = highlightPosition;
            Log.d("AsrJitter", "adapter highlight " + previous + " -> " + index
                    + " pos=" + position + " sentence=" + sentence + " word=" + word
                    + " n=" + media.getTranscript().getSegmentCount());
            highlightPosition = index;
            highlightedSentence = sentence;
            highlightedWord = word;
            refreshBoundRow(previous);
            refreshBoundRow(highlightPosition);
            changed = true;
        } else if (sentence != highlightedSentence) {
            highlightedSentence = sentence;
            highlightedWord = word;
            refreshBoundRow(highlightPosition);
            changed = true;
        } else if (word != highlightedWord) {
            highlightedWord = word;
            refreshBoundRow(highlightPosition);
            changed = true;
        }
        if (changed) {
            // Position updates arrive even when the highlight ticker is not running (1 s fallback
            // events, paused page); ask for a follow frame so centring does not depend on it.
            requestFollowFrame();
        }
    }

    public void setFollowRequester(Runnable requester) {
        this.followRequester = requester;
    }

    private void requestFollowFrame() {
        if (followRequester != null) {
            followRequester.run();
        }
    }

    /**
     * Re-applies the highlight on the bound row. Item change notifications are avoided here:
     * rebinding a row while the list is scrolled to its end clamps the scroll back by a row,
     * so the follow scrolls forward again and the list visibly jitters.
     */
    private void refreshBoundRow(int position) {
        if (position < 0) {
            return;
        }
        RecyclerView.ViewHolder holder = recyclerView == null ? null
                : recyclerView.findViewHolderForAdapterPosition(position);
        if (holder instanceof TranscriptViewholder) {
            // Replacing the text drops the user's selection; leave the row alone while selected.
            if (((TranscriptViewholder) holder).viewContent.hasSelection()) {
                return;
            }
            bindText((TranscriptViewholder) holder, position, position == highlightPosition);
        } else {
            notifyItemChanged(position, SEGMENT_HIGHLIGHT_PAYLOAD);
        }
    }

    private int wordIndexAt(int segmentIndex, long position) {
        if (segmentIndex < 0 || media == null || media.getTranscript() == null
                || segmentIndex >= media.getTranscript().getSegmentCount()) {
            return -1;
        }
        return media.getTranscript().getSegmentAt(segmentIndex).getWordIndexAt(position);
    }

    public void setHighlightPosition(int position) {
        if (position < 0 || position == highlightPosition) {
            return;
        }
        Log.d("AsrJitter", "adapter setHighlightPosition " + highlightPosition + " -> "
                + position);
        final int previous = highlightPosition;
        highlightPosition = position;
        highlightedSentence = -1;
        highlightedWord = -1;
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
        highlightedSentence = -1;
        highlightedWord = -1;
        if (previous >= 0 && previous != position) {
            notifyItemChanged(previous);
        }
        notifyItemChanged(position);
    }

    @Override
    public long getItemId(int position) {
        TranscriptSegment segment = segmentAt(position);
        return segment == null ? RecyclerView.NO_ID : segment.getStartTime();
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
        this.recyclerView = recyclerView;
        fontSizeStep = UserPreferences.getTranscriptFontSize();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (this.recyclerView == recyclerView) {
            this.recyclerView = null;
        }
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

    private static final class SearchHit {
        private final int position;
        private final int start;
        private final int end;
        private final int index;

        private SearchHit(int position, int start, int end, int index) {
            this.position = position;
            this.start = start;
            this.end = end;
            this.index = index;
        }
    }


}
