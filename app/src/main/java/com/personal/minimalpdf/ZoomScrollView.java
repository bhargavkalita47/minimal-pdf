package com.personal.minimalpdf;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * Owns two-finger gestures before the nested RecyclerView can intercept them.
 * One-finger gestures remain available for ordinary page scrolling and horizontal panning.
 */
public final class ZoomScrollView extends HorizontalScrollView {
    public interface PinchListener { void onPinch(float scale, float focusX, float focusY); }

    private final ScaleGestureDetector scaleDetector;
    private PinchListener listener;
    private boolean gestureTaken;

    public ZoomScrollView(Context context) {
        super(context);
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                    return true;
                }
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if (listener != null) {
                        listener.onPinch(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    }
                    return true;
                }
            });
    }

    public void setPinchListener(PinchListener listener) {
        this.listener = listener;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Always feed the detector from the root of this subtree. Child pages
        // can consume taps and drags, but cannot starve two-finger scaling.
        final int action = event.getActionMasked();
        scaleDetector.onTouchEvent(event);

        if (action == MotionEvent.ACTION_DOWN) {
            gestureTaken = false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            if (!gestureTaken) {
                gestureTaken = true;
                // Cancel any RecyclerView fling or single-finger drag exactly
                // once; do not cancel the scale detector's pointer stream.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (gestureTaken) {
            if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_CANCEL) {
                gestureTaken = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
