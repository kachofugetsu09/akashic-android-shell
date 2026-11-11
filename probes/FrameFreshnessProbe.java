package com.akashic.shell.probes;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.SystemClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/** Real Android buffers with controlled producer timestamps; not an SF clock calibration or real action. */
public final class FrameFreshnessProbe {
    private static Class<?> controller;
    private static Object instance;

    private static Object call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = controller.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(instance, values);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = controller.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    /** Seed only this process's display descriptor to exercise the real full-PNG boundary, without a VD. */
    private static byte[] capture() throws Exception {
        set("virtualDisplayId", 4242);
        set("isTrustedVirtualDisplay", true);
        try {
            return (byte[]) call("captureDisplayPng", new Class<?>[]{int.class,
                Class.forName("com.akashic.shell.AkashicAccessibilityService")}, 4242, null);
        } finally {
            set("virtualDisplayId", null);
            set("isTrustedVirtualDisplay", false);
        }
    }

    private static void unavailable(String message) throws Exception {
        long before = SystemClock.uptimeMillis();
        try {
            capture();
            throw new AssertionError(message);
        } catch (InvocationTargetException error) {
            check(error.getCause().getMessage().contains("PHONE_FRAME_NOT_FRESH"), "unexpected capture failure");
            check(SystemClock.uptimeMillis() - before >= 5_000, "production wait was not exercised");
        }
    }

    private static ImageReader reader() throws Exception {
        ImageReader reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2);
        call("setFrameReader", new Class<?>[]{ImageReader.class}, reader);
        return reader;
    }

    private static Image produce(ImageReader reader, int color, long timestamp) {
        ImageWriter writer = ImageWriter.newInstance(reader.getSurface(), 2);
        try {
            Image image = writer.dequeueInputImage();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer bytes = plane.getBuffer();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int offset = y * plane.getRowStride() + x * plane.getPixelStride();
                    bytes.put(offset, (byte) Color.red(color));
                    bytes.put(offset + 1, (byte) Color.green(color));
                    bytes.put(offset + 2, (byte) Color.blue(color));
                    bytes.put(offset + 3, (byte) 255);
                }
            }
            image.setTimestamp(timestamp);
            writer.queueInputImage(image);
            long deadline = SystemClock.uptimeMillis() + 2_000;
            Image received;
            while ((received = reader.acquireLatestImage()) == null) {
                check(SystemClock.uptimeMillis() < deadline, "producer did not deliver");
                SystemClock.sleep(2);
            }
            check(received.getTimestamp() == timestamp, "producer timestamp was changed by queue");
            return received;
        } finally { writer.close(); }
    }

    private static boolean copy(ImageReader reader, Image image) throws Exception {
        try {
            return (Boolean) call("copyFrame", new Class<?>[]{ImageReader.class, Image.class, int.class, int.class},
                reader, image, 16, 16);
        } finally { image.close(); }
    }

    private static byte[] png(ImageReader reader) throws Exception {
        return (byte[]) call("encodeFullPng", new Class<?>[]{ImageReader.class}, reader);
    }

    private static void color(byte[] png, int expected) {
        check(png != null && BitmapFactory.decodeByteArray(png, 0, png.length).getPixel(0, 0) == expected,
            "unexpected PNG color");
    }

    public static void main(String[] args) throws Exception {
        controller = Class.forName("com.akashic.shell.PhoneBridgeController");
        instance = controller.getField("INSTANCE").get(null);
        boolean baseline = args.length == 1 && args[0].equals("baseline");
        ImageReader reader = reader();
        copy(reader, produce(reader, Color.RED, System.nanoTime()));
        color(png(reader), Color.RED);
        long oldTimestamp = System.nanoTime();
        Image queuedOld = produce(reader, Color.YELLOW, oldTimestamp);
        SystemClock.sleep(2);
        Object watermark = baseline ? null : call("advanceFrameWatermark", new Class<?>[]{});
        long consumedAt = System.nanoTime();
        boolean copied = copy(reader, queuedOld);
        System.out.println("queuedA producer=" + oldTimestamp + " consumedAfterWatermark=" + consumedAt);
        if (baseline) {
            check(copied, "baseline copy failed");
            color(png(reader), Color.YELLOW);
            call("releaseVirtualDisplay", new Class<?>[]{});
            System.out.println("REPRODUCED: old producer A copied after submission boundary returned as current PNG");
            return;
        }
        check(!copied && png(reader) == null, "late old A or cached old raw bypassed watermark");
        // Watermark was advanced without requesting PNG; the next observe's full capture still rejects A.
        unavailable("next full capture after non-screenshot watermark returned old raw");
        Object expired = call("waitForFramePng", new Class<?>[]{ImageReader.class, long.class},
            reader, SystemClock.uptimeMillis() + 100);
        check(expired == null && png(reader) == null, "wait timeout reopened old raw");
        copy(reader, produce(reader, Color.BLUE, System.nanoTime()));
        byte[] blue = png(reader);
        color(blue, Color.BLUE);
        check(blue == png(reader), "static observe lost valid same-frame cache");
        check(blue == capture(), "static full capture did not reuse valid same-frame cache");
        Object refused = call("advanceFrameWatermark", new Class<?>[]{});
        check(png(reader) == null, "new watermark did not invalidate existing full PNG");
        call("restoreFrameWatermark", new Class<?>[]{refused.getClass()}, refused);
        check(blue == png(reader), "known no-effect rejection did not restore static screenshot");
        Object unknown = call("advanceFrameWatermark", new Class<?>[]{});
        Class<?> screenshot = Class.forName("com.akashic.shell.CapturedScreenshot");
        SystemClock.sleep(2);
        Object fresh = screenshot.getConstructor(byte[].class, long.class).newInstance(blue, SystemClock.uptimeMillis());
        check(call("acceptAccessibilityScreenshot", new Class<?>[]{ImageReader.class, screenshot}, reader, fresh) == blue,
            "uptime-domain guard rejected a valid timestamp fixture");
        check(png(reader) == null, "fresh a11y timestamp fixture incorrectly upgraded old raw");
        unavailable("disabling a11y after a fresh fixture resurrected old raw");
        Object stale = screenshot.getConstructor(byte[].class, long.class).newInstance(blue, 1L);
        check(call("acceptAccessibilityScreenshot", new Class<?>[]{ImageReader.class, screenshot}, reader, stale) == null,
            "old uptime timestamp bypassed guard");
        Object wrongUnit = screenshot.getConstructor(byte[].class, long.class).newInstance(blue, System.nanoTime());
        try {
            call("acceptAccessibilityScreenshot", new Class<?>[]{ImageReader.class, screenshot}, reader, wrongUnit);
            throw new AssertionError("nanoseconds were accepted as uptime milliseconds");
        } catch (InvocationTargetException error) {
            check(error.getCause().getMessage().contains("PHONE_SCREENSHOT_CLOCK"), "unexpected unit failure");
        }
        call("releaseVirtualDisplay", new Class<?>[]{});
        reader = reader();
        copy(reader, produce(reader, Color.GREEN, System.nanoTime()));
        color(png(reader), Color.GREEN);
        try {
            copy(reader, produce(reader, Color.RED, 0));
            throw new AssertionError("invalid clock was accepted");
        } catch (InvocationTargetException error) {
            check(error.getCause().getMessage().contains("PHONE_FRAME_CLOCK"), "unexpected clock failure");
        }
        try {
            png(reader);
            throw new AssertionError("clock failure was hidden by cached PNG");
        } catch (InvocationTargetException error) {
            check(error.getCause().getMessage().contains("PHONE_FRAME_CLOCK"), "unexpected cached clock failure");
        }
        call("releaseVirtualDisplay", new Class<?>[]{});
        reader = reader();
        copy(reader, produce(reader, Color.GREEN, System.nanoTime()));
        color(png(reader), Color.GREEN);
        call("releaseVirtualDisplay", new Class<?>[]{});
        System.out.println("PASS producer fence: old A/cache, timeout, fresh B/static, known failure restore, unknown retained, uptime guard fixture, clock failure/release recovery");
    }
}
