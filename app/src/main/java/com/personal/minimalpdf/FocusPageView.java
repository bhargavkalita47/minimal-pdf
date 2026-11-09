package com.personal.minimalpdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One focused PDF page drawn above the scrollable document. */
public final class FocusPageView extends View {
    public interface Actions {
        void onDoubleTap();
        void onSingleTap();
        void onZoomChanged(float zoom);
        void onHorizontalSwipe(int direction);
    }

    private final Actions actions;
    private final GestureDetector taps;
    private final ScaleGestureDetector pinches;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private float zoom = 1f;
    private float panX, panY;
    private float lastX, lastY;
    private boolean movedWithFingers = false;
    private ValueAnimator zoomAnimator;
    private VelocityTracker swipeVelocity;
    private float gestureStartX, gestureStartY;
    private boolean multiTouchGesture;

    public FocusPageView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setClickable(true);
        shadow.setColor(Color.argb(42, 0, 0, 0));

        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                actions.onSingleTap();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                actions.onDoubleTap();
                return true;
            }

        });

        pinches = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (zoomAnimator != null) zoomAnimator.cancel();
                    changeZoom(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
    }

    public void setBitmap(Bitmap image) {
        setBitmap(image, false);
    }

    /** New pages keep the chosen focus zoom while centering their own content.
     * This must also cancel any zoom animation still updating the old page. */
    public void setBitmap(Bitmap image, boolean preserveZoom) {
        float retainedZoom = preserveZoom ? zoom : 1f;
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
        if (bitmap != null && bitmap != image && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = image;
        zoom = Math.max(1f, Math.min(5f, retainedZoom));
        panX = panY = 0;
        clampPan();
        invalidate();
        actions.onZoomChanged(zoom);
    }

    public float getZoom() { return zoom; }

    public void setZoom(float zoom) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        changeZoom(zoom, getWidth() / 2f, getHeight() / 2f);
    }

    public void animateToZoom(float destination) {
        if (bitmap == null) return;
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoomAnimator = ValueAnimator.ofFloat(zoom,
            Math.max(1f, Math.min(5f, destination)));
        zoomAnimator.setDuration(180L);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(1.3f));
        zoomAnimator.addUpdateListener(animation -> changeZoom(
            (float) animation.getAnimatedValue(),
            getWidth() / 2f, getHeight() / 2f));
        zoomAnimator.start();
    }

    public void dispose() {
        if (swipeVelocity != null) { swipeVelocity.recycle(); swipeVelocity = null; }
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        invalidate();
    }

    private RectF pageRect() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0)
            return new RectF();
        float margin = getResources().getDisplayMetrics().density * 18f;
        float fit = Math.min((getWidth() - 2f * margin) / bitmap.getWidth(),
                             (getHeight() - 2f * margin) / bitmap.getHeight());
        float w = bitmap.getWidth() * Math.max(.001f, fit) * zoom;
        float h = bitmap.getHeight() * Math.max(.001f, fit) * zoom;
        float left = (getWidth() - w) / 2f + panX;
        float top = (getHeight() - h) / 2f + panY;
        return new RectF(left, top, left + w, top + h);
    }

    private void clampPan() {
        RectF rectangle = pageRect();
        float width = rectangle.width(), height = rectangle.height();
        panX = width <= getWidth() ? 0f :
            Math.max((getWidth() - width) / 2f, Math.min((width - getWidth()) / 2f, panX));
        panY = height <= getHeight() ? 0f :
            Math.max((getHeight() - height) / 2f, Math.min((height - getHeight()) / 2f, panY));
    }

    private void changeZoom(float value, float anchorX, float anchorY) {
        if (bitmap == null) return;
        float target = Math.max(1f, Math.min(5f, value));
        RectF before = pageRect();
        float fractionX = before.width() <= 0 ? .5f : (anchorX - before.left) / before.width();
        float fractionY = before.height() <= 0 ? .5f : (anchorY - before.top) / before.height();
        zoom = target;
        RectF after = pageRect();
        panX += anchorX - (after.left + fractionX * after.width());
        panY += anchorY - (after.top + fractionY * after.height());
        clampPan();
        invalidate();
        actions.onZoomChanged(zoom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampPan();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        RectF rect = pageRect();
        float d = getResources().getDisplayMetrics().density;
        canvas.drawRect(rect.left + d * 2, rect.top + d * 4,
                        rect.right + d * 2, rect.bottom + d * 4, shadow);
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinches.onTouchEvent(e);
        taps.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureStartX = lastX = e.getX();
            gestureStartY = lastY = e.getY();
            movedWithFingers = false;
            multiTouchGesture = false;
            if (swipeVelocity != null) swipeVelocity.recycle();
            swipeVelocity = VelocityTracker.obtain();
            swipeVelocity.addMovement(e);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            movedWithFingers = true;
            multiTouchGesture = true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (swipeVelocity != null && !multiTouchGesture)
                swipeVelocity.addMovement(e);
            if (e.getPointerCount() == 1 && !pinches.isInProgress()
                && !movedWithFingers && zoom > 1f) {
                panX += e.getX() - lastX;
                panY += e.getY() - lastY;
                clampPan();
                invalidate();
            }
            lastX = e.getX();
            lastY = e.getY();
        } else if (action == MotionEvent.ACTION_UP) {
            if (swipeVelocity != null) {
                swipeVelocity.addMovement(e);
                swipeVelocity.computeCurrentVelocity(1000);
            }
            if (!multiTouchGesture && !pinches.isInProgress()) {
                // Navigation is independent of zoom: fast one-finger horizontal
                // swipes flip pages, while slow drags can still pan zoomed text.
                float dx = e.getX() - gestureStartX;
                float dy = e.getY() - gestureStartY;
                float d = getResources().getDisplayMetrics().density;
                float vx = swipeVelocity == null ? 0f : swipeVelocity.getXVelocity();
                // When zoomed, a deliberate quick swipe changes pages; a
                // slow horizontal drag pans the zoomed image instead.
                boolean deliberateSwipe = zoom <= 1.02f
                    ? (Math.abs(vx) >= 250f * d || Math.abs(dx) >= 112f * d)
                    : (Math.abs(vx) >= 420f * d && Math.abs(dx) >= 75f * d);
                if (Math.abs(dx) >= 56f * d &&
                    Math.abs(dx) > Math.abs(dy) * 1.35f && deliberateSwipe) {
                    actions.onHorizontalSwipe(dx < 0 ? 1 : -1);
                }
            }
            if (swipeVelocity != null) { swipeVelocity.recycle(); swipeVelocity = null; }
            performClick();
            movedWithFingers = false;
            multiTouchGesture = false;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            if (swipeVelocity != null) { swipeVelocity.recycle(); swipeVelocity = null; }
            movedWithFingers = false;
            multiTouchGesture = false;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
