package com.personal.minimalpdf;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Tests two-finger zoom through Activity.dispatchTouchEvent, not focus mode. */
@RunWith(AndroidJUnit4.class)
public class ReaderGestureTest {
    private static Object field(Object source, String name) throws Exception {
        Field f = source.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(source);
    }

    private static File makeTestPdf(MainActivity activity) throws Exception {
        File file = new File(activity.getCacheDir(), "gesture-smoke.pdf");
        PdfDocument pdf = new PdfDocument();
        PdfDocument.PageInfo info =
            new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = pdf.startPage(info);
        page.getCanvas().drawColor(Color.WHITE);
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.BLACK);
        title.setTextSize(36);
        page.getCanvas().drawText("Pinch zoom test", 50, 170, title);
        pdf.finishPage(page);
        try (FileOutputStream out = new FileOutputStream(file)) {
            pdf.writeTo(out);
        } finally {
            pdf.close();
        }
        return file;
    }

    private static void sendOne(MainActivity activity, long down, long time,
                                int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, time, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        activity.dispatchTouchEvent(e);
        e.recycle();
    }

    private static void sendTwo(MainActivity activity, long down, long time,
                                int action, float centerX, float centerY, float halfWidth) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] positions = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            positions[i] = new MotionEvent.PointerCoords();
            positions[i].x = centerX + (i == 0 ? -halfWidth : halfWidth);
            positions[i].y = centerY;
            positions[i].pressure = 1f;
            positions[i].size = .8f;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, 2,
            properties, positions, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        activity.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test public void normalReaderPinchChangesVisiblePageScale() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                 ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    File pdf = makeTestPdf(activity);
                    Method add = MainActivity.class.getDeclaredMethod("addDocuments", List.class);
                    add.setAccessible(true);
                    add.invoke(activity, Collections.singletonList(Uri.fromFile(pdf)));

                    HorizontalScrollView pan =
                        (HorizontalScrollView) field(activity, "normalPan");
                    PdfPageList pages = (PdfPageList) field(activity, "pdfPages");
                    assertNotNull(pan);
                    assertNotNull(pages);

                    // The Activity receives coordinates in the same window as pan.
                    int[] origin = new int[2];
                    pan.getLocationInWindow(origin);
                    float x = origin[0] + pan.getWidth() * .45f;
                    float y = origin[1] + pan.getHeight() * .48f;
                    long down = SystemClock.uptimeMillis();

                    sendOne(activity, down, down, MotionEvent.ACTION_DOWN, x - 25, y);
                    sendTwo(activity, down, down + 15,
                        MotionEvent.ACTION_POINTER_DOWN |
                        (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, 25);
                    sendTwo(activity, down, down + 45, MotionEvent.ACTION_MOVE, x, y, 45);
                    sendTwo(activity, down, down + 70, MotionEvent.ACTION_MOVE, x, y, 65);
                    sendTwo(activity, down, down + 95, MotionEvent.ACTION_MOVE, x, y, 100);
                    sendTwo(activity, down, down + 120,
                        MotionEvent.ACTION_POINTER_UP |
                        (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, 100);
                    sendOne(activity, down, down + 140, MotionEvent.ACTION_UP, x - 100, y);
                    assertTrue("Two-finger zoom was not received by the normal reader",
                        pages.getZoom() > 1.15f);
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            if (error.get() != null) throw new AssertionError(error.get());
            SystemClock.sleep(250);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                try {
                    HorizontalScrollView pan =
                        (HorizontalScrollView) field(activity, "normalPan");
                    PdfPageList pages = (PdfPageList) field(activity, "pdfPages");
                    assertTrue("Rendered PDF page did not grow after pinch",
                        pages.getWidth() > pan.getWidth());
                } catch (Exception e) { throw new AssertionError(e); }
            });
        }
    }
}
