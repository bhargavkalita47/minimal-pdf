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
    private static final int INK = Color.rgb(27, 30, 36);
    private static final int SOFT = Color.rgb(242, 243, 245);

    private Uri sourceUri;
    private ParcelFileDescriptor sourceFd;
    private PdfRenderer renderer;
    // The order contains ORIGINAL zero-based page numbers, not rendered bitmaps.
    private final ArrayList<Integer> pageOrder = new ArrayList<>();
    private int pageIndex = 0;
    private String documentName = "document.pdf";
    private PdfPageList pdfPages;
    private TextView emptyView;
    private TextView fileLabel;
    private TextView pageLabel;
    private Button saveButton;
    private Button shareButton;
    private Button openButton;
    private boolean busy = false;

    private ArrayList<Integer> pendingSavePages;
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildInterface();
        Uri incoming = getIntent() == null ? null : getIntent().getData();
        if (incoming != null) openDocument(incoming);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null && !busy) openDocument(intent.getData());
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(10));
        return background;
    }

    private Button button(String title, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setTextColor(INK);
        b.setAllCaps(false);
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(shape(SOFT));
        b.setOnClickListener(action);
        return b;
    }

    private void addButton(LinearLayout row, Button b) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
        params.rightMargin = dp(7);
        row.addView(b, params);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(8), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));

        openButton = button("Open", v -> chooseDocument());
        addButton(header, openButton);

        fileLabel = new TextView(this);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        fileLabel.setTextSize(15);
        fileLabel.setTextColor(INK);
        fileLabel.setTypeface(null, 1);
        fileLabel.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(fileLabel, new LinearLayout.LayoutParams(0, dp(40), 1));

        shareButton = button("Share", v -> sharePages(new ArrayList<>(pageOrder)));
        addButton(header, shareButton);
        saveButton = button("Save", v -> chooseSaveDestination(new ArrayList<>(pageOrder), "-edited"));
        saveButton.setTextColor(Color.WHITE);
        saveButton.setBackground(shape(INK));
        header.addView(saveButton, new LinearLayout.LayoutParams(-2, dp(40)));

        View rule = new View(this);
        rule.setBackgroundColor(Color.rgb(230, 232, 236));
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(228, 230, 234));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView pan = new HorizontalScrollView(this);
        pan.setFillViewport(true);
        pan.setHorizontalScrollBarEnabled(false);
        pan.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pdfPages = new PdfPageList(this, pan, position -> {
            if (renderer != null && pageIndex != position) {
                pageIndex = position;
                refreshUI();
            }
        });
        pan.addView(pdfPages, new HorizontalScrollView.LayoutParams(-1, -1));
        content.addView(pan, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setText("Open a PDF to begin");
        emptyView.setTextColor(Color.DKGRAY);
        emptyView.setTextSize(17);
        emptyView.setGravity(Gravity.CENTER);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.WHITE);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(67)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(12), dp(10), dp(5), dp(12));
        scroll.addView(actions, new HorizontalScrollView.LayoutParams(-2, -1));
        addButton(actions, button("‹", v -> navigate(-1)));
        pageLabel = new TextView(this);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextColor(INK);
        pageLabel.setTextSize(13);
        pageLabel.setMinWidth(dp(65));
        pageLabel.setOnClickListener(v -> showPages());
        actions.addView(pageLabel, new LinearLayout.LayoutParams(-2, dp(40)));
        addButton(actions, button("›", v -> navigate(1)));
        addButton(actions, button("Pages", v -> showPages()));
        addButton(actions, button("Move to…", v -> movePage()));
        addButton(actions, button("Extract", v -> extractPages()));
        addButton(actions, button("Remove", v -> removeCurrentPage()));
        addButton(actions, button("Reset", v -> resetPages()));
        refreshUI();
    }

    private boolean ready() { return renderer != null && !pageOrder.isEmpty() && !busy; }

    private void refreshUI() {
        boolean has = renderer != null && !pageOrder.isEmpty();
        saveButton.setEnabled(has && !busy);
        shareButton.setEnabled(has && !busy);
        openButton.setEnabled(!busy);
        saveButton.setAlpha(has && !busy ? 1f : .4f);
        shareButton.setAlpha(has && !busy ? 1f : .4f);
        fileLabel.setText(has ? documentName : "PDF");
        pageLabel.setText(has
            ? (pageIndex + 1) + " / " + pageOrder.size()
            : "— / —");
    }

    private void setBusy(boolean value) {
        busy = value;
        saveButton.setText(value ? "Working…" : "Save");
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
            pdfPages.setDocument(renderer, pageOrder);
            emptyView.setVisibility(View.GONE);
            refreshUI();
        } catch (Exception error) {
            toast("Could not open PDF: " + error.getMessage());
        }
    }

    private void closeDocument() {
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

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (pdfPages != null) pdfPages.resetZoom();
    }

    @Override protected void onDestroy() {
        closeDocument();
        if (pdfPages != null) pdfPages.dispose();
        background.shutdown();
        super.onDestroy();
    }
}
