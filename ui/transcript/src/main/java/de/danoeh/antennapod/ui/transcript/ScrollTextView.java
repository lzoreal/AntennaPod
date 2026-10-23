package de.danoeh.antennapod.ui.transcript;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

public class ScrollTextView extends TextView {
    private static final float OUTLINE_WIDTH_DP = 1.5f;
    private final float outlineWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, OUTLINE_WIDTH_DP, getResources().getDisplayMetrics());
    private final Shader outlineShader = new LinearGradient(0f, 0f, 1f, 1f,
            Color.BLACK, Color.BLACK, Shader.TileMode.CLAMP);
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

    @Override
    protected void onDraw(Canvas canvas) {
        TextPaint paint = getPaint();
        int textColor = getCurrentTextColor();
        int previousColor = paint.getColor();
        Shader previousShader = paint.getShader();
        Paint.Style previousStyle = paint.getStyle();
        float previousStrokeWidth = paint.getStrokeWidth();
        Paint.Join previousStrokeJoin = paint.getStrokeJoin();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(outlineWidth);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setShader(outlineShader);
        super.onDraw(canvas);

        paint.setStyle(previousStyle);
        paint.setStrokeWidth(previousStrokeWidth);
        paint.setStrokeJoin(previousStrokeJoin);
        paint.setShader(previousShader);
        paint.setColor(textColor);
        super.onDraw(canvas);

        paint.setColor(previousColor);
    }
}