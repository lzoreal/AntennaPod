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
        float maxSpeed = 0.30f;  // 最高速度 200px/s，再快就看不清了

        long actualDuration;
        if (requiredSpeed > maxSpeed) {
            // 文字太长时间太短，以最高速度滚，会提前滚完
            actualDuration = (long) (scrollDistance / maxSpeed);
        } else {
            // 正常情况：在 durationMs 内刚好滚完
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