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

    /**
     * Scrolls the text along a word timing table, so the speed follows the words that are
     * actually being read instead of a constant speed over the whole sentence.
     */
    public void startTimedScroll(TranscriptScrollTimeline timeline, long positionMs,
                                 int availableWidth) {
        stopAutoScroll();
        if (timeline == null || availableWidth <= 0) return;

        long duration = timeline.getEndTime() - positionMs;
        if (duration <= 0) return;

        String text = getText().toString();
        if (text.isEmpty()) return;

        float textWidth = getPaint().measureText(text);
        float contentWidth = availableWidth - getPaddingLeft() - getPaddingRight();
        if (textWidth <= contentWidth) {
            setScrollX(0);
            return;
        }
        float scrollDistance = textWidth - contentWidth;

        // Width of every prefix of the text, so the animation can move by fractions of a
        // character instead of snapping from one character boundary to the next.
        final float[] prefixWidths = new float[text.length() + 1];
        float accumulated = 0f;
        for (int i = 0; i < text.length(); i++) {
            accumulated += getPaint().measureText(text, i, i + 1);
            prefixWidths[i + 1] = accumulated;
        }
        final float totalWidth = prefixWidths[text.length()];
        if (totalWidth <= 0f) return;

        scrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        scrollAnimator.setDuration(duration);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        final float center = contentWidth / 2f;
        scrollAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            float charIndex = timeline.charIndexAt(positionMs + (long) (progress * duration));
            float position = Math.max(0f, Math.min(charIndex, text.length()));
            int index = (int) position;
            float prefixWidth;
            if (index >= text.length()) {
                prefixWidth = totalWidth;
            } else {
                float fraction = position - index;
                prefixWidth = prefixWidths[index]
                        + (prefixWidths[index + 1] - prefixWidths[index]) * fraction;
            }
            // Keep the word being read in the middle of the window; the clamps leave the
            // first and the last words aligned to the edges instead.
            float target = prefixWidth * textWidth / totalWidth - center;
            setScrollX(Math.round(Math.max(0f, Math.min(target, scrollDistance))));
        });
        scrollAnimator.start();
    }

    public void stopAutoScroll() {
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        setScrollX(0);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAutoScroll();
        super.onDetachedFromWindow();
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