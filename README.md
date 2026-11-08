# Minimal PDF

A minimal offline Android PDF reader and page organizer for personal use. No accounts, ads, trackers, or internet permissions.

## Features

- Open PDF files using Android's document picker or another app's **Open with** action
- Scroll vertically through the entire PDF continuously, with pages rendered lazily for memory efficiency
- Pinch to zoom (and pan horizontally when zoomed in), or jump using **Pages**
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
