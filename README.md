# Minimal PDF

A minimal offline Android PDF reader and page organizer for personal use. No accounts, ads, trackers, or internet permissions.

## Features

- Automatically match the phone's light/dark theme, including when system appearance changes
- Clean, compact reader interface with an improved empty state and organized page actions
- Open PDF files using Android's document picker or another app's **Open with** action
- Tap a PDF page to hide or reveal the app controls (distraction-free reading)
- Double-tap a page to open a focused overlay; double-tap again or press × to return
- Zoom with two fingers or choose 100%, 125%, 150%, 200%, 300%, or 400% in the zoom menu
- Dark, full-width reader toolbars inspired by modern mobile PDF readers, with borderless line icons
- Floating page scrubber: vertical position tracks the document progress and dragging jumps directly to a page
- Drag directly on the bottom-left document or page label for an in-place, finger-tracking wheel that updates pages live and switches PDFs without blocking the UI thread
- Tap those labels to open the full picker as before; dragging never opens the picker on release
- Swipe left/right to navigate one page in both the continuous reader and focused page mode
- Focus mode retains its overlay and renders the next page in the background with a short cross-slide
- Pages are separated by only ~2dp of vertical spacing
- The document and page picker wheels are centered above the bottom bar and use the same vertical alignment
- The compact page indicator follows fractional scroll progress with frame-rate-independent easing rather than snapping between whole pages
- The compact right-side page indicator animates between positions and fades when the reader controls hide, reappearing briefly as pages change
- Normal continuous scrolling no longer supports zoom; pinch and zoom presets are available in focused single-page mode only
- Import multiple PDFs in one picker action, switch between them, and retain each PDF's in-progress page ordering and reading position across switches
- Fast 165–185 ms hide/show and focused-page transitions that do not resize the reading area
- Borderless, simplified page and document controls
- Scroll vertically through the entire PDF continuously, with pages rendered lazily for memory efficiency
- Pinch to zoom and pan in **focus mode**. Normal continuous reading remains unzoomed.
- **Move to…** reposition the current page anywhere in the PDF
- **Remove** a page from the output, or **Reset** to restore the original order
- **Extract** a 1-based inclusive range such as pages **10–20** into a separate PDF
- **Save** an edited copy, leaving the original PDF unchanged
- **Share** the edited PDF or an extracted range using Android's share sheet

Page operations are lossless with respect to text and vector page graphics: the app imports the actual PDF content instead of rendering pages to images. Note that some advanced PDF structures (bookmarks, document metadata, form fields, signatures, and links to removed pages) may not transfer intact when creating an edited subset. For these documents, keep the original.

## Build

The [Build APK](../../actions/workflows/build-apk.yml) GitHub Actions workflow produces an installable **debug APK** as the `MinimalPDF-APK` artifact. Open the latest successful run in **Actions**, then download the artifact ZIP.

The project uses native Android `PdfRenderer` for viewing and [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache-2.0) for editing pages without rasterization. AndroidX FileProvider securely shares a temporary PDF with other installed apps.

Requires Android 8.0 or newer.

### Version 1.10
- A one-finger left/right swipe changes focused pages at every zoom level. Fast horizontal gestures navigate; slower movements can still pan zoomed content.
- Changing a focused page preserves the selected zoom, rather than resetting to 150% after every swipe.
- Switching between opened PDFs restores each document's previous page and its in-progress page ordering; document binding no longer announces page one before restoring the saved position.

## v1.11 reading workflow

- Reopen recent PDFs after app relaunch, remembering page order, bookmarks and exact within-page reading progress; Android persistable file permissions required for automatic reopen
- Bottom selector labels retain drag-to-switch and drag-to-page navigation; the action bar now contains Search, Bookmarks, Edit and Share rather than duplicate PDF/Page buttons
- Offline search in selectable PDF text, with asynchronous match extraction, page result list and visual match highlights; image-only scans are not OCRed
- Persistent per-document page bookmarks and embedded chapter outline navigation
- Document manager for reordering and closing open PDFs while preserving their respective reading positions; active document and last-read pages are visible
- Optional, throttled haptic feedback for selection changes
- Fixed overlays and near-zero reading padding so hiding the toolbars reveals the complete reading area without content resizing

### Version 1.12 — simpler search and saved pages

- Tap **Find** to open a small search field over the document; PDF pages remain readable and scrollable. Results appear as highlights, with up/down buttons and a compact result counter. Close with × or Back.
- Tap **Save** once to bookmark/unbookmark the current page. The icon fills when saved.
- Tap **Saved** to see a clean list of saved pages and jump directly to one.
- Embedded chapter navigation is a separate option in the More menu and from the Saved dialog, avoiding a combined, crowded list.
- Search stays offline and works for PDFs with a selectable text layer; scanned-image text requires OCR.

### Version 1.13 — branded app icon

- Custom red-and-white book/PDF launcher logo, packaged as a transparent drawable asset.
- Android adaptive launcher icon uses a charcoal background and the book foreground, while a legacy layer-list icon provides a fallback.
- The icon appears in the launcher, app drawer and Android application settings after installing v1.13.

### Version 1.14 — unclipped pages and cleaned-up launcher icon

- The continuous reader clips PDF pages to the unobscured viewport between the visible 59dp header and 103dp bottom toolbar, so text is not hidden under the interface.
- A single tap still hides/shows the controls; the reader gains nearly the whole screen when they are hidden and preserves the scroll anchor when they return.
- Replaced the old near-black adaptive icon background with a full-bleed light blush gradient, keeping the original red book logo clear on launcher shapes and avoiding dark empty borders.
