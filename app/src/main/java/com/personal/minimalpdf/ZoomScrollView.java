package com.personal.minimalpdf;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * Horizontal panning only. The Activity owns multi-touch gestures so pinch
 * detection cannot be cancelled by nested RecyclerView and page tap listeners.
 */
public final class ZoomScrollView extends HorizontalScrollView {
    public ZoomScrollView(Context context) {
        super(context);
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
    }
}
