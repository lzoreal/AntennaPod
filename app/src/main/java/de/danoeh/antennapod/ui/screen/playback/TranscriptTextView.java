package de.danoeh.antennapod.ui.screen.playback;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

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
}
