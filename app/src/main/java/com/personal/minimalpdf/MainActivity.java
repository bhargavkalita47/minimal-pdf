package com.personal.minimalpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int OPEN_FILE = 101;
    private static final int SAVE_FILE = 102;
    private static final int INK = Color.rgb(27, 30, 36);
    private static final int SOFT = Color.rgb(242, 243, 245);
    private static final int ACCENT = Color.rgb(32, 95, 226);

    private Uri sourceUri;
    private ParcelFileDescriptor sourceFd;
    private PdfRenderer renderer;
    private int pageIndex = 0;
    private final Set<Integer> removed = new HashSet<>();
    private final Map<Integer, ArrayList<PdfCanvas.Mark>> marks = new HashMap<>();
    private PdfCanvas pdfCanvas;
    private TextView fileLabel, pageLabel;
    private Button viewButton, penButton, textButton, saveButton;
    private String documentName = "document.pdf";
    private String pendingText = "";
    private PdfCanvas.Tool tool = PdfCanvas.Tool.VIEW;
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildInterface();
        Uri incoming = getIntent() != null ? getIntent().getData() : null;
        if (incoming != null) openDocument(incoming);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) openDocument(intent.getData());
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int background, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(background);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private Button button(String title, View.OnClickListener callback) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(INK);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(shape(SOFT, 10));
        b.setOnClickListener(callback);
        return b;
    }

    private void addButton(LinearLayout row, Button b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40));
        p.setMargins(0, 0, dp(7), 0);
        row.addView(b, p);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(9), dp(12));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(66)));

        Button open = button("Open", v -> chooseDocument());
        addButton(top, open);

        fileLabel = new TextView(this);
        fileLabel.setText("PDF");
        fileLabel.setTextColor(INK);
        fileLabel.setTextSize(15);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        fileLabel.setGravity(Gravity.CENTER_VERTICAL);
        fileLabel.setTypeface(null, 1);
        top.addView(fileLabel, new LinearLayout.LayoutParams(0, dp(40), 1));

        saveButton = button("Save copy", v -> chooseSaveDestination());
        saveButton.setBackground(shape(INK, 10));
        saveButton.setTextColor(Color.WHITE);
        top.addView(saveButton, new LinearLayout.LayoutParams(-2, dp(40)));

        View border = new View(this);
        border.setBackgroundColor(Color.rgb(231, 232, 235));
        root.addView(border, new LinearLayout.LayoutParams(-1, dp(1)));

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(227, 229, 234));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        pdfCanvas = new PdfCanvas(this, new PdfCanvas.Actions() {
            @Override public ArrayList<PdfCanvas.Mark> currentMarks() { return marksForPage(pageIndex); }
            @Override public void addMark(PdfCanvas.Mark mark) {
                marksForPage(pageIndex).add(mark);
                pdfCanvas.invalidate();
            }
            @Override public void onTextTap(float x, float y) {
                if (pendingText.trim().isEmpty()) return;
                marksForPage(pageIndex).add(PdfCanvas.Mark.text(x, y, pendingText));
                pendingText = "";
                setTool(PdfCanvas.Tool.VIEW);
                pdfCanvas.invalidate();
            }
        });
        content.addView(pdfCanvas, new FrameLayout.LayoutParams(-1, -1));

        TextView empty = new TextView(this);
        empty.setText("Open a PDF to begin");
        empty.setTextSize(17);
        empty.setTextColor(Color.DKGRAY);
        empty.setGravity(Gravity.CENTER);
        empty.setTag("empty");
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        pdfCanvas.setOnTouchListener((v, event) -> false);
        pdfCanvas.setEmptyLabel(empty);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setBackgroundColor(Color.WHITE);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, dp(67)));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(14), dp(10), dp(7), dp(12));
        scroller.addView(tools, new HorizontalScrollView.LayoutParams(-2, -1));

        addButton(tools, button("‹", v -> goRelative(-1)));
        pageLabel = new TextView(this);
        pageLabel.setText("— / —");
        pageLabel.setTextColor(INK);
        pageLabel.setTextSize(13);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setMinWidth(dp(62));
        tools.addView(pageLabel, new LinearLayout.LayoutParams(-2, dp(40)));
        addButton(tools, button("›", v -> goRelative(1)));
        addButton(tools, viewButton = button("View", v -> setTool(PdfCanvas.Tool.VIEW)));
        addButton(tools, penButton = button("Draw", v -> setTool(PdfCanvas.Tool.PEN)));
        addButton(tools, textButton = button("Text", v -> promptText()));
        addButton(tools, button("Undo", v -> undoMark()));
        addButton(tools, button("Remove page", v -> confirmDeletePage()));
        setTool(PdfCanvas.Tool.VIEW);
        refreshUI();
    }

    private void refreshUI() {
        boolean hasPdf = renderer != null;
        saveButton.setEnabled(hasPdf);
        saveButton.setAlpha(hasPdf ? 1f : 0.35f);
        if (!hasPdf) {
            pageLabel.setText("— / —");
            fileLabel.setText("PDF");
            return;
        }
        int current = 0;
        for (int i = 0; i <= pageIndex; i++) if (!removed.contains(i)) current++;
        pageLabel.setText(current + " / " + (renderer.getPageCount() - removed.size()));
        fileLabel.setText(documentName);
    }

    private void setTool(PdfCanvas.Tool selected) {
        tool = selected;
        if (pdfCanvas != null) pdfCanvas.setTool(selected);
        if (viewButton != null) {
            styleTool(viewButton, selected == PdfCanvas.Tool.VIEW);
            styleTool(penButton, selected == PdfCanvas.Tool.PEN);
            styleTool(textButton, selected == PdfCanvas.Tool.TEXT);
        }
    }

    private void styleTool(Button b, boolean selected) {
        b.setBackground(shape(selected ? ACCENT : SOFT, 10));
        b.setTextColor(selected ? Color.WHITE : INK);
    }

    private void chooseDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, OPEN_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == OPEN_FILE) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            openDocument(data.getData());
        } else if (requestCode == SAVE_FILE) {
            exportCopy(data.getData());
        }
    }

    private String findDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "document.pdf";
    }

    private void openDocument(Uri uri) {
        try {
            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new IllegalStateException("Unable to read file");
            PdfRenderer newRenderer;
            try { newRenderer = new PdfRenderer(fd); }
            catch (Exception e) { fd.close(); throw e; }
            closeDocument();
            sourceFd = fd;
            renderer = newRenderer;
            sourceUri = uri;
            pageIndex = 0;
            documentName = findDisplayName(uri);
            removed.clear();
            marks.clear();
            pendingText = "";
            setTool(PdfCanvas.Tool.VIEW);
            renderCurrentPage();
        } catch (Exception e) {
            toast("Can't open PDF: " + e.getMessage());
        }
    }

    private void closeDocument() {
        pdfCanvas.setPage(null);
        if (renderer != null) { try { renderer.close(); } catch (Exception ignored) { } renderer = null; }
        if (sourceFd != null) { try { sourceFd.close(); } catch (Exception ignored) { } sourceFd = null; }
    }

    private void renderCurrentPage() {
        if (renderer == null) return;
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int w = page.getWidth(), h = page.getHeight();
            float scale = Math.min(3f, 2400f / Math.max(w, h));
            int bw = Math.max(1, Math.round(w * scale));
            int bh = Math.max(1, Math.round(h * scale));
            Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pdfCanvas.setPage(bitmap);
            refreshUI();
        } catch (Exception e) {
            toast("Can't display page: " + e.getMessage());
        }
    }

    private ArrayList<PdfCanvas.Mark> marksForPage(int i) {
        ArrayList<PdfCanvas.Mark> list = marks.get(i);
        if (list == null) { list = new ArrayList<>(); marks.put(i, list); }
        return list;
    }

    private void goRelative(int delta) {
        if (renderer == null) return;
        int candidate = pageIndex + delta;
        while (candidate >= 0 && candidate < renderer.getPageCount() && removed.contains(candidate)) candidate += delta;
        if (candidate < 0 || candidate >= renderer.getPageCount()) return;
        pageIndex = candidate;
        renderCurrentPage();
    }

    private void promptText() {
        if (renderer == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setTextSize(16);
        input.setHint("Type your note");
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(20), dp(4), dp(20), 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Add text")
            .setView(wrapper)
            .setPositiveButton("Place on page", (dialog, which) -> {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    pendingText = text;
                    setTool(PdfCanvas.Tool.TEXT);
                    toast("Tap where you want the text");
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void undoMark() {
        if (renderer == null) return;
        ArrayList<PdfCanvas.Mark> list = marksForPage(pageIndex);
        if (!list.isEmpty()) { list.remove(list.size() - 1); pdfCanvas.invalidate(); }
    }

    private void confirmDeletePage() {
        if (renderer == null) return;
        if (renderer.getPageCount() - removed.size() <= 1) { toast("Keep at least one page"); return; }
        new AlertDialog.Builder(this).setMessage("Remove this page from the saved copy?")
            .setPositiveButton("Remove", (d, w) -> {
                int old = pageIndex;
                removed.add(old);
                int next = old + 1;
                while (next < renderer.getPageCount() && removed.contains(next)) next++;
                if (next >= renderer.getPageCount()) {
                    next = old - 1;
                    while (next >= 0 && removed.contains(next)) next--;
                }
                pageIndex = next;
                renderCurrentPage();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private boolean edited() {
        if (!removed.isEmpty()) return true;
        for (List<PdfCanvas.Mark> list : marks.values()) if (!list.isEmpty()) return true;
        return false;
    }

    private void chooseSaveDestination() {
        if (renderer == null) return;
        String name = documentName;
        int dot = name.toLowerCase().lastIndexOf(".pdf");
        if (dot >= 0) name = name.substring(0, dot);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, name + "-edited.pdf");
        startActivityForResult(i, SAVE_FILE);
    }

    private void exportCopy(Uri dest) {
        if (renderer == null || sourceUri == null) return;
        final Uri original = sourceUri;
        final boolean needFlatten = edited();
        final Set<Integer> skipped = new HashSet<>(removed);
        final Map<Integer, ArrayList<PdfCanvas.Mark>> notes = new HashMap<>();
        for (Map.Entry<Integer, ArrayList<PdfCanvas.Mark>> e : marks.entrySet()) {
            ArrayList<PdfCanvas.Mark> cloned = new ArrayList<>();
            for (PdfCanvas.Mark m : e.getValue()) cloned.add(m.copy());
            notes.put(e.getKey(), cloned);
        }
        saveButton.setEnabled(false);
        saveButton.setText("Saving...");
        background.execute(() -> {
            String error = null;
            try {
                if (needFlatten) writeFlattened(original, dest, skipped, notes);
                else copyExact(original, dest);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String finishedError = error;
            runOnUiThread(() -> {
                saveButton.setEnabled(true);
                saveButton.setText("Save copy");
                if (finishedError == null) toast("Saved PDF copy");
                else new AlertDialog.Builder(this).setTitle("Could not save").setMessage(finishedError).setPositiveButton("OK", null).show();
            });
        });
    }

    private void copyExact(Uri from, Uri to) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(from);
             OutputStream out = getContentResolver().openOutputStream(to, "wt")) {
            if (in == null || out == null) throw new Exception("Could not access selected files");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
        }
    }

    private void writeFlattened(Uri from, Uri to, Set<Integer> skipped,
                                Map<Integer, ArrayList<PdfCanvas.Mark>> annotations) throws Exception {
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(from, "r");
             PdfRenderer reader = new PdfRenderer(fd)) {
            PdfDocument output = new PdfDocument();
            try {
            int outIndex = 0;
            for (int i = 0; i < reader.getPageCount(); i++) {
                if (skipped.contains(i)) continue;
                try (PdfRenderer.Page source = reader.openPage(i)) {
                    int w = source.getWidth();
                    int h = source.getHeight();
                    float scale = Math.min(2.5f, 3000f / Math.max(w, h));
                    int bw = Math.max(1, Math.round(w * scale));
                    int bh = Math.max(1, Math.round(h * scale));
                    Bitmap bits = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    try {
                        bits.eraseColor(Color.WHITE);
                        source.render(bits, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, outIndex + 1).create();
                        PdfDocument.Page page = output.startPage(info);
                        Canvas paper = page.getCanvas();
                        paper.drawColor(Color.WHITE);
                        Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                        paper.drawBitmap(bits, null, new RectF(0, 0, w, h), bitmapPaint);
                        ArrayList<PdfCanvas.Mark> pageNotes = annotations.get(i);
                        if (pageNotes != null) for (PdfCanvas.Mark m : pageNotes) m.draw(paper, new RectF(0, 0, w, h));
                        output.finishPage(page);
                        outIndex++;
                    } finally {
                        bits.recycle();
                    }
                }
            }
            try (OutputStream stream = getContentResolver().openOutputStream(to, "wt")) {
                if (stream == null) throw new Exception("Destination unavailable");
                output.writeTo(stream);
                stream.flush();
            }
            } finally { output.close(); }
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        pdfCanvas.resetTransform();
    }

    @Override protected void onDestroy() {
        closeDocument();
        background.shutdown();
        super.onDestroy();
    }
}
