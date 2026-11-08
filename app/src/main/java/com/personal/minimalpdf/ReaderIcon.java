package com.personal.minimalpdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Compact stroke icons with a 48dp accessible touch target. No external assets. */
public final class ReaderIcon extends View {
    public static final String BACK = "back", OPEN = "open", ZOOM = "zoom",
        SHARE = "share", MORE = "more", PAGES = "pages", MOVE = "move",
        EXTRACT = "extract", DELETE = "delete", SAVE = "save", RESET = "reset",
        CLOSE = "close", PREVIOUS = "previous", NEXT = "next";

    private final String symbol;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;

    public ReaderIcon(Context context, String symbol, int color, String label,
                      OnClickListener listener) {
        super(context);
        this.symbol = symbol;
        this.color = color;
        setContentDescription(label);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(listener);
        ink.setColor(color);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.85f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setBackground(UiPalette.from(context).rounded(context,
            Color.TRANSPARENT, Color.TRANSPARENT, 12));
    }

    public void setIconColor(int value) {
        color = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas raw) {
        super.onDraw(raw);
        raw.save();
        float unit = getResources().getDisplayMetrics().density;
        float dimension = 24f * unit;
        raw.translate((getWidth() - dimension) / 2f, (getHeight() - dimension) / 2f);
        raw.scale(unit, unit);
        ink.setColor(color);
        ink.setAlpha(isEnabled() ? 255 : 85);
        switch (symbol) {
            case BACK:
                line(raw, 19, 12, 5, 12); line(raw, 12, 5, 5, 12); line(raw, 5, 12, 12, 19);
                break;
            case OPEN:
                line(raw, 3, 8, 3, 19); line(raw, 3, 19, 21, 19);
                line(raw, 21, 19, 21, 9); line(raw, 21, 9, 11, 9);
                line(raw, 11, 9, 9, 6); line(raw, 9, 6, 3, 6);
                break;
            case SHARE:
                line(raw, 8, 11, 16, 6); line(raw, 8, 13, 16, 18);
                circle(raw, 6, 12, 2.3f); circle(raw, 18, 5, 2.3f);
                circle(raw, 18, 19, 2.3f);
                break;
            case ZOOM:
                circle(raw, 10.5f, 10.5f, 6.5f);
                line(raw, 15.5f, 15.5f, 21, 21);
                line(raw, 7.5f, 10.5f, 13.5f, 10.5f);
                line(raw, 10.5f, 7.5f, 10.5f, 13.5f);
                break;
            case MORE:
                fillCircle(raw, 12, 5, 1.3f);
                fillCircle(raw, 12, 12, 1.3f);
                fillCircle(raw, 12, 19, 1.3f);
                break;
            case PAGES:
                roundRect(raw, 5, 3, 20, 19, 1.7f);
                line(raw, 9, 8, 16, 8); line(raw, 9, 12, 16, 12);
                line(raw, 9, 16, 14, 16);
                line(raw, 2, 7, 2, 21); line(raw, 2, 21, 16, 21);
                break;
            case MOVE:
                line(raw, 12, 3, 12, 21);
                line(raw, 8, 7, 12, 3); line(raw, 16, 7, 12, 3);
                line(raw, 8, 17, 12, 21); line(raw, 16, 17, 12, 21);
                break;
            case EXTRACT:
                roundRect(raw, 3, 3, 14, 21, 1.3f);
                line(raw, 7, 8, 11, 8); line(raw, 7, 12, 11, 12);
                line(raw, 12, 16, 21, 16); line(raw, 17, 12, 21, 16);
                line(raw, 17, 20, 21, 16);
                break;
            case DELETE:
                roundRect(raw, 6, 7, 18, 21, 1.2f);
                line(raw, 4, 7, 20, 7); line(raw, 9, 4, 15, 4);
                line(raw, 9, 11, 9, 17); line(raw, 15, 11, 15, 17);
                break;
            case SAVE:
                roundRect(raw, 4, 3, 20, 21, 1.3f);
                roundRect(raw, 8, 3, 16, 9, .7f);
                roundRect(raw, 8, 14, 16, 21, .7f);
                break;
            case RESET:
                raw.drawArc(new RectF(4, 4, 20, 20), -65, 298, false, ink);
                line(raw, 4, 5, 4, 11); line(raw, 4, 11, 10, 11);
                break;
            case CLOSE:
                line(raw, 5, 5, 19, 19); line(raw, 19, 5, 5, 19);
                break;
            case PREVIOUS:
                line(raw, 15, 5, 8, 12); line(raw, 8, 12, 15, 19);
                break;
            case NEXT:
                line(raw, 9, 5, 16, 12); line(raw, 16, 12, 9, 19);
                break;
        }
        raw.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, ink);
    }
    private void circle(Canvas c, float x, float y, float radius) {
        c.drawCircle(x, y, radius, ink);
    }
    private void fillCircle(Canvas c, float x, float y, float radius) {
        Paint.Style before = ink.getStyle();
        ink.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, radius, ink);
        ink.setStyle(before);
    }
    private void roundRect(Canvas c, float l, float t, float r, float b, float radius) {
        c.drawRoundRect(l, t, r, b, radius, radius, ink);
    }
}
