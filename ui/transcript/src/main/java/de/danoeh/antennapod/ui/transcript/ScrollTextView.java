package de.danoeh.antennapod.ui.transcript;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

public class ScrollTextView extends TextView {
    private ValueAnimator scrollAnimator;

    public ScrollTextView(Context context) { super(context); }
    public ScrollTextView(Context context, AttributeSet attrs) { super(context, attrs); }
    public ScrollTextView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    public void startAutoScroll(long durationMs, int availableWidth) {
        stopAutoScroll();
        if (durationMs <= 0 || availableWidth <= 0) return;

        String text = getText().toString();
        if (text.isEmpty()) return;

        float textWidth = getPaint().measureText(text);
        float contentWidth = availableWidth - getPaddingLeft() - getPaddingRight();

        if (textWidth <= contentWidth) {
            setScrollX(0);
            return;
        }

        float scrollDistance = textWidth - contentWidth;
        float requiredSpeed = scrollDistance / durationMs;  // px/ms
        float maxSpeed = 0.30f;  // Max speed of 300px/s, any faster and it becomes unreadable

        long actualDuration;
        if (requiredSpeed > maxSpeed) {
            // Text too long for the duration: scroll at max speed, which finishes early
            actualDuration = (long) (scrollDistance / maxSpeed);
        } else {
            // Normal case: finish scrolling exactly within durationMs
            actualDuration = durationMs;
        }

        scrollAnimator = ValueAnimator.ofFloat(0f, scrollDistance);
        scrollAnimator.setDuration(actualDuration);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(animation ->
                setScrollX((int) (float) animation.getAnimatedValue())
        );
        scrollAnimator.start();
    }

    public void stopAutoScroll() {
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        setScrollX(0);
    }
}