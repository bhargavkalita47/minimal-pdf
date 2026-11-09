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
import android.view.GestureDetector;
import android.view.View;
import android.view.ViewGroup;


import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
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
        void onHorizontalSwipe(int position, int direction);
        void onScrollProgress(float fractionalPage);
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
    private List<Integer> pageOrder = new ArrayList<>();
    private PdfRenderer pdf;
    private volatile int generation = 0;
    private boolean restoringDocument;
    private float readingProgress;
    private Map<Integer, List<RectF>> searchHighlights = new HashMap<>();
    public PdfPageList(Context context, PageListener listener) {
        super(context);
        this.listener = listener;
        layout = new LinearLayoutManager(context, VERTICAL, false);
        setLayoutManager(layout);
        setAdapter(adapter);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setItemAnimator(null);
        setHasFixedSize(false);
        setClipToPadding(false);
        // Never reserve invisible toolbar height inside the reader. Toolbars are overlays.
        setPadding(0, dp(2), 0, dp(2));
        setItemViewCacheSize(3);
        setBackgroundColor(Color.rgb(229, 231, 235));
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                notifyVisiblePage();
                notifyScrollProgress();
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
        if (restoringDocument || adapter.getItemCount() == 0) return;
        int first = layout.findFirstVisibleItemPosition();
        if (first == NO_POSITION) return;
        // Report the page nearest the top, or the next one if the top page has
        // almost completely scrolled out of sight.
        View top = layout.findViewByPosition(first);
        if (top != null && first + 1 < adapter.getItemCount() && top.getBottom() < getHeight() / 5)
            first++;
        listener.onVisiblePageChanged(first);
    }

    private void notifyScrollProgress() {
        if (restoringDocument) return;
        int first = layout.findFirstVisibleItemPosition();
        if (first == NO_POSITION || adapter.getItemCount() == 0) return;
        View row = layout.findViewByPosition(first);
        if (row == null) return;
        float fractional = Math.max(0f, Math.min(1f,
            (getPaddingTop() - row.getTop()) / (float)Math.max(1, row.getHeight())));
        readingProgress = Math.min(adapter.getItemCount() - 1f, first + fractional);
        listener.onScrollProgress(readingProgress);
    }

    /** Restore each document directly to its saved page. Never publish page 0
     * during binding: that old notification overwrote MainActivity's pageIndex,
     * making every PDF reopen at its first page. */
    public void setDocument(PdfRenderer reader, List<Integer> order, int initialPage, float initialProgress) {
        stopScroll();
        restoringDocument = true;
        final int request;
        synchronized (renderLock) {
            request = ++generation;
            pdf = reader;
        }
        pageOrder = order;
        inFlight.clear();
        bitmaps.evictAll();
        adapter.notifyDataSetChanged();
        if (!pageOrder.isEmpty()) {
            int target = Math.max(0, Math.min(pageOrder.size() - 1, initialPage));
            float fraction = Math.max(0f, Math.min(.995f, initialProgress - target));
            readingProgress = target + fraction;
            layout.scrollToPositionWithOffset(target, dp(2));
            postOnAnimation(() -> {
                if (generation != request || pdf == null) return;
                View row = layout.findViewByPosition(target);
                if (row != null && fraction > 0f) {
                    layout.scrollToPositionWithOffset(target,
                        dp(2) - Math.round(fraction * row.getHeight()));
                }
                // Do not publish a transient wrong page during first layout.
                postOnAnimation(() -> {
                    if (generation != request || pdf == null) return;
                    restoringDocument = false;
                    notifyVisiblePage();
                    notifyScrollProgress();
                });
            });
        } else {
            restoringDocument = false;
        }
    }

    public void clearDocument() {
        restoringDocument = false;
        readingProgress = 0f;
        synchronized (renderLock) {
            generation++;
            pdf = null;
        }
        inFlight.clear();
        bitmaps.evictAll();
        searchHighlights.clear();
        pageOrder = new ArrayList<>();
        adapter.notifyDataSetChanged();
    }

    public void setSearchHighlights(Map<Integer, List<RectF>> highlights) {
        searchHighlights = highlights == null ? new HashMap<>() : highlights;
        adapter.notifyDataSetChanged();
    }

    public void pagesChanged(int currentPage) {
        inFlight.clear();
        adapter.notifyDataSetChanged();
        scrollToPage(currentPage);
    }

    public float getReadingProgress() { return readingProgress; }

    public void scrollToPage(int index) {
        if (index < 0 || index >= adapter.getItemCount()) return;
        stopScroll();
        layout.scrollToPositionWithOffset(index, dp(2));
        readingProgress = index;
        listener.onVisiblePageChanged(index);
        listener.onScrollProgress(index);
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
                @Override public void horizontalSwipe(int direction) {
                    int index = holder.getBindingAdapterPosition();
                    if (index != NO_POSITION) listener.onHorizontalSwipe(index, direction);
                }
            });
            return holder;
        }

        @Override public void onBindViewHolder(PageHolder holder, int position) {
            int original = pageOrder.get(position);
            Bitmap cached = bitmaps.get(original);
            holder.page.setBitmap(cached);
            holder.page.setHighlights(searchHighlights.get(original));
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
        private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<RectF> highlights;
        private Bitmap bitmap;
        private GestureDetector taps;
        private float aspect = A4_ASPECT;
        private int gutterX, gutterY;

        PageView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            gutterX = (int)(12f * density + .5f);
            // Only a 1dp margin above and below: adjacent pages nearly touch.
            gutterY = Math.max(1, (int)(1f * density + .5f));
            paper.setColor(Color.WHITE);
            highlightPaint.setColor(Color.argb(97, 255, 193, 54));
        }

        interface TapActions {
            void singleTap();
            void doubleTap();
            void horizontalSwipe(int direction);
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
                    @Override public boolean onFling(MotionEvent first, MotionEvent last,
                                                      float vx, float vy) {
                        if (first == null || last == null) return false;
                        float dx = last.getX() - first.getX();
                        float dy = last.getY() - first.getY();
                        float density = getResources().getDisplayMetrics().density;
                        if (Math.abs(dx) > 68f * density &&
                            Math.abs(dx) > Math.abs(dy) * 1.35f &&
                            Math.abs(vx) > 360f * density) {
                            callbacks.horizontalSwipe(dx < 0 ? 1 : -1);
                            return true;
                        }
                        return false;
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

        void setHighlights(List<RectF> rectangles) {
            highlights = rectangles;
            invalidate();
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
                if (highlights != null) {
                    for (RectF r : highlights) {
                        canvas.drawRect(
                            bounds.left + bounds.width() * r.left,
                            bounds.top + bounds.height() * r.top,
                            bounds.left + bounds.width() * r.right,
                            bounds.top + bounds.height() * r.bottom, highlightPaint);
                    }
                }
            }
        }
    }
}
