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
        // Unlike handling scaling only in RecyclerView, the parent sees every
        // pointer event even if the horizontally scrollable container intercepts.
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1 ||
            scaleDetector.isInProgress()) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            gestureTaken = true;
        }
        if (gestureTaken) {
            // Explicitly cancel the previously active single-finger child gesture.
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                gestureTaken = false;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
