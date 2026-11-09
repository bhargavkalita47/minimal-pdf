package com.personal.minimalpdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * The thumb's vertical position represents the currently visible page.
 * Dragging anywhere along its narrow track scrubs the entire document.
 */
public final class PageScrubber extends View {
    public interface Listener { void onScrub(int zeroBasedPage); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final float density;
    private int count;
    private int current;
    private int lastDelivered = -1;
    private int pending = -1;
    private boolean scheduled;
    private boolean dragging;

    public PageScrubber(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        labelPaint.setTypeface(android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(15f * context.getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Scrub PDF pages");
    }

    private float dp(float value) { return value * density; }
    private float trackTop() { return dp(27); }
    private float trackBottom() { return Math.max(trackTop() + dp(1), getHeight() - dp(27)); }
    private float range() { return Math.max(1f, trackBottom() - trackTop()); }

    public void setPage(int page, int total) {
        count = Math.max(0, total);
        current = count == 0 ? 0 : Math.min(count - 1, Math.max(0, page));
        setVisibility(count > 0 ? VISIBLE : GONE);
        setContentDescription(count == 0 ? "No pages" :
            "Page " + (current + 1) + " of " + count + ". Slide vertically to change page");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count <= 0) return;
        float top = trackTop();
        float bottom = trackBottom();
        float trackX = getWidth() - dp(9);
        paint.setColor(Color.argb(88, 144, 151, 162));
        canvas.drawRoundRect(trackX - dp(2), top - dp(5), trackX + dp(2),
            bottom + dp(5), dp(2), dp(2), paint);

        float fraction = count < 2 ? 0f : current / (float)(count - 1);
        float centerY = top + fraction * range();
        float left = getWidth() - dp(59);
        float right = getWidth() - dp(3);
        float radius = dragging ? dp(24) : dp(22);
        paint.setColor(Color.argb(dragging ? 246 : 224, 24, 27, 31));
        canvas.drawRoundRect(new RectF(left, centerY - dp(24),
            right, centerY + dp(24)), radius, radius, paint);
        labelPaint.setColor(Color.WHITE);
        // Center the text vertically using the font metrics, not a fixed baseline.
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(String.valueOf(current + 1),
            (left + right) / 2f, baseline, labelPaint);
    }

    private int pageAt(float y) {
        if (count <= 1) return 0;
        float fraction = Math.max(0f, Math.min(1f, (y - trackTop()) / range()));
        return Math.min(count - 1, Math.round(fraction * (count - 1)));
    }

    private void scheduleSelection(int page) {
        pending = page;
        if (scheduled) return;
        scheduled = true;
        postOnAnimation(() -> {
            scheduled = false;
            int selected = pending;
            if (dragging && selected >= 0 && selected != lastDelivered) {
                lastDelivered = selected;
                listener.onScrub(selected);
            }
        });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (count <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                lastDelivered = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                current = pageAt(event.getY());
                scheduleSelection(current);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                int target = pageAt(event.getY());
                if (target != current) {
                    current = target;
                    scheduleSelection(target);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    current = pageAt(event.getY());
                    dragging = false;
                    pending = -1;
                    if (lastDelivered != current) listener.onScrub(current);
                    invalidate();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pending = -1;
                invalidate();
                return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
