package com.personal.minimalpdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Minimal position-aware page thumb. Remains touchable when faded, so a user
 * can scrub by touching the right edge even when the reader chrome is hidden.
 */
public final class PageScrubber extends View {
    public interface Listener { void onScrub(int zeroBasedPage); }

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final float density;
    private int count;
    private int selected;
    private float displayed;
    private boolean dragging;
    private boolean chromeVisible = true;
    private int lastDelivered = -1;
    private int pending = -1;
    private boolean scheduled;
    private Runnable fadeCallback;
    private ValueAnimator slide;

    public PageScrubber(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        label.setTypeface(android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        label.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setFocusable(true);
        setAlpha(0f);
        setContentDescription("Scrub pages vertically");
    }

    private float dp(float n) { return n * density; }
    private float top() { return dp(20); }
    private float bottom() { return Math.max(top() + dp(1), getHeight() - dp(20)); }
    private float range() { return Math.max(1f, bottom() - top()); }

    private void cancelFade() {
        if (fadeCallback != null) removeCallbacks(fadeCallback);
        fadeCallback = null;
        animate().cancel();
    }

    private void appear() {
        if (count == 0) return;
        cancelFade();
        animate().alpha(1f).setDuration(105).start();
    }

    private void showBriefly() {
        appear();
        if (dragging) return;
        fadeCallback = () -> animate().alpha(0f).setDuration(230).start();
        postDelayed(fadeCallback, chromeVisible ? 1350 : 900);
    }

    public void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        if (!visible && !dragging) {
            cancelFade();
            animate().alpha(0f).setDuration(165).start();
        } else if (visible && count > 0) {
            showBriefly();
        }
    }

    public void setPage(int page, int total) {
        int newCount = Math.max(0, total);
        int target = newCount == 0 ? 0 : Math.max(0, Math.min(newCount - 1, page));
        boolean newDocument = newCount != count;
        boolean changed = target != selected;
        count = newCount;
        selected = target;
        setVisibility(count > 0 ? VISIBLE : GONE);

        if (count == 0) {
            if (slide != null) slide.cancel();
            cancelFade();
            setAlpha(0f);
            return;
        }

        if (newDocument || dragging) {
            if (slide != null) slide.cancel();
            displayed = selected;
        } else if (changed) {
            if (slide != null) slide.cancel();
            slide = ValueAnimator.ofFloat(displayed, selected);
            slide.setDuration(220);
            slide.setInterpolator(new DecelerateInterpolator(1.6f));
            slide.addUpdateListener(a -> {
                displayed = (float)a.getAnimatedValue();
                invalidate();
            });
            slide.start();
        }

        if (changed || newDocument) showBriefly();
        setContentDescription("Page " + (selected + 1) + " of " + count
            + ". Drag vertically to change page");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count <= 0) return;
        float fraction = count < 2 ? 0f : displayed / (count - 1f);
        float y = top() + Math.max(0, Math.min(1, fraction)) * range();
        float x = getWidth() - dp(5);

        background.setColor(Color.argb(dragging ? 100 : 52, 166, 170, 177));
        canvas.drawRoundRect(new RectF(x - dp(1), top(), x + dp(1), bottom()),
            dp(1), dp(1), background);

        float width = dp(Math.max(31, 17 + String.valueOf(selected + 1).length() * 9));
        float left = getWidth() - width - dp(3);
        RectF pill = new RectF(left, y - dp(16), getWidth() - dp(3), y + dp(16));
        background.setColor(Color.argb(dragging ? 248 : 225, 25, 28, 33));
        canvas.drawRoundRect(pill, dp(12), dp(12), background);
        label.setColor(Color.WHITE);
        Paint.FontMetrics m = label.getFontMetrics();
        canvas.drawText(String.valueOf(selected + 1),
            pill.centerX(), y - (m.ascent + m.descent) / 2f, label);
    }

    private int indexAt(float y) {
        if (count <= 1) return 0;
        float fraction = Math.max(0f, Math.min(1f, (y - top()) / range()));
        return Math.min(count - 1, Math.round(fraction * (count - 1)));
    }

    private void scheduleSelection(int index) {
        pending = index;
        if (scheduled) return;
        scheduled = true;
        postOnAnimation(() -> {
            scheduled = false;
            int indexToSend = pending;
            if (dragging && indexToSend >= 0 && indexToSend != lastDelivered) {
                lastDelivered = indexToSend;
                listener.onScrub(indexToSend);
            }
        });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (count == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (slide != null) slide.cancel();
                lastDelivered = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                appear();
                selected = indexAt(event.getY());
                displayed = selected;
                invalidate();
                scheduleSelection(selected);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                int next = indexAt(event.getY());
                if (next != selected) {
                    selected = next;
                    displayed = next;
                    scheduleSelection(next);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    selected = indexAt(event.getY());
                    displayed = selected;
                    dragging = false;
                    pending = -1;
                    if (lastDelivered != selected) listener.onScrub(selected);
                    showBriefly();
                    invalidate();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pending = -1;
                showBriefly();
                return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (slide != null) slide.cancel();
        cancelFade();
        super.onDetachedFromWindow();
    }
}
