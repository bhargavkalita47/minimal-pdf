package com.personal.minimalpdf;

import android.content.ContentResolver;
import android.graphics.RectF;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Offline PDF text search and built-in outline indexing. No OCR/network calls. */
public final class DocumentIndex {
    public static final class Match {
        public final int originalPage;
        public final String snippet;
        Match(int page, String snippet) {
            this.originalPage = page;
            this.snippet = snippet;
        }
    }

    public static final class Chapter {
        public final int originalPage;
        public final String title;
        Chapter(int page, String title) {
            originalPage = page;
            this.title = title;
        }
    }

    public static final class SearchResult {
        public final List<Match> matches = new ArrayList<>();
        public final Map<Integer, List<RectF>> highlights = new HashMap<>();
        public int pagesScanned;
        public boolean truncated;
    }

    private DocumentIndex() { }

    public static SearchResult search(ContentResolver resolver, Uri uri,
                                      String query, BooleanSupplier cancelled)
                                      throws IOException {
        SearchResult results = new SearchResult();
        String term = query.trim().toLowerCase(Locale.ROOT);
        if (term.length() < 2) return results;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Document cannot be opened");
            try (PDDocument doc = PDDocument.load(stream)) {
                TextScanner stripper = new TextScanner(term);
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    if (cancelled.getAsBoolean() || results.matches.size() >= 200) {
                        results.truncated = results.matches.size() >= 200;
                        break;
                    }
                    PDPage page = doc.getPage(i);
                    stripper.startPage(i, page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    stripper.getText(doc);
                    results.pagesScanned++;
                    if (!stripper.matches.isEmpty()) {
                        results.matches.addAll(stripper.matches);
                        results.highlights.put(i, new ArrayList<>(stripper.rectangles));
                    }
                }
            }
        }
        return results;
    }

    private static final class TextScanner extends PDFTextStripper {
        private final String query;
        private final List<Match> matches = new ArrayList<>();
        private final List<RectF> rectangles = new ArrayList<>();
        private int originalPage;
        private float pageWidth, pageHeight;

        TextScanner(String query) throws IOException {
            this.query = query;
            setSortByPosition(true);
        }

        void startPage(int page, float width, float height) {
            originalPage = page;
            pageWidth = Math.max(1, width);
            pageHeight = Math.max(1, height);
            matches.clear();
            rectangles.clear();
        }

        @Override protected void writeString(String text, List<TextPosition> positions)
            throws IOException {
            super.writeString(text, positions);
            if (positions == null || positions.isEmpty() || matches.size() >= 25) return;
            StringBuilder glyphText = new StringBuilder();
            ArrayList<TextPosition> characters = new ArrayList<>();
            for (TextPosition position : positions) {
                String value = position.getUnicode();
                if (value == null || value.isEmpty()) continue;
                for (int i = 0; i < value.length(); i++) {
                    glyphText.append(value.charAt(i));
                    characters.add(position);
                }
            }
            String source = glyphText.toString().toLowerCase(Locale.ROOT);
            int start = 0;
            while ((start = source.indexOf(query, start)) >= 0) {
                int end = Math.min(start + query.length(), characters.size());
                if (end <= start) break;
                float left = Float.MAX_VALUE, top = Float.MAX_VALUE;
                float right = -1, bottom = -1;
                for (int i = start; i < end; i++) {
                    TextPosition position = characters.get(i);
                    float x = position.getXDirAdj();
                    float y = position.getYDirAdj();
                    left = Math.min(left, x);
                    right = Math.max(right, x + position.getWidthDirAdj());
                    top = Math.min(top, y - position.getHeightDir());
                    bottom = Math.max(bottom, y + position.getHeightDir() * .18f);
                }
                if (right > left && bottom > top) {
                    rectangles.add(new RectF(
                        clamp(left / pageWidth), clamp(top / pageHeight),
                        clamp(right / pageWidth), clamp(bottom / pageHeight)));
                }
                String compact = text == null ? glyphText.toString() : text;
                compact = compact.replaceAll("\\s+", " ").trim();
                if (compact.length() > 105) compact = compact.substring(0, 105) + "…";
                matches.add(new Match(originalPage, compact));
                if (matches.size() >= 25) break;
                start = end;
            }
        }
        private static float clamp(float n) {
            return Math.max(0f, Math.min(1f, n));
        }
    }

    public static List<Chapter> chapters(ContentResolver resolver, Uri uri)
        throws IOException {
        List<Chapter> output = new ArrayList<>();
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) return output;
            try (PDDocument document = PDDocument.load(stream)) {
                PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
                if (outline != null) {
                    appendOutline(document, outline.getFirstChild(), output, 0);
                }
            }
        }
        return output;
    }

    private static String indent(int level) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(5, level); i++) text.append("  ");
        return text.toString();
    }

    private static void appendOutline(PDDocument document, PDOutlineItem item,
                                      List<Chapter> result, int depth) {
        int safety = 0;
        while (item != null && result.size() < 500 && safety++ < 500) {
            try {
                PDPage page = item.findDestinationPage(document);
                int index = page == null ? -1 : document.getPages().indexOf(page);
                if (index >= 0) {
                    String title = item.getTitle();
                    result.add(new Chapter(index,
                        indent(depth) + (title == null ? "Chapter" : title.trim())));
                }
            } catch (IOException ignored) { }
            if (depth < 7 && item.getFirstChild() != null) {
                appendOutline(document, item.getFirstChild(), result, depth + 1);
            }
            item = item.getNextSibling();
        }
    }
}
