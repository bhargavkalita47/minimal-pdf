package com.personal.minimalpdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * A TradingView-like compact vertical wheel: selected row is bright,
 * surrounding rows fade out, finger velocity settles to a nearby item.
 * Selection is committed on settle, not on every move, avoiding file reloads.
 */
public final class PickerWheel extends View {
    public interface Listener { void onSelected(int index); }

    private final List<String> labels = new ArrayList<>();
    private final List<String> clippedLabels = new ArrayList<>();
    private final android.graphics.Typeface regular =
        android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
    private final android.graphics.Typeface medium =
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float row;
    private final float slop;
    private final Listener listener;
    private float position;
    private float downY, lastY;
    private boolean moved;
    private ValueAnimator settle;
    private VelocityTracker velocity;

    public PickerWheel(Context context, List<String> options, int selected, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = getResources().getDisplayMetrics().density;
        this.row = 38f * density;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.labels.addAll(options);
        this.position = clamp(selected);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Scroll up or down to select");
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(Math.max(0, labels.size() - 1), value));
    }

    private void setPosition(float value) {
        position = clamp(value);
        invalidate();
    }

    /** Follow a finger-driven selector without waiting for the gesture to end.
     * Fractional positions keep the surrounding labels moving continuously. */
    public void followDrag(float position) {
        if (settle != null) settle.cancel();
        setPosition(position);
    }

    public void animateTo(int index) {
        settleAt(index, false);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Truncation is cached once instead of recomputed for every animation frame.
        clippedLabels.clear();
        float available = Math.max(1, w - 37*density);
        text.setTypeface(medium);
        for (String value : labels) {
            clippedLabels.add(TextUtils.ellipsize(value, text,
                available, TextUtils.TruncateAt.END).toString());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (labels.isEmpty() || w == 0 || h == 0) return;

        background.setColor(Color.argb(244, 28, 30, 35));
        canvas.drawRoundRect(0, 0, w, h, 17*density, 17*density, background);

        float center = h/2f;
        background.setColor(Color.argb(100, 68, 72, 79));
        canvas.drawRoundRect(6*density, center-row*.54f,
            w-6*density, center+row*.54f, 10*density, 10*density, background);

        int first = Math.max(0, (int)Math.floor(position) - 5);
        int last = Math.min(labels.size()-1, (int)Math.ceil(position)+5);
        for (int i=first; i<=last; i++) {
            float displacement = (i - position)*row;
            float centerY = center + displacement;
            if (centerY < -row || centerY > h+row) continue;
            float distance = Math.abs(displacement) / Math.max(1f, h*.5f);
            float fade = Math.max(0.10f, 1f - distance * distance * 1.16f);
            float weight = 1f - Math.min(1f, Math.abs(i-position));
            text.setColor(Color.WHITE);
            text.setAlpha(Math.round(255 * fade));
            text.setTypeface(weight > .6f ? medium : regular);
            String value = i < clippedLabels.size() ? clippedLabels.get(i) : labels.get(i);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(value, 20*density,
                centerY - (fm.ascent+fm.descent)/2f, text);
        }
    }

    private void settleAt(int target, boolean commit) {
        if (settle != null) settle.cancel();
        int selection = (int)clamp(target);
        if (Math.abs(position-selection) < .002f) {
            setPosition(selection);
            if (commit) listener.onSelected(selection);
            return;
        }
        settle = ValueAnimator.ofFloat(position, selection);
        float distance = Math.abs(position-selection);
        settle.setDuration((long)Math.min(300, 140 + distance*25f));
        settle.setInterpolator(new DecelerateInterpolator(1.4f));
        settle.addUpdateListener(animation -> setPosition((Float)animation.getAnimatedValue()));
        if (commit) {
            settle.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator animation) {
                    cancelled = true;
                }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) listener.onSelected(selection);
                }
            });
        }
        settle.start();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (labels.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null) settle.cancel();
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                downY = lastY = event.getY();
                moved = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(event);
                float delta = event.getY() - lastY;
                if (Math.abs(event.getY()-downY) > slop) moved = true;
                if (moved) setPosition(position - delta/row);
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (velocity != null) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000);
                }
                float v = velocity == null ? 0f : velocity.getYVelocity();
                if (velocity != null) { velocity.recycle(); velocity = null; }
                if (!moved) {
                    float tapOffset = (event.getY()-getHeight()/2f)/row;
                    settleAt(Math.round(position+tapOffset), true);
                    performClick();
                } else {
                    float momentum = Math.max(-5f, Math.min(5f, -v/(row*8f)));
                    settleAt(Math.round(position+momentum), true);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (velocity != null) { velocity.recycle(); velocity = null; }
                settleAt(Math.round(position), false);
                return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (settle != null) settle.cancel();
        if (velocity != null) { velocity.recycle(); velocity = null; }
        super.onDetachedFromWindow();
    }
}
