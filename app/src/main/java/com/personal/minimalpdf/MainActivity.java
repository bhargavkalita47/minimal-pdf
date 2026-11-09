package com.personal.minimalpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int OPEN_FILE = 101;
    private static final int SAVE_FILE = 102;
    private UiPalette colors;
    private final ArrayList<View> documentButtons = new ArrayList<>();

    private static final class DocumentSession {
        final Uri uri;
        final String name;
        final ArrayList<Integer> order = new ArrayList<>();
        int page;
        DocumentSession(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private final ArrayList<DocumentSession> documents = new ArrayList<>();
    private int activeDocument = -1;
    private Uri sourceUri;
    private ParcelFileDescriptor sourceFd;
    private PdfRenderer renderer;
    // The order contains ORIGINAL zero-based page numbers, not rendered bitmaps.
    private final ArrayList<Integer> pageOrder = new ArrayList<>();
    private int pageIndex = 0;
    private String documentName = "document.pdf";
    private PdfPageList pdfPages;
    private FrameLayout host;
    private View headerView, footerView, topDivider, bottomDivider;
    private boolean chromeVisible = true;
    private Button zoomButton;
    private FrameLayout focusOverlay;
    private FocusPageView focusPage;
    private Button focusZoomButton;
    private View focusToolbar;
    private TextView focusLoading;
    private int focusGeneration = 0;
    private static final int[] ZOOM_LEVELS = {100, 125, 150, 200, 300, 400};
    private View emptyView;
    private TextView fileLabel;
    private TextView pageLabel;
    private ReaderIcon editButton;
    private ReaderIcon shareButton;
    private ReaderIcon openButton;
    private PageScrubber pageScrubber;
    private TextView documentSelector;
    private TextView pageSelector;
    private FrameLayout wheelOverlay;
    private FrameLayout editOverlay;
    private PickerWheel pickerWheel;
    private int barBackground, barForeground, barMuted;
    private boolean busy = false;

    private ArrayList<Integer> pendingSavePages;
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        colors = UiPalette.from(this);
        barBackground = colors.dark ? Color.rgb(14, 15, 18) : Color.rgb(28, 29, 33);
        barForeground = Color.rgb(245, 247, 250);
        barMuted = Color.rgb(169, 174, 183);
        styleSystemBars();
        PDFBoxResourceLoader.init(getApplicationContext());
        buildInterface();
        if (state != null) {
            ArrayList<Bundle> stored = state.getParcelableArrayList("open_documents");
            if (stored != null) {
                for (Bundle entry : stored) {
                    if (entry == null || entry.getString("uri") == null) continue;
                    DocumentSession session = new DocumentSession(
                        Uri.parse(entry.getString("uri")),
                        entry.getString("name", "document.pdf"));
                    ArrayList<Integer> order = entry.getIntegerArrayList("order");
                    if (order != null) session.order.addAll(order);
                    session.page = entry.getInt("page", 0);
                    documents.add(session);
                }
                if (!documents.isEmpty()) {
                    switchDocument(Math.max(0, Math.min(documents.size() - 1,
                        state.getInt("active_document", 0))));
                }
            }
            chromeVisible = state.getBoolean("chrome_visible", true);
            applyChrome();
        }
        if (documents.isEmpty()) {
            Uri incoming = getIntent() == null ? null : getIntent().getData();
            if (incoming != null) addDocuments(java.util.Collections.singletonList(incoming));
        }
        if (state != null) {
            chromeVisible = state.getBoolean("chrome_visible", true);
            applyChrome();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null && !busy)
            addDocuments(java.util.Collections.singletonList(intent.getData()));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private void styleSystemBars() {
        getWindow().setStatusBarColor(barBackground);
        getWindow().setNavigationBarColor(barBackground);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (medium) view.setTypeface(android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL));
        return view;
    }

    private Button button(String title, View.OnClickListener onClick) {
        Button item = new Button(this);
        item.setText(title);
        item.setTextSize(13);
        item.setAllCaps(false);
        item.setTypeface(android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL));
        item.setTextColor(colors.foreground);
        item.setPadding(dp(13), 0, dp(13), 0);
        item.setMinWidth(0);
        item.setMinimumWidth(0);
        item.setMinHeight(0);
        item.setMinimumHeight(0);
        item.setElevation(0);
        item.setStateListAnimator(null);
        item.setBackground(colors.rounded(this, colors.surface, Color.TRANSPARENT, 11));
        item.setOnClickListener(onClick);
        return item;
    }

    private void primary(Button item) {
        item.setTextColor(colors.onAccent);
        item.setBackground(colors.rounded(this, colors.accent, Color.TRANSPARENT, 11));
    }

    private void quiet(Button item) {
        item.setBackground(colors.rounded(this, colors.surface, Color.TRANSPARENT, 11));
    }

    private Button documentButton(String title, View.OnClickListener onClick) {
        Button item = button(title, onClick);
        documentButtons.add(item);
        return item;
    }

    private void addButton(LinearLayout row, Button item) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
        params.rightMargin = dp(7);
        row.addView(item, params);
    }

    private ReaderIcon iconButton(String icon, String label, View.OnClickListener action) {
        return new ReaderIcon(this, icon, barForeground, label, action);
    }

    private void buildInterface() {
        host = new FrameLayout(this);
        host.setBackgroundColor(colors.canvas);
        setContentView(host);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(colors.canvas);
        host.addView(content, new FrameLayout.LayoutParams(-1, -1));

        ZoomScrollView pan = new ZoomScrollView(this);
        pdfPages = new PdfPageList(this, pan, new PdfPageList.PageListener() {
            @Override public void onVisiblePageChanged(int position) {
                if (renderer != null && pageIndex != position) {
                    pageIndex = position;
                    refreshUI();
                    showPageBadge();
                }
            }
            @Override public void onSingleTap() { toggleChrome(); }
            @Override public void onDoubleTap(int position) { showFocus(position); }
            @Override public void onZoomChanged(float zoom) {
                if (zoomButton != null) zoomButton.setText(zoomTitle(zoom));
            }
        });
        pdfPages.setBackgroundColor(colors.canvas);
        pan.addView(pdfPages, new HorizontalScrollView.LayoutParams(-1, -1));
        content.addView(pan, new FrameLayout.LayoutParams(-1, -1));

        // A quiet empty state, consistent with the reader's dark toolbar.
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        empty.setBackgroundColor(colors.canvas);
        TextView emblem = text("PDF", 17, colors.accent, true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(colors.rounded(this, colors.subtle, Color.TRANSPARENT, 18));
        LinearLayout.LayoutParams emblemParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        emblemParams.bottomMargin = dp(18);
        empty.addView(emblem, emblemParams);
        TextView title = text("Your documents, simplified", 19, colors.foreground, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, new LinearLayout.LayoutParams(-1, dp(35)));
        TextView description = text("Open a PDF to read, organize, extract and share.",
            13, colors.mutedText, false);
        description.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, dp(48));
        descParams.bottomMargin = dp(16);
        empty.addView(description, descParams);
        Button openEmpty = button("Open a PDF", view -> chooseDocument());
        primary(openEmpty);
        empty.addView(openEmpty, new LinearLayout.LayoutParams(-2, dp(47)));
        emptyView = empty;
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        pageScrubber = new PageScrubber(this, position -> {
            if (renderer == null || busy || position < 0 || position >= pageOrder.size()) return;
            if (pageIndex == position) return;
            pageIndex = position;
            pdfPages.scrollToPage(position);
            refreshUI();
        });
        pageScrubber.setPage(0, 0);
        FrameLayout.LayoutParams scrubParams = new FrameLayout.LayoutParams(
            dp(65), -1, Gravity.END);
        scrubParams.topMargin = dp(67);
        scrubParams.bottomMargin = dp(121);
        host.addView(pageScrubber, scrubParams);

        // Fixed overlays: hiding controls never resizes the document viewport.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);
        header.setBackgroundColor(barBackground);
        headerView = header;
        host.addView(header, new FrameLayout.LayoutParams(-1, dp(59), Gravity.TOP));

        ReaderIcon back = iconButton(ReaderIcon.BACK, "Back", view -> {
            if (renderer != null) {
                closeAllDocuments();
            } else finish();
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(47), dp(49)));

        View hairline = new View(this);
        hairline.setBackgroundColor(Color.rgb(74, 77, 85));
        LinearLayout.LayoutParams hairlineParams = new LinearLayout.LayoutParams(dp(1), dp(26));
        hairlineParams.rightMargin = dp(9);
        header.addView(hairline, hairlineParams);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(meta, new LinearLayout.LayoutParams(0, -1, 1));
        fileLabel = text("PDF", 14, barForeground, true);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        meta.addView(fileLabel, new LinearLayout.LayoutParams(-1, dp(23)));
        pageLabel = text("Open a document", 11, barMuted, false);
        pageLabel.setSingleLine(true);
        pageLabel.setOnClickListener(view -> showPages());
        meta.addView(pageLabel, new LinearLayout.LayoutParams(-1, dp(19)));

        openButton = iconButton(ReaderIcon.OPEN, "Open PDF", view -> chooseDocument());
        header.addView(openButton, new LinearLayout.LayoutParams(dp(43), dp(48)));
        shareButton = iconButton(ReaderIcon.SHARE, "Share PDF",
            view -> sharePages(new ArrayList<>(pageOrder)));
        header.addView(shareButton, new LinearLayout.LayoutParams(dp(43), dp(48)));
        ReaderIcon more = iconButton(ReaderIcon.MORE, "More actions", this::showReaderMenu);
        header.addView(more, new LinearLayout.LayoutParams(dp(43), dp(48)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(barBackground);
        footerView = footer;
        host.addView(footer, new FrameLayout.LayoutParams(-1, dp(112), Gravity.BOTTOM));

        LinearLayout paging = new LinearLayout(this);
        paging.setGravity(Gravity.CENTER_VERTICAL);
        paging.setPadding(dp(13), dp(1), dp(12), 0);
        footer.addView(paging, new LinearLayout.LayoutParams(-1, dp(43)));

        // Similar to the stock/timeframe selector in the provided reference.
        // Each compact target opens its own vertical flick-to-select wheel.
        documentSelector = text("Choose PDF ▾", 13, barForeground, true);
        documentSelector.setSingleLine(true);
        documentSelector.setEllipsize(android.text.TextUtils.TruncateAt.END);
        documentSelector.setContentDescription("Choose loaded PDF");
        documentSelector.setOnClickListener(view -> showWheel(false));
        enableSelectorScrub(documentSelector, false);
        paging.addView(documentSelector, new LinearLayout.LayoutParams(0, dp(42), 1));

        pageSelector = text("— / — ▾", 12, barForeground, true);
        pageSelector.setSingleLine(true);
        pageSelector.setGravity(Gravity.CENTER);
        pageSelector.setContentDescription("Scroll to select a page");
        pageSelector.setOnClickListener(view -> showWheel(true));
        enableSelectorScrub(pageSelector, true);
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(dp(100), dp(42));
        pageParams.leftMargin = dp(4);
        paging.addView(pageSelector, pageParams);

        zoomButton = button("100% ▾", view -> chooseZoom(false));
        zoomButton.setTextColor(barMuted);
        zoomButton.setTextSize(12);
        zoomButton.setPadding(dp(2), 0, dp(2), 0);
        zoomButton.setBackground(colors.rounded(this, Color.TRANSPARENT,
            Color.TRANSPARENT, 8));
        documentButtons.add(zoomButton);
        paging.addView(zoomButton, new LinearLayout.LayoutParams(dp(78), dp(40)));

        View rule = new View(this);
        rule.setBackgroundColor(Color.rgb(62, 64, 69));
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, dp(1));
        ruleParams.leftMargin = dp(14);
        ruleParams.rightMargin = dp(14);
        footer.addView(rule, ruleParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(4), dp(1), dp(4), dp(3));
        footer.addView(actions, new LinearLayout.LayoutParams(-1, dp(67)));
        addAction(actions, ReaderIcon.PAGES, "Pages", view -> showWheel(true));
        editButton = addAction(actions, ReaderIcon.EDIT, "Edit", view -> showEditMenu());
        addAction(actions, ReaderIcon.SHARE, "Share", view ->
            sharePages(new ArrayList<>(pageOrder)));
        addAction(actions, ReaderIcon.OPEN, "Add PDF", view -> chooseDocument());

        refreshUI();
    }

    private ReaderIcon addAction(LinearLayout actions, String symbol, String label,
                                 View.OnClickListener callback) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setContentDescription(label);
        cell.setOnClickListener(view -> {
            if (ready()) callback.onClick(view);
        });
        actions.addView(cell, new LinearLayout.LayoutParams(0, dp(65), 1));

        ReaderIcon icon = iconButton(symbol, label, view -> {
            if (ready()) callback.onClick(view);
        });
        cell.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(38)));
        TextView caption = text(label, 10, barMuted, false);
        caption.setGravity(Gravity.CENTER);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, dp(18)));
        documentButtons.add(icon);
        return icon;
    }

    private void showReaderMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Edit PDF");
        popup.getMenu().add(0, 2, 1, "Add more PDFs");
        popup.getMenu().add(0, 3, 2, "Switch PDF");
        popup.getMenu().add(0, 4, 3, "Close all documents");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showEditMenu(); return true;
                case 2: chooseDocument(); return true;
                case 3: showWheel(false); return true;
                case 4: closeAllDocuments(); return true;
            }
            return false;
        });
        popup.show();
    }

    private String shortName(String name) {
        if (name == null) return "PDF";
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            return name.substring(0, name.length() - 4);
        return name;
    }

    private void updatePageCounter() {
        boolean has = renderer != null && !pageOrder.isEmpty();
        if (pageScrubber != null)
            pageScrubber.setPage(has ? pageIndex : 0, has ? pageOrder.size() : 0);
        if (pageLabel != null)
            pageLabel.setText(has
                ? documents.size() + (documents.size() == 1 ? " document" : " documents")
                    + " · " + pageOrder.size() + " pages"
                : "Open documents");
        if (documentSelector != null) {
            documentSelector.setText(has ? shortName(documentName) + "  ▾" : "Choose PDF ▾");
            documentSelector.setEnabled(has);
        }
        if (pageSelector != null) {
            pageSelector.setText(has
                ? (pageIndex + 1) + " / " + pageOrder.size() + "  ▾"
                : "— / —");
            pageSelector.setEnabled(has);
        }
    }

    private void showPageBadge() {
        // Kept as a compatibility point for navigation callbacks.
        updatePageCounter();
    }

    private void dismissWheel() {
        if (wheelOverlay != null) {
            FrameLayout shown = wheelOverlay;
            wheelOverlay = null;
            pickerWheel = null;
            shown.animate().cancel();
            shown.animate().alpha(0f).setDuration(130)
                .withEndAction(() -> {
                    if (host != null && shown.getParent() == host) host.removeView(shown);
                }).start();
        }
    }

    /**
     * Tap to open the wheel, or drag vertically over the collapsed value
     * to operate the very same wheel without a preliminary tap.
     */
    private void enableSelectorScrub(TextView target, boolean pages) {
        final float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        target.setOnTouchListener(new View.OnTouchListener() {
            private float downY, previousY;
            private boolean dragging;
            private android.view.VelocityTracker tracker;

            private void recycle() {
                if (tracker != null) { tracker.recycle(); tracker = null; }
            }

            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (!ready()) return true;
                int action = event.getActionMasked();
                float rawY = event.getRawY();
                switch (action) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        recycle();
                        tracker = android.view.VelocityTracker.obtain();
                        tracker.addMovement(event);
                        downY = previousY = rawY;
                        dragging = false;
                        if (view.getParent() != null)
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (tracker != null) tracker.addMovement(event);
                        if (!dragging && Math.abs(rawY - downY) > slop) {
                            dragging = true;
                            showWheel(pages);
                            if (pickerWheel != null) pickerWheel.beginExternalDrag();
                        }
                        if (dragging && pickerWheel != null)
                            pickerWheel.dragExternal(rawY - previousY);
                        previousY = rawY;
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!dragging) {
                            view.performClick();
                        } else if (pickerWheel != null) {
                            pickerWheel.dragExternal(rawY - previousY);
                            float velocity = 0f;
                            if (tracker != null) {
                                tracker.addMovement(event);
                                tracker.computeCurrentVelocity(1000);
                                velocity = tracker.getYVelocity();
                            }
                            pickerWheel.endExternalDrag(velocity);
                        }
                        dragging = false;
                        recycle();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging && pickerWheel != null)
                            pickerWheel.cancelExternalDrag();
                        dragging = false;
                        recycle();
                        return true;
                }
                return true;
            }
        });
    }

    private void showEditMenu() {
        if (!ready()) return;
        dismissWheel();
        if (editOverlay != null) return;
        final FrameLayout overlay = new FrameLayout(this);
        editOverlay = overlay;
        overlay.setBackgroundColor(Color.argb(148, 0, 0, 0));
        overlay.setOnClickListener(view -> dismissEditMenu(null));
        overlay.setAlpha(0f);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setElevation(dp(14));
        sheet.setBackground(colors.rounded(this, Color.rgb(27, 29, 34),
            Color.TRANSPARENT, 20));
        int available = getResources().getDisplayMetrics().heightPixels - dp(95);
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
            -1, Math.min(dp(452), Math.max(dp(240), available)), Gravity.BOTTOM);
        overlay.addView(sheet, sheetParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(21), dp(4), dp(13), 0);
        sheet.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(61)));
        TextView heading = text("Edit PDF", 18, barForeground, true);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        ReaderIcon close = iconButton(ReaderIcon.CLOSE, "Close edit menu",
            view -> dismissEditMenu(null));
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(9), 0, dp(9), dp(15));
        scroll.addView(items, new android.widget.ScrollView.LayoutParams(-1, -2));

        addEditRow(items, ReaderIcon.MOVE, "Rearrange page",
            "Move the current page to another position", this::movePage);
        addEditRow(items, ReaderIcon.EXTRACT, "Extract pages",
            "Save or share a selected page range", this::extractPages);
        addEditRow(items, ReaderIcon.DELETE, "Remove page",
            "Exclude this page from the edited copy", this::removeCurrentPage);
        addEditRow(items, ReaderIcon.RESET, "Restore original pages",
            "Discard in-session page changes", this::resetPages);
        addEditRow(items, ReaderIcon.SAVE, "Save edited copy",
            "Preserve the PDF content and selectable text",
            () -> chooseSaveDestination(new ArrayList<>(pageOrder), "-edited"));

        sheet.setTranslationY(dp(28));
        overlay.animate().alpha(1f).setDuration(165).start();
        sheet.animate().translationY(0f).setDuration(175)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.45f))
            .start();
    }

    private void addEditRow(LinearLayout list, String symbol, String title,
                            String description, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(10), 0);
        row.setBackground(colors.rounded(this, Color.TRANSPARENT,
            Color.TRANSPARENT, 11));
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(67)));
        ReaderIcon icon = iconButton(symbol, title, view -> dismissEditMenu(action));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1);
        labelParams.leftMargin = dp(5);
        row.addView(labels, labelParams);
        TextView name = text(title, 15, barForeground, true);
        labels.addView(name, new LinearLayout.LayoutParams(-1, dp(27)));
        TextView detail = text(description, 11, barMuted, false);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(detail, new LinearLayout.LayoutParams(-1, dp(20)));
        row.setOnClickListener(view -> dismissEditMenu(action));
    }

    private void dismissEditMenu(Runnable after) {
        final FrameLayout overlay = editOverlay;
        editOverlay = null;
        if (overlay == null) {
            if (after != null) after.run();
            return;
        }
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(120)
            .withEndAction(() -> {
                if (host != null && overlay.getParent() == host) host.removeView(overlay);
                if (after != null) after.run();
            }).start();
    }

    private void showWheel(boolean pages) {
        if (!ready()) return;
        dismissWheel();

        ArrayList<String> items = new ArrayList<>();
        if (pages) {
            for (int i = 0; i < pageOrder.size(); i++)
                items.add("Page " + (i + 1));
        } else {
            for (DocumentSession session : documents)
                items.add(shortName(session.name));
        }
        if (items.isEmpty()) return;

        FrameLayout overlay = new FrameLayout(this);
        wheelOverlay = overlay;
        overlay.setBackgroundColor(Color.argb(78, 0, 0, 0));
        overlay.setOnClickListener(view -> dismissWheel());
        overlay.setAlpha(0f);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(colors.rounded(this, Color.rgb(26, 29, 33),
            Color.TRANSPARENT, 18));
        card.setClickable(true);
        int availableWidth = Math.max(dp(190),
            getResources().getDisplayMetrics().widthPixels - dp(30));
        int availableHeight = Math.max(dp(150),
            getResources().getDisplayMetrics().heightPixels - dp(210));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            Math.min(dp(292), availableWidth), Math.min(dp(376), availableHeight),
            Gravity.BOTTOM | Gravity.START);
        params.leftMargin = dp(15);
        params.bottomMargin = dp(116);
        overlay.addView(card, params);

        TextView heading = text(pages ? "PAGES" : "OPEN DOCUMENTS", 11,
            Color.rgb(170, 177, 187), true);
        heading.setLetterSpacing(.09f);
        heading.setPadding(dp(22), 0, 0, 0);
        card.addView(heading, new LinearLayout.LayoutParams(-1, dp(35)));

        PickerWheel wheel = new PickerWheel(this, items,
            pages ? pageIndex : Math.max(0, activeDocument), selected -> {
                if (wheelOverlay != overlay) return;
                if (pages) {
                    if (renderer != null && selected >= 0 && selected < pageOrder.size()) {
                        pageIndex = selected;
                        pdfPages.scrollToPage(selected);
                        refreshUI();
                    }
                } else {
                    switchDocument(selected);
                }
            });
        pickerWheel = wheel;
        card.addView(wheel, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = text(pages ? "Scroll to jump to a page" :
            "Scroll to switch PDFs", 12, Color.rgb(157, 164, 176), false);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, new LinearLayout.LayoutParams(-1, dp(36)));

        if (!pages) {
            TextView add = text("+ Add more PDFs", 13, Color.rgb(169, 198, 255), true);
            add.setGravity(Gravity.CENTER);
            add.setOnClickListener(view -> {
                dismissWheel();
                chooseDocument();
            });
            card.addView(add, new LinearLayout.LayoutParams(-1, dp(43)));
        }
        overlay.animate().alpha(1f).setDuration(150).start();
    }

    private void applyChrome() {
        animateBar(headerView, chromeVisible, true);
        animateBar(footerView, chromeVisible, false);

    }

    private void animateBar(View bar, boolean show, boolean top) {
        if (bar == null) return;
        bar.animate().cancel();
        int displacement = top ? -1 : 1;
        if (show) {
            bar.setVisibility(View.VISIBLE);
            if (bar.getAlpha() < .9f) {
                bar.setTranslationY(displacement * dp(top ? 18 : 22));
            }
            bar.animate().alpha(1f).translationY(0f).setDuration(185)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
        } else {
            bar.animate().alpha(0f)
                .translationY(displacement * dp(top ? 18 : 22))
                .setDuration(165)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.1f))
                .withEndAction(() -> {
                    if (!chromeVisible) bar.setVisibility(View.GONE);
                })
                .start();
        }
    }

    private void toggleChrome() {
        if (renderer == null || focusOverlay != null) return;
        chromeVisible = !chromeVisible;
        applyChrome();
    }

    private String zoomTitle(float zoom) {
        return Math.round(zoom * 100) + "% ▾";
    }

    private void chooseZoom(boolean focused) {
        if (!ready() && !focused) return;
        String[] labels = new String[ZOOM_LEVELS.length];
        float current = focused
            ? (focusPage == null ? 1f : focusPage.getZoom())
            : pdfPages.getZoom();
        int selected = 0;
        float closest = Float.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            labels[i] = ZOOM_LEVELS[i] + "%";
            float delta = Math.abs(current * 100f - ZOOM_LEVELS[i]);
            if (delta < closest) { closest = delta; selected = i; }
        }
        new AlertDialog.Builder(this)
            .setTitle("Zoom level")
            .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                float level = ZOOM_LEVELS[index] / 100f;
                if (focused) {
                    if (focusPage != null) focusPage.animateToZoom(level);
                } else {
                    pdfPages.setZoom(level);
                }
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showFocus(int position) {
        if (!ready() || position < 0 || position >= pageOrder.size()) return;
        closeFocus(false);
        final int originalPage = pageOrder.get(position);
        final int request = ++focusGeneration;
        final Uri source = sourceUri;
        focusOverlay = new FrameLayout(this);
        // A dimmed scrim leaves the document visible beneath the lifted page.
        focusOverlay.setBackgroundColor(Color.argb(
            colors.dark ? 225 : 190, 14, 18, 25));
        focusPage = new FocusPageView(this, new FocusPageView.Actions() {
            @Override public void onDoubleTap() { closeFocus(); }
            @Override public void onSingleTap() {
                if (focusToolbar != null) {
                    focusToolbar.setVisibility(
                        focusToolbar.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE);
                }
            }
            @Override public void onZoomChanged(float zoom) {
                if (focusZoomButton != null) focusZoomButton.setText(zoomTitle(zoom));
            }
        });
        focusOverlay.addView(focusPage, new FrameLayout.LayoutParams(-1, -1));

        focusLoading = text("Loading page…", 14, Color.WHITE, false);
        focusLoading.setGravity(Gravity.CENTER);
        focusOverlay.addView(focusLoading,
            new FrameLayout.LayoutParams(-1, dp(50), Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        bar.setBackgroundColor(barBackground);
        focusToolbar = bar;
        focusOverlay.addView(bar, new FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP));

        ReaderIcon close = iconButton(ReaderIcon.CLOSE, "Close focused page",
            view -> closeFocus());
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(46)));

        TextView title = text("Page " + (position + 1) + " of " + pageOrder.size(),
            15, barForeground, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        focusZoomButton = button("100% ▾", v -> chooseZoom(true));
        focusZoomButton.setTextColor(barForeground);
        focusZoomButton.setBackground(colors.rounded(this, Color.TRANSPARENT,
            Color.TRANSPARENT, 9));
        focusZoomButton.setContentDescription("Focused zoom level");
        bar.addView(focusZoomButton, new LinearLayout.LayoutParams(-2, dp(42)));

        focusOverlay.setAlpha(0f);
        host.addView(focusOverlay, new FrameLayout.LayoutParams(-1, -1));
        focusOverlay.animate().alpha(1f).setDuration(170)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
            .start();
        background.execute(() -> {
            Bitmap rendered = null;
            String error = null;
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(source, "r")) {
                if (descriptor == null) throw new Exception("Can't open PDF");
                try (PdfRenderer focusedRenderer = new PdfRenderer(descriptor);
                     PdfRenderer.Page page = focusedRenderer.openPage(originalPage)) {
                    float scale = Math.min(3.5f,
                        3000f / Math.max(page.getWidth(), page.getHeight()));
                    rendered = Bitmap.createBitmap(
                        Math.max(1, Math.round(page.getWidth() * scale)),
                        Math.max(1, Math.round(page.getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
                    rendered.eraseColor(Color.WHITE);
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
            } catch (Exception e) {
                if (rendered != null) rendered.recycle();
                rendered = null;
                error = errorMessage(e);
            }
            final Bitmap result = rendered;
            final String message = error;
            runOnUiThread(() -> {
                if (request != focusGeneration || focusOverlay == null || focusPage == null) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    if (focusLoading != null) focusLoading.setVisibility(View.GONE);
                    focusPage.setBitmap(result);
                    focusPage.setAlpha(0f);
                    focusPage.animate().alpha(1f).setDuration(160).start();
                    focusPage.animateToZoom(1.5f);
                } else {
                    toast("Unable to focus page: " + message);
                    closeFocus();
                }
            });
        });
    }

    private void closeFocus() { closeFocus(true); }

    private void closeFocus(boolean animate) {
        focusGeneration++;
        final FrameLayout overlay = focusOverlay;
        final FocusPageView page = focusPage;
        focusOverlay = null;
        focusPage = null;
        focusZoomButton = null;
        focusToolbar = null;
        focusLoading = null;
        if (overlay == null) return;
        overlay.animate().cancel();
        Runnable clean = () -> {
            if (host != null && overlay.getParent() == host) host.removeView(overlay);
            if (page != null) page.dispose();
        };
        if (animate) {
            overlay.animate().alpha(0f).setDuration(145)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(clean).start();
        } else clean.run();
    }

    @Override public void onBackPressed() {
        if (wheelOverlay != null) {
            dismissWheel();
        } else if (focusOverlay != null) {
            closeFocus();
        } else if (!chromeVisible) {
            chromeVisible = true;
            applyChrome();
        } else {
            super.onBackPressed();
        }
    }

    private boolean ready() { return renderer != null && !pageOrder.isEmpty() && !busy; }

    private void refreshUI() {
        boolean has = renderer != null && !pageOrder.isEmpty();
        boolean enabled = has && !busy;
        if (editButton != null) editButton.setEnabled(enabled);
        if (shareButton != null) {
            shareButton.setEnabled(enabled);
            shareButton.setAlpha(enabled ? 1f : .40f);
        }
        if (openButton != null) openButton.setEnabled(!busy);
        for (View action : documentButtons) {
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : .40f);
        }
        fileLabel.setText(has ? documentName : "PDF");
        updatePageCounter();

    }

    private void setBusy(boolean value) {
        busy = value;
        if (editButton != null)
            editButton.setContentDescription(value ? "Working…" : "Edit PDF");
        refreshUI();
    }

    private void chooseDocument() {
        if (busy) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/pdf");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, OPEN_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE) {
            if (resultCode != RESULT_OK || data == null) return;
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri item = data.getClipData().getItemAt(i).getUri();
                    if (item != null && !picked.contains(item)) picked.add(item);
                }
            }
            if (data.getData() != null && !picked.contains(data.getData()))
                picked.add(data.getData());
            for (Uri item : picked) {
                try {
                    getContentResolver().takePersistableUriPermission(
                        item, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
            addDocuments(picked);
        } else if (requestCode == SAVE_FILE) {
            ArrayList<Integer> selected = pendingSavePages;
            pendingSavePages = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && selected != null) {
                exportToUri(data.getData(), selected);
            }
        }
    }

    private String getDocumentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null)
                return cursor.getString(0);
        } catch (Exception ignored) { }
        return "document.pdf";
    }

    private void addDocuments(List<Uri> picked) {
        if (busy || picked == null || picked.isEmpty()) return;
        int firstNew = -1;
        for (Uri uri : picked) {
            if (uri == null) continue;
            int existing = -1;
            for (int i = 0; i < documents.size(); i++)
                if (documents.get(i).uri.equals(uri)) { existing = i; break; }
            if (existing >= 0) {
                if (firstNew < 0) firstNew = existing;
            } else {
                documents.add(new DocumentSession(uri, getDocumentName(uri)));
                if (firstNew < 0) firstNew = documents.size() - 1;
            }
        }
        if (firstNew >= 0) switchDocument(firstNew);
    }

    private void saveActiveSession() {
        if (activeDocument < 0 || activeDocument >= documents.size() || renderer == null)
            return;
        DocumentSession current = documents.get(activeDocument);
        current.order.clear();
        current.order.addAll(pageOrder);
        current.page = pageIndex;
    }

    private void switchDocument(int position) {
        if (busy || position < 0 || position >= documents.size()) return;
        if (position == activeDocument && renderer != null) return;
        DocumentSession chosen = documents.get(position);
        try {
            ParcelFileDescriptor descriptor =
                getContentResolver().openFileDescriptor(chosen.uri, "r");
            if (descriptor == null) throw new Exception("Could not read file");
            PdfRenderer next;
            try { next = new PdfRenderer(descriptor); }
            catch (Exception error) { descriptor.close(); throw error; }

            saveActiveSession();
            closeDocument();
            sourceFd = descriptor;
            renderer = next;
            sourceUri = chosen.uri;
            documentName = chosen.name;
            activeDocument = position;
            pageOrder.clear();
            if (validOrder(chosen.order)) {
                pageOrder.addAll(chosen.order);
            } else {
                for (int i = 0; i < renderer.getPageCount(); i++) pageOrder.add(i);
                chosen.order.clear();
                chosen.order.addAll(pageOrder);
                chosen.page = 0;
            }
            pageIndex = Math.max(0, Math.min(pageOrder.size() - 1, chosen.page));
            pdfPages.setDocument(renderer, pageOrder);
            pdfPages.scrollToPage(pageIndex);
            if (emptyView != null) emptyView.setVisibility(View.GONE);
            if (zoomButton != null) zoomButton.setText("100% ▾");
            chromeVisible = true;
            applyChrome();
            refreshUI();
        } catch (Exception error) {
            toast("Could not open PDF: " + error.getMessage());
        }
    }

    private void closeAllDocuments() {
        dismissWheel();
        closeDocument();
        documents.clear();
        activeDocument = -1;
        sourceUri = null;
        pageOrder.clear();
        pageIndex = 0;
        documentName = "document.pdf";
        refreshUI();
    }

    private void closeDocument() {
        closeFocus(false);
        if (pdfPages != null) pdfPages.clearDocument();
        if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
        if (renderer != null) { try { renderer.close(); } catch (Exception ignored) { } renderer = null; }
        if (sourceFd != null) { try { sourceFd.close(); } catch (Exception ignored) { } sourceFd = null; }
    }

    private void navigate(int distance) {
        if (!ready()) return;
        int target = pageIndex + distance;
        if (target < 0 || target >= pageOrder.size()) return;
        pageIndex = target;
        pdfPages.scrollToPage(pageIndex);
        refreshUI();
    }

    private void showPages() {
        if (!ready()) return;
        String[] labels = new String[pageOrder.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = (i + 1) + "   ·   Original page " + (pageOrder.get(i) + 1);
        new AlertDialog.Builder(this)
            .setTitle("Pages (tap to view)")
            .setSingleChoiceItems(labels, pageIndex, (dialog, which) -> {
                pageIndex = which;
                dialog.dismiss();
                pdfPages.scrollToPage(pageIndex);
                refreshUI();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private EditText numberInput(int initial, String hint) {
        EditText input = new EditText(this);
        input.setText(String.valueOf(initial));
        input.setSelectAllOnFocus(true);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(16);
        return input;
    }

    private LinearLayout dialogFields(EditText... inputs) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), dp(4));
        for (EditText field : inputs) container.addView(
            field, new LinearLayout.LayoutParams(-1, dp(55)));
        return container;
    }

    private int numeric(EditText input) {
        try { return Integer.parseInt(input.getText().toString().trim()); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private void movePage() {
        if (!ready()) return;
        EditText to = numberInput(pageIndex + 1, "Position");
        new AlertDialog.Builder(this)
            .setTitle("Move page " + (pageIndex + 1) + " to position")
            .setView(dialogFields(to))
            .setPositiveButton("Move", (dialog, which) -> {
                int target = numeric(to) - 1;
                if (target < 0 || target >= pageOrder.size()) {
                    toast("Enter a position between 1 and " + pageOrder.size());
                    return;
                }
                Integer original = pageOrder.remove(pageIndex);
                pageOrder.add(target, original);
                pageIndex = target;
                pdfPages.pagesChanged(pageIndex);
                refreshUI();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void removeCurrentPage() {
        if (!ready()) return;
        if (pageOrder.size() == 1) { toast("Keep at least one page"); return; }
        new AlertDialog.Builder(this)
            .setMessage("Remove page " + (pageIndex + 1) + " from the saved copy?")
            .setPositiveButton("Remove", (dialog, which) -> {
                pageOrder.remove(pageIndex);
                pageIndex = Math.min(pageIndex, pageOrder.size() - 1);
                pdfPages.pagesChanged(pageIndex);
                refreshUI();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void resetPages() {
        if (!ready()) return;
        new AlertDialog.Builder(this)
            .setMessage("Restore all pages to their original order?")
            .setPositiveButton("Reset", (dialog, which) -> {
                pageOrder.clear();
                for (int i = 0; i < renderer.getPageCount(); i++) pageOrder.add(i);
                pageIndex = 0;
                pdfPages.pagesChanged(pageIndex);
                refreshUI();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void extractPages() {
        if (!ready()) return;
        EditText start = numberInput(1, "From page");
        EditText end = numberInput(pageOrder.size(), "To page (inclusive)");
        new AlertDialog.Builder(this)
            .setTitle("Extract page range")
            .setView(dialogFields(start, end))
            .setPositiveButton("Save range", (dialog, which) -> handleRange(start, end, false))
            .setNeutralButton("Share range", (dialog, which) -> handleRange(start, end, true))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void handleRange(EditText startInput, EditText endInput, boolean share) {
        int start = numeric(startInput);
        int end = numeric(endInput);
        if (start < 1 || end < start || end > pageOrder.size()) {
            toast("Use a range between 1 and " + pageOrder.size());
            return;
        }
        ArrayList<Integer> range = new ArrayList<>(pageOrder.subList(start - 1, end));
        if (share) sharePages(range);
        else chooseSaveDestination(range, "-pages-" + start + "-" + end);
    }

    private String fileBaseName() {
        String name = documentName;
        if (name.toLowerCase().endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        return name.replaceAll("[\\\\/\\u0000-\\u001f]", "_");
    }

    private void chooseSaveDestination(ArrayList<Integer> pages, String suffix) {
        if (!ready()) return;
        pendingSavePages = pages;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/pdf");
        save.putExtra(Intent.EXTRA_TITLE, fileBaseName() + suffix + ".pdf");
        startActivityForResult(save, SAVE_FILE);
    }

    private void exportToUri(Uri destination, ArrayList<Integer> selection) {
        final Uri original = sourceUri;
        final int total = renderer.getPageCount();
        setBusy(true);
        background.execute(() -> {
            String error = null;
            try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new Exception("Cannot open destination");
                writeSelection(original, out, selection, total);
            } catch (Exception exception) { error = errorMessage(exception); }
            final String result = error;
            runOnUiThread(() -> {
                setBusy(false);
                if (result == null) toast("PDF copy saved");
                else showError(result);
            });
        });
    }

    private void sharePages(ArrayList<Integer> selection) {
        if (!ready()) return;
        final Uri original = sourceUri;
        final int total = renderer.getPageCount();
        setBusy(true);
        background.execute(() -> {
            File shared = null;
            String error = null;
            try {
                File directory = new File(getCacheDir(), "shared");
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create share cache");
                // Clean previous temporary shares to limit storage use.
                File[] old = directory.listFiles();
                if (old != null) for (File file : old) file.delete();
                shared = new File(directory, "shared-pdf.pdf");
                try (OutputStream out = new FileOutputStream(shared)) {
                    writeSelection(original, out, selection, total);
                }
            } catch (Exception exception) { error = errorMessage(exception); }
            final String result = error;
            final File completed = shared;
            runOnUiThread(() -> {
                setBusy(false);
                if (result == null && completed != null) showShareChooser(completed);
                else showError(result == null ? "Could not prepare the PDF" : result);
            });
        });
    }

    private void showShareChooser(File pdf) {
        try {
            Uri sharedUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", pdf);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, sharedUri);
            send.setClipData(ClipData.newUri(getContentResolver(), "PDF", sharedUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share PDF with"));
        } catch (Exception error) { showError(errorMessage(error)); }
    }

    /**
     * Write actual PDF page content without rendering pages as bitmaps.
     * Original content is copied byte-for-byte when the selection is unchanged.
     */
    private void writeSelection(Uri source, OutputStream destination, List<Integer> selection,
                                int totalPages) throws Exception {
        if (selection.isEmpty()) throw new Exception("Select at least one page");
        boolean unchanged = selection.size() == totalPages;
        for (int i = 0; unchanged && i < selection.size(); i++) {
            unchanged = selection.get(i) == i;
        }
        try (InputStream input = new BufferedInputStream(getContentResolver().openInputStream(source))) {
            if (unchanged) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) destination.write(buffer, 0, count);
            } else {
                try (PDDocument original = PDDocument.load(input);
                     PDDocument result = new PDDocument()) {
                    for (int originalPage : selection) {
                        if (originalPage < 0 || originalPage >= original.getNumberOfPages())
                            throw new Exception("The source PDF has changed");
                        PDPage sourcePage = original.getPage(originalPage);
                        PDPage imported = result.importPage(sourcePage);
                        // Some PDFs inherit fonts, images, etc. from a parent page node.
                        if (sourcePage.getResources() != null &&
                            !imported.getCOSObject().containsKey(COSName.RESOURCES)) {
                            imported.setResources(sourcePage.getResources());
                        }
                        imported.setRotation(sourcePage.getRotation());
                        imported.setMediaBox(sourcePage.getMediaBox());
                        imported.setCropBox(sourcePage.getCropBox());
                    }
                    result.save(destination);
                }
            }
            destination.flush();
        }
    }

    private String errorMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("Could not complete")
            .setMessage(message).setPositiveButton("OK", null).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean validOrder(ArrayList<Integer> order) {
        if (renderer == null || order == null || order.isEmpty()
            || order.size() > renderer.getPageCount()) return false;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (Integer value : order) {
            if (value == null || value < 0 || value >= renderer.getPageCount()
                || !seen.add(value)) return false;
        }
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("chrome_visible", chromeVisible);
        saveActiveSession();
        ArrayList<Bundle> entries = new ArrayList<>();
        for (DocumentSession session : documents) {
            Bundle item = new Bundle();
            item.putString("uri", session.uri.toString());
            item.putString("name", session.name);
            item.putIntegerArrayList("order", new ArrayList<>(session.order));
            item.putInt("page", session.page);
            entries.add(item);
        }
        state.putParcelableArrayList("open_documents", entries);
        state.putInt("active_document", activeDocument);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (pdfPages != null) pdfPages.resetZoom();
        if (zoomButton != null) zoomButton.setText("100% ▾");
    }

    @Override protected void onDestroy() {
        if (wheelOverlay != null) {
            wheelOverlay.animate().cancel();
            host.removeView(wheelOverlay);
            wheelOverlay = null;
            pickerWheel = null;
        }
        closeDocument();
        if (pdfPages != null) pdfPages.dispose();
        background.shutdown();
        super.onDestroy();
    }
}
