package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Selectable row text. Keep {@link #bringPointIntoView(int)} overridden: a selectable TextView
 * re-reveals its cursor before every draw, and because the rows live inside a RecyclerView that
 * request becomes a smoothScrollBy() which fights the transcript follow and shakes the list.
 */
public class TranscriptTextView extends AppCompatTextView {
    public interface SelectionListener {
        void onSelectionChanged(@Nullable String selectedText);
    }

    private SelectionListener selectionListener;

    public TranscriptTextView(Context context) {
        super(context);
    }

    public TranscriptTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TranscriptTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (selectionListener == null) {
            return;
        }
        CharSequence text = getText();
        if (text == null) {
            return;
        }
        if (selStart >= 0 && selEnd > selStart && selEnd <= text.length()) {
            selectionListener.onSelectionChanged(text.subSequence(selStart, selEnd).toString());
        } else {
            selectionListener.onSelectionChanged(null);
        }
    }

    /**
     * A selectable row re-reveals its cursor before every draw, so each highlight update makes
     * the host RecyclerView smooth-scroll the row back into view. That fights the transcript
     * follow, which owns the scroll position. Only honour the request while the user is actually
     * selecting text, which is what this call is for.
     */
    @Override
    public boolean bringPointIntoView(int offset) {
        return hasSelection() && super.bringPointIntoView(offset);
    }
}
