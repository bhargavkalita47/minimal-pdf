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
    private ReaderIcon saveButton;
    private ReaderIcon shareButton;
    private ReaderIcon openButton;
    private TextView pageBadge;
    private Runnable badgeFade;
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
        String savedUri = state == null ? null : state.getString("open_uri");
        Uri incoming = savedUri == null
            ? (getIntent() == null ? null : getIntent().getData()) : Uri.parse(savedUri);
        if (incoming != null) {
            openDocument(incoming);
            if (renderer != null && state != null && savedUri != null) {
                ArrayList<Integer> savedOrder = state.getIntegerArrayList("page_order");
                if (validOrder(savedOrder)) {
                    pageOrder.clear();
                    pageOrder.addAll(savedOrder);
                    pageIndex = Math.min(Math.max(0, state.getInt("page_index", 0)), pageOrder.size() - 1);
                    pdfPages.pagesChanged(pageIndex);
                    refreshUI();
                }
            }
        }
        if (state != null) {
            chromeVisible = state.getBoolean("chrome_visible", true);
            applyChrome();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null && !busy) openDocument(intent.getData());
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

        pageBadge = text("1", 16, barForeground, true);
        pageBadge.setGravity(Gravity.CENTER);
        pageBadge.setBackground(colors.rounded(this, barBackground, Color.TRANSPARENT, 24));
        pageBadge.setAlpha(0f);
        pageBadge.setVisibility(View.GONE);
        pageBadge.setOnClickListener(view -> showPages());
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
            dp(51), dp(54), Gravity.END | Gravity.CENTER_VERTICAL);
        badgeParams.rightMargin = -dp(10);
        host.addView(pageBadge, badgeParams);

        // Fixed overlays: hiding controls never resizes the document viewport.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);
        header.setBackgroundColor(barBackground);
        headerView = header;
        host.addView(header, new FrameLayout.LayoutParams(-1, dp(59), Gravity.TOP));

        ReaderIcon back = iconButton(ReaderIcon.BACK, "Back", view -> {
            if (renderer != null) {
                closeDocument();
                pageOrder.clear();
                sourceUri = null;
                refreshUI();
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
        paging.setPadding(dp(12), dp(1), dp(12), 0);
        footer.addView(paging, new LinearLayout.LayoutParams(-1, dp(43)));
        ReaderIcon previous = iconButton(ReaderIcon.PREVIOUS, "Previous page",
            view -> navigate(-1));
        documentButtons.add(previous);
        paging.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(40)));

        TextView current = text("1 / 1", 12, barMuted, true);
        current.setGravity(Gravity.CENTER);
        current.setOnClickListener(view -> showPages());
        // Keep a secondary display of page position alongside the fixed badge.
        paging.addView(current, new LinearLayout.LayoutParams(dp(69), dp(40)));
        current.setTag("page-counter");

        ReaderIcon next = iconButton(ReaderIcon.NEXT, "Next page",
            view -> navigate(1));
        documentButtons.add(next);
        paging.addView(next, new LinearLayout.LayoutParams(dp(42), dp(40)));

        View space = new View(this);
        paging.addView(space, new LinearLayout.LayoutParams(0, 1, 1));

        zoomButton = button("100% ▾", view -> chooseZoom(false));
        zoomButton.setTextColor(barForeground);
        zoomButton.setTextSize(13);
        zoomButton.setBackground(colors.rounded(this, Color.TRANSPARENT,
            Color.TRANSPARENT, 8));
        documentButtons.add(zoomButton);
        paging.addView(zoomButton, new LinearLayout.LayoutParams(dp(85), dp(40)));

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
        addAction(actions, ReaderIcon.PAGES, "Pages", view -> showPages());
        addAction(actions, ReaderIcon.MOVE, "Move", view -> movePage());
        addAction(actions, ReaderIcon.EXTRACT, "Extract", view -> extractPages());
        addAction(actions, ReaderIcon.DELETE, "Remove", view -> removeCurrentPage());
        saveButton = addAction(actions, ReaderIcon.SAVE, "Save", view ->
            chooseSaveDestination(new ArrayList<>(pageOrder), "-edited"));

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
        popup.getMenu().add(0, 1, 0, "Save edited copy");
        popup.getMenu().add(0, 2, 1, "Reset page order");
        popup.getMenu().add(0, 3, 2, "Open another PDF");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (ready()) chooseSaveDestination(new ArrayList<>(pageOrder), "-edited");
                    return true;
                case 2:
                    resetPages();
                    return true;
                case 3:
                    chooseDocument();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void updatePageCounter() {
        if (pageBadge == null || pageLabel == null) return;
        boolean has = renderer != null && !pageOrder.isEmpty();
        pageBadge.setVisibility(has ? View.VISIBLE : View.GONE);
        pageBadge.setText(has ? String.valueOf(pageIndex + 1) : "");
        pageLabel.setText(has
            ? (pageIndex + 1) + " / " + pageOrder.size() + " pages"
            : "Open a document");
        if (footerView instanceof LinearLayout) {
            LinearLayout footer = (LinearLayout)footerView;
            LinearLayout paging = (LinearLayout)footer.getChildAt(0);
            for (int i = 0; i < paging.getChildCount(); i++) {
                View child = paging.getChildAt(i);
                if ("page-counter".equals(child.getTag()) && child instanceof TextView) {
                    ((TextView)child).setText(has
                        ? (pageIndex + 1) + " / " + pageOrder.size() : "— / —");
                    break;
                }
            }
        }
    }

    private void showPageBadge() {
        if (pageBadge == null || renderer == null || !chromeVisible) return;
        pageBadge.setVisibility(View.VISIBLE);
        pageBadge.animate().cancel();
        pageBadge.animate().alpha(1f).setDuration(90).start();
        if (badgeFade != null) pageBadge.removeCallbacks(badgeFade);
        badgeFade = () -> {
            if (pageBadge != null) pageBadge.animate().alpha(0f)
                .setDuration(200).start();
        };
        pageBadge.postDelayed(badgeFade, 1300);
    }

    private void applyChrome() {
        animateBar(headerView, chromeVisible, true);
        animateBar(footerView, chromeVisible, false);
        if (pageBadge != null && !chromeVisible) {
            pageBadge.animate().cancel();
            pageBadge.animate().alpha(0f).setDuration(130).start();
        } else if (chromeVisible) {
            showPageBadge();
        }
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
                    if (focusPage != null) focusPage.setZoom(level);
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
        closeFocus();
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
        bar.setBackgroundColor(colors.surface);
        focusToolbar = bar;
        focusOverlay.addView(bar, new FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP));

        Button close = button("×", v -> closeFocus());
        close.setTextSize(25);
        close.setContentDescription("Close focused page");
        addButton(bar, close);

        TextView title = text("Page " + (position + 1) + " of " + pageOrder.size(),
            15, colors.foreground, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        focusZoomButton = button("100% ▾", v -> chooseZoom(true));
        focusZoomButton.setContentDescription("Focused zoom level");
        bar.addView(focusZoomButton, new LinearLayout.LayoutParams(-2, dp(42)));

        host.addView(focusOverlay, new FrameLayout.LayoutParams(-1, -1));
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
                    focusPage.setZoom(1.5f);
                } else {
                    toast("Unable to focus page: " + message);
                    closeFocus();
                }
            });
        });
    }

    private void closeFocus() {
        focusGeneration++;
        if (focusPage != null) focusPage.dispose();
        focusPage = null;
        focusZoomButton = null;
        focusToolbar = null;
        focusLoading = null;
        if (focusOverlay != null) {
            if (host != null) host.removeView(focusOverlay);
            focusOverlay = null;
        }
    }

    @Override public void onBackPressed() {
        if (focusOverlay != null) {
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
        if (saveButton != null) saveButton.setEnabled(enabled);
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
        if (saveButton != null)
            saveButton.setContentDescription(value ? "Saving PDF…" : "Save PDF");
        refreshUI();
    }

    private void chooseDocument() {
        if (busy) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/pdf");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, OPEN_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                    data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            openDocument(data.getData());
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

    private void openDocument(Uri uri) {
        if (busy) return;
        try {
            ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new Exception("Could not read file");
            PdfRenderer newRenderer;
            try { newRenderer = new PdfRenderer(descriptor); }
            catch (Exception error) { descriptor.close(); throw error; }
            closeDocument();
            renderer = newRenderer;
            sourceFd = descriptor;
            sourceUri = uri;
            documentName = getDocumentName(uri);
            pageOrder.clear();
            for (int index = 0; index < renderer.getPageCount(); index++) pageOrder.add(index);
            pageIndex = 0;
            chromeVisible = true;
            applyChrome();
            pdfPages.setDocument(renderer, pageOrder);
            showPageBadge();
            if (zoomButton != null) zoomButton.setText("100% ▾");
            emptyView.setVisibility(View.GONE);
            refreshUI();
        } catch (Exception error) {
            toast("Could not open PDF: " + error.getMessage());
        }
    }

    private void closeDocument() {
        closeFocus();
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
        if (sourceUri != null && renderer != null) {
            state.putString("open_uri", sourceUri.toString());
            state.putIntegerArrayList("page_order", new ArrayList<>(pageOrder));
            state.putInt("page_index", pageIndex);
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (pdfPages != null) pdfPages.resetZoom();
        if (zoomButton != null) zoomButton.setText("100% ▾");
    }

    @Override protected void onDestroy() {
        closeDocument();
        if (pdfPages != null) pdfPages.dispose();
        background.shutdown();
        super.onDestroy();
    }
}
