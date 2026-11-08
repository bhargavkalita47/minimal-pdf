package com.personal.minimalpdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

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
    public interface PageListener { void onVisiblePageChanged(int position); }

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
    private final HorizontalScrollView pan;
    private final ScaleGestureDetector scaleDetector;
    private List<Integer> pageOrder = new ArrayList<>();
    private PdfRenderer pdf;
    private volatile int generation = 0;
    private float zoom = 1f;

    public PdfPageList(Context context, HorizontalScrollView pan, PageListener listener) {
        super(context);
        this.listener = listener;
        this.pan = pan;
        layout = new LinearLayoutManager(context, VERTICAL, false);
        setLayoutManager(layout);
        setAdapter(adapter);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setItemAnimator(null);
        setHasFixedSize(false);
        setBackgroundColor(Color.rgb(229, 231, 235));
        // The item widths change during zoom. RecyclerView recycles rows as we scroll.
        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    updateZoom(detector.getScaleFactor(), detector.getFocusX());
                    return true;
                }
            });
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

    public void resetZoom() {
        zoom = 1f;
        ViewGroup.LayoutParams p = getLayoutParams();
        if (p != null) {
            p.width = ViewGroup.LayoutParams.MATCH_PARENT;
            setLayoutParams(p);
        }
        pan.post(() -> pan.scrollTo(0, 0));
    }

    private void updateZoom(float multiplier, float focus) {
        if (pan.getWidth() <= 0) return;
        float next = Math.max(1f, Math.min(3f, zoom * multiplier));
        if (Math.abs(next - zoom) < .002f) return;
        float oldWidth = pan.getWidth() * zoom;
        float focusFraction = (pan.getScrollX() + focus) / Math.max(1f, oldWidth);
        zoom = next;
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params == null) return;
        params.width = Math.max(pan.getWidth(), Math.round(pan.getWidth() * zoom));
        setLayoutParams(params);
        post(() -> pan.scrollTo(Math.round(focusFraction * getWidth() - focus), 0));
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Observe both fingers before the list handles its normal single-finger scroll.
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1 && getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) return true;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) return true;
        return super.onTouchEvent(event);
    }

    public void dispose() {
        clearDocument();
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
            page.setOnClickListener(view -> {
                int index = holder.getBindingAdapterPosition();
                if (index != NO_POSITION) listener.onVisiblePageChanged(index);
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
        private float aspect = A4_ASPECT;
        private int gutterX, gutterY;

        PageView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            gutterX = (int)(12f * density + .5f);
            gutterY = (int)(9f * density + .5f);
            paper.setColor(Color.WHITE);
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
