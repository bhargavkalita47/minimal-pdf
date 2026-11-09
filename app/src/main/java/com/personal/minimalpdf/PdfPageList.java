package com.personal.minimalpdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;


import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A continuous vertical PDF reader. Only visible/nearby pages are rendered;
 * off-screen bitmaps are kept in a bounded cache instead of loading the whole PDF.
 */
public final class PdfPageList extends RecyclerView {
    public interface PageListener {
        void onVisiblePageChanged(int position);
        void onSingleTap();
        void onDoubleTap(int position);
        void onZoomChanged(float zoom);
    }

    private final Object renderLock = new Object();
    private final ExecutorService renderQueue = Executors.newSingleThreadExecutor();
    private final Set<Integer> inFlight = new HashSet<>();
    private final LruCache<Integer, Bitmap> bitmaps =
        new LruCache<Integer, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    private final LinearLayoutManager layout;
    private final PageAdapter adapter = new PageAdapter();
    private final PageListener listener;
    private final ZoomScrollView pan;
    private List<Integer> pageOrder = new ArrayList<>();
    private PdfRenderer pdf;
    private volatile int generation = 0;
    private float zoom = 1f;
    private float desiredZoom = 1f;
    private float zoomFocusX, zoomFocusY;
    private boolean zoomFrameScheduled;
    private ValueAnimator zoomAnimation;
    private Runnable pendingHorizontalMove;

    public PdfPageList(Context context, ZoomScrollView pan, PageListener listener) {
        super(context);
        this.listener = listener;
        this.pan = pan;
        layout = new LinearLayoutManager(context, VERTICAL, false);
        setLayoutManager(layout);
        setAdapter(adapter);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setItemAnimator(null);
        setHasFixedSize(false);
        setClipToPadding(false);
        setPadding(0, dp(70), 0, dp(125));
        setItemViewCacheSize(3);
        setBackgroundColor(Color.rgb(229, 231, 235));
        // The item widths change during zoom. RecyclerView recycles rows as we scroll.
        // The Activity dispatches pinch updates here directly, ahead of
        // nested horizontal/vertical child interception.
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                notifyVisiblePage();
            }
            @Override public void onScrollStateChanged(RecyclerView view, int newState) {
                if (newState == SCROLL_STATE_IDLE) notifyVisiblePage();
            }
        });
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }

    /**
     * HorizontalScrollView measures its child with an UNSPECIFIED width spec,
     * which can ignore a changed LayoutParams.width. Explicitly measure the
     * RecyclerView at the zoomed width so page rows actually become wider.
     */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int viewport = pan.getWidth();
        if (viewport > 0 && !pageOrder.isEmpty()) {
            int target = Math.max(viewport, Math.round(viewport * zoom));
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void notifyVisiblePage() {
        if (adapter.getItemCount() == 0) return;
        int first = layout.findFirstVisibleItemPosition();
        if (first == NO_POSITION) return;
        // Report the page nearest the top, or the next one if the top page has
        // almost completely scrolled out of sight.
        View top = layout.findViewByPosition(first);
        if (top != null && first + 1 < adapter.getItemCount() && top.getBottom() < getHeight() / 5)
            first++;
        listener.onVisiblePageChanged(first);
    }

    public void setDocument(PdfRenderer reader, List<Integer> order) {
        synchronized (renderLock) {
            generation++;
            pdf = reader;
        }
        pageOrder = order;
        inFlight.clear();
        bitmaps.evictAll();
        resetZoom();
        adapter.notifyDataSetChanged();
        scrollToPage(0);
    }

    public void clearDocument() {
        synchronized (renderLock) {
            generation++;
            pdf = null;
        }
        inFlight.clear();
        bitmaps.evictAll();
        pageOrder = new ArrayList<>();
        adapter.notifyDataSetChanged();
    }

    public void pagesChanged(int currentPage) {
        inFlight.clear();
        adapter.notifyDataSetChanged();
        scrollToPage(currentPage);
    }

    public void scrollToPage(int index) {
        if (index < 0 || index >= adapter.getItemCount()) return;
        stopScroll();
        layout.scrollToPositionWithOffset(index, dp(6));
        listener.onVisiblePageChanged(index);
    }

    public float getZoom() { return desiredZoom; }

    public void resetZoom() {
        if (zoomAnimation != null) zoomAnimation.cancel();
        desiredZoom = zoom = 1f;
        zoomFrameScheduled = false;
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params != null) {
            setMinimumWidth(0);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            setLayoutParams(params);
        }
        if (pendingHorizontalMove != null) pan.removeCallbacks(pendingHorizontalMove);
        pan.postOnAnimation(() -> pan.scrollTo(0, 0));
        listener.onZoomChanged(1f);
    }

    /** Presets animate, but direct pinch changes apply at most once per frame. */
    public void setZoom(float level) {
        if (zoomAnimation != null) zoomAnimation.cancel();
        final float from = desiredZoom;
        final float target = Math.max(1f, Math.min(4f, level));
        if (Math.abs(from - target) < .001f) return;
        zoomAnimation = ValueAnimator.ofFloat(from, target);
        zoomAnimation.setDuration(175L);
        zoomAnimation.setInterpolator(new DecelerateInterpolator(1.2f));
        zoomAnimation.addUpdateListener(animation ->
            requestZoom((float) animation.getAnimatedValue(),
                pan.getWidth() / 2f, getHeight() / 2f));
        zoomAnimation.start();
    }

    public void pinchBy(float scaleFactor, float focusX, float focusY) {
        if (pageOrder.isEmpty() || !Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        if (zoomAnimation != null && zoomAnimation.isRunning()) zoomAnimation.cancel();
        requestZoom(desiredZoom * scaleFactor, focusX, focusY);
    }

    private void requestZoom(float level, float focusX, float focusY) {
        if (pageOrder.isEmpty()) return;
        desiredZoom = Math.max(1f, Math.min(4f, level));
        zoomFocusX = focusX;
        zoomFocusY = focusY;
        if (zoomFrameScheduled) return;
        zoomFrameScheduled = true;
        postOnAnimation(() -> {
            zoomFrameScheduled = false;
            applyZoomFrame();
        });
    }

    private void applyZoomFrame() {
        if (pan.getWidth() <= 0 || Math.abs(desiredZoom - zoom) < .001f) return;
        float beforeZoom = zoom;
        float next = desiredZoom;
        float factor = next / Math.max(.001f, beforeZoom);
        float focusX = zoomFocusX, focusY = zoomFocusY;

        int anchor = layout.findFirstVisibleItemPosition();
        int last = layout.findLastVisibleItemPosition();
        if (anchor == NO_POSITION) anchor = 0;
        for (int i = anchor; i <= last; i++) {
            View item = layout.findViewByPosition(i);
            if (item != null && item.getTop() <= focusY && item.getBottom() >= focusY) {
                anchor = i;
                break;
            }
        }
        View anchorView = layout.findViewByPosition(anchor);
        float newTop = anchorView == null
            ? 0f : focusY + (anchorView.getTop() - focusY) * factor;

        float previousWidth = pan.getWidth() * beforeZoom;
        float fraction = (pan.getScrollX() + focusX) / Math.max(1f, previousWidth);
        zoom = next;

        ViewGroup.LayoutParams params = getLayoutParams();
        if (params == null) return;
        params.width = Math.max(pan.getWidth(), Math.round(pan.getWidth() * zoom));
        setMinimumWidth(params.width);
        setLayoutParams(params);
        requestLayout();
        // Preserve the reading position instead of jumping back to the top.
        if (anchorView != null && anchor >= 0 && anchor < pageOrder.size()) {
            layout.scrollToPositionWithOffset(anchor,
                Math.round(newTop - getPaddingTop()));
        }

        final int horizontal = Math.max(0,
            Math.round(fraction * pan.getWidth() * zoom - focusX));
        if (pendingHorizontalMove != null) pan.removeCallbacks(pendingHorizontalMove);
        pendingHorizontalMove = () -> pan.scrollTo(horizontal, 0);
        pan.postOnAnimation(pendingHorizontalMove);
        listener.onZoomChanged(zoom);
    }

    public void dispose() {
        clearDocument();
        if (zoomAnimation != null) zoomAnimation.cancel();
        if (pendingHorizontalMove != null) pan.removeCallbacks(pendingHorizontalMove);
        renderQueue.shutdownNow();
    }

    private void loadPage(int original) {
        if (inFlight.contains(original)) return;
        inFlight.add(original);
        final int requestGeneration = generation;
        renderQueue.execute(() -> {
            Bitmap bitmap = null;
            try {
                synchronized (renderLock) {
                    if (pdf != null && generation == requestGeneration) {
                        try (PdfRenderer.Page page = pdf.openPage(original)) {
                            int width = page.getWidth(), height = page.getHeight();
                            float scale = Math.min(2.2f, 1600f / Math.max(width, height));
                            bitmap = Bitmap.createBitmap(
                                Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        }
                    }
                }
            } catch (Exception ignored) {
                if (bitmap != null) bitmap.recycle();
                bitmap = null;
            }
            final Bitmap finished = bitmap;
            post(() -> {
                if (generation != requestGeneration) {
                    if (finished != null) finished.recycle();
                    return;
                }
                inFlight.remove(original);
                if (finished != null) {
                    bitmaps.put(original, finished);
                    int position = pageOrder.indexOf(original);
                    if (position >= 0) adapter.notifyItemChanged(position);
                }
            });
        });
    }

    private final class PageAdapter extends Adapter<PageHolder> {
        PageAdapter() { setHasStableIds(true); }

        @Override public long getItemId(int position) { return pageOrder.get(position); }
        @Override public int getItemCount() { return pageOrder.size(); }

        @Override public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            PageView page = new PageView(getContext());
            page.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            PageHolder holder = new PageHolder(page);
            page.setTapActions(new PageView.TapActions() {
                @Override public void singleTap() {
                    int index = holder.getBindingAdapterPosition();
                    if (index != NO_POSITION) {
                        listener.onVisiblePageChanged(index);
                        listener.onSingleTap();
                    }
                }
                @Override public void doubleTap() {
                    int index = holder.getBindingAdapterPosition();
                    if (index != NO_POSITION) listener.onDoubleTap(index);
                }
            });
            return holder;
        }

        @Override public void onBindViewHolder(PageHolder holder, int position) {
            int original = pageOrder.get(position);
            Bitmap cached = bitmaps.get(original);
            holder.page.setBitmap(cached);
            if (cached == null) loadPage(original);
        }

        @Override public void onViewRecycled(PageHolder holder) {
            holder.page.setBitmap(null);
        }
    }

    private static final class PageHolder extends ViewHolder {
        final PageView page;
        PageHolder(PageView view) {
            super(view);
            page = view;
        }
    }

    private static final class PageView extends View {
        private static final float A4_ASPECT = 842f / 595f;
        private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        private GestureDetector taps;
        private float aspect = A4_ASPECT;
        private int gutterX, gutterY;

        PageView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            gutterX = (int)(12f * density + .5f);
            gutterY = (int)(9f * density + .5f);
            paper.setColor(Color.WHITE);
        }

        interface TapActions {
            void singleTap();
            void doubleTap();
        }

        void setTapActions(TapActions callbacks) {
            taps = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                        callbacks.singleTap();
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent event) {
                        callbacks.doubleTap();
                        return true;
                    }
                });
            setClickable(true);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (taps != null) taps.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        void setBitmap(Bitmap updated) {
            bitmap = updated;
            aspect = updated == null ? A4_ASPECT : (float)updated.getHeight() / updated.getWidth();
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int width = MeasureSpec.getSize(wSpec);
            int inner = Math.max(1, width - gutterX * 2);
            int height = Math.max(1, Math.round(inner * aspect) + gutterY * 2);
            setMeasuredDimension(width, height);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RectF bounds = new RectF(gutterX, gutterY,
                getWidth() - gutterX, getHeight() - gutterY);
            canvas.drawRect(bounds, paper);
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, null, bounds, imagePaint);
            }
        }
    }
}
