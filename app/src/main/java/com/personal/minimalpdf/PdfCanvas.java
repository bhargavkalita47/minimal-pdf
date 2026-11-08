package com.personal.minimalpdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;

public class PdfCanvas extends View {
    public enum Tool { VIEW, PEN, TEXT }

    public interface Actions {
        ArrayList<Mark> currentMarks();
        void addMark(Mark mark);
        void onTextTap(float x, float y);
    }

    public static class Mark {
        public final boolean isText;
        public String text;
        public float x, y;
        public final ArrayList<PointF> points = new ArrayList<>();

        private Mark(boolean text) { this.isText = text; }

        public static Mark text(float x, float y, String words) {
            Mark m = new Mark(true);
            m.x = x;
            m.y = y;
            m.text = words;
            return m;
        }

        public static Mark pen() { return new Mark(false); }

        public Mark copy() {
            Mark m = new Mark(isText);
            m.text = text;
            m.x = x;
            m.y = y;
            for (PointF p : points) m.points.add(new PointF(p.x, p.y));
            return m;
        }

        public void draw(Canvas canvas, RectF bounds) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(23, 78, 198));
            if (isText) {
                if (text == null) return;
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
                paint.setTextSize(Math.max(7f, bounds.width() * .038f));
                float baseline = bounds.top + y * bounds.height();
                float left = bounds.left + x * bounds.width();
                for (String line : text.split("\\n", -1)) {
                    canvas.drawText(line, left, baseline, paint);
                    baseline += paint.getTextSize() * 1.25f;
                }
            } else {
                if (points.isEmpty()) return;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(Math.max(1.5f, bounds.width() * .005f));
                Path path = new Path();
                PointF first = points.get(0);
                path.moveTo(bounds.left + first.x * bounds.width(), bounds.top + first.y * bounds.height());
                for (int i = 1; i < points.size(); i++) {
                    PointF p = points.get(i);
                    path.lineTo(bounds.left + p.x * bounds.width(), bounds.top + p.y * bounds.height());
                }
                if (points.size() == 1) {
                    float cx = bounds.left + first.x * bounds.width();
                    float cy = bounds.top + first.y * bounds.height();
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, paint.getStrokeWidth() * .5f, paint);
                } else canvas.drawPath(path, paint);
            }
        }
    }

    private final Actions actions;
    private final ScaleGestureDetector pinch;
    private Bitmap bitmap;
    private Tool tool = Tool.VIEW;
    private float zoom = 1f, offsetX, offsetY;
    private float lastX, lastY;
    private Mark drawing;
    private boolean multiTouch = false;
    private View emptyLabel;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public PdfCanvas(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                RectF old = pageRect();
                float focusX = detector.getFocusX(), focusY = detector.getFocusY();
                float fracX = old.width() <= 0 ? .5f : (focusX - old.left) / old.width();
                float fracY = old.height() <= 0 ? .5f : (focusY - old.top) / old.height();
                zoom = Math.max(1f, Math.min(5f, zoom * detector.getScaleFactor()));
                float[] dimensions = pageDimensions();
                offsetX = focusX - fracX * dimensions[0] - (getWidth() - dimensions[0]) / 2f;
                offsetY = focusY - fracY * dimensions[1] - (getHeight() - dimensions[1]) / 2f;
                if (zoom <= 1.001f) { offsetX = 0; offsetY = 0; }
                invalidate();
                return true;
            }
        });
    }

    public void setEmptyLabel(View v) { emptyLabel = v; }

    public void setPage(Bitmap newBitmap) {
        Bitmap previous = bitmap;
        bitmap = newBitmap;
        drawing = null;
        resetTransform();
        if (emptyLabel != null) emptyLabel.setVisibility(bitmap == null ? VISIBLE : GONE);
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    public void resetTransform() {
        zoom = 1f;
        offsetX = 0;
        offsetY = 0;
        invalidate();
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        drawing = null;
        invalidate();
    }

    private float[] pageDimensions() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return new float[]{0f, 0f};
        float factor = Math.min((getWidth() - getResources().getDisplayMetrics().density * 24f) / bitmap.getWidth(),
                                (getHeight() - getResources().getDisplayMetrics().density * 24f) / bitmap.getHeight());
        factor = Math.max(.001f, factor) * zoom;
        return new float[]{bitmap.getWidth() * factor, bitmap.getHeight() * factor};
    }

    private RectF pageRect() {
        float[] dims = pageDimensions();
        float x = (getWidth() - dims[0]) / 2f + offsetX;
        float y = (getHeight() - dims[1]) / 2f + offsetY;
        return new RectF(x, y, x + dims[0], y + dims[1]);
    }

    private PointF position(MotionEvent event) {
        RectF rect = pageRect();
        float x = rect.width() > 0 ? (event.getX() - rect.left) / rect.width() : 0f;
        float y = rect.height() > 0 ? (event.getY() - rect.top) / rect.height() : 0f;
        return new PointF(Math.max(0f, Math.min(1f, x)), Math.max(0f, Math.min(1f, y)));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        RectF rect = pageRect();
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.rgb(200, 204, 210));
        canvas.drawRect(rect.left + 2, rect.top + 5, rect.right + 2, rect.bottom + 5, shadow);
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
        for (Mark m : actions.currentMarks()) m.draw(canvas, rect);
        if (drawing != null) drawing.draw(canvas, rect);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        pinch.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            multiTouch = true;
            drawing = null;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) return true;
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            lastX = event.getX(); lastY = event.getY();
            if (tool == Tool.PEN && pageRect().contains(lastX, lastY)) {
                drawing = Mark.pen();
                PointF p = position(event);
                drawing.points.add(p);
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (multiTouch || pinch.isInProgress()) return true;
            if (tool == Tool.VIEW && zoom > 1f) {
                offsetX += event.getX() - lastX;
                offsetY += event.getY() - lastY;
                invalidate();
            } else if (tool == Tool.PEN && drawing != null) {
                drawing.points.add(position(event));
                invalidate();
            }
            lastX = event.getX(); lastY = event.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!multiTouch && !pinch.isInProgress()) {
                if (tool == Tool.PEN && drawing != null) {
                    drawing.points.add(position(event));
                    actions.addMark(drawing);
                } else if (tool == Tool.TEXT && pageRect().contains(event.getX(), event.getY())) {
                    PointF p = position(event);
                    actions.onTextTap(p.x, p.y);
                }
            }
            drawing = null;
            multiTouch = false;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            drawing = null;
            multiTouch = false;
            invalidate();
            return true;
        }
        return true;
    }
}
