package com.personal.minimalpdf;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** Simple accessible colours which follow Android's system night mode. */
public final class UiPalette {
    public final boolean dark;
    public final int surface, canvas, foreground, mutedText, stroke, subtle, accent, onAccent, error, ripple;

    private UiPalette(boolean dark) {
        this.dark = dark;
        surface = Color.parseColor(dark ? "#171A20" : "#FFFFFF");
        canvas = Color.parseColor(dark ? "#101319" : "#F0F2F5");
        foreground = Color.parseColor(dark ? "#F2F4F7" : "#222831");
        mutedText = Color.parseColor(dark ? "#AAB1BF" : "#717986");
        stroke = Color.parseColor(dark ? "#343943" : "#E5E8ED");
        subtle = Color.parseColor(dark ? "#272C35" : "#F4F6F9");
        accent = Color.parseColor(dark ? "#8BAEFF" : "#2D62DF");
        onAccent = Color.parseColor(dark ? "#12213D" : "#FFFFFF");
        error = Color.parseColor(dark ? "#F29BA2" : "#B83E4D");
        ripple = Color.parseColor(dark ? "#45526A" : "#D8E0EE");
    }

    public static UiPalette from(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return new UiPalette(mode == Configuration.UI_MODE_NIGHT_YES);
    }

    public android.graphics.drawable.Drawable rounded(Context context,
                                                        int fill, int border, float radiusDp) {
        int radius = Math.round(radiusDp * context.getResources().getDisplayMetrics().density);
        GradientDrawable base = new GradientDrawable();
        base.setColor(fill);
        base.setCornerRadius(radius);
        if (border != Color.TRANSPARENT) {
            base.setStroke(Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)), border);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), base, mask);
    }
}
