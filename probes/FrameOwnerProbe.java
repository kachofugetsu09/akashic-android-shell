package com.akashic.shell.probes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.content.Context;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Separate app_process probe: real Android buffers, no installed app state or VD changes. */
public final class FrameOwnerProbe {
    private static Class<?> controller;
    private static Object instance;
    private static Class<?> pair;

    private static Object get(String name) throws Exception {
        Field field = controller.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = controller.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Object call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = controller.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(instance, values);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void startReader(ImageReader reader) throws Exception {
        call("setFrameReader", new Class<?>[]{ImageReader.class}, reader);
    }

    private static Image produce(ImageReader reader, int color) {
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
            writer.queueInputImage(image);
            long deadline = SystemClock.uptimeMillis() + 2_000;
            Image received;
            while ((received = reader.acquireLatestImage()) == null) {
                check(SystemClock.uptimeMillis() < deadline, "real producer did not deliver a buffer");
                SystemClock.sleep(2);
            }
            return received;
        } finally {
            writer.close();
        }
    }

    private static Object viewField(Object view, String name) throws Exception {
        Field field = view.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }

    /** Hold an unattached View's real post queue, then drain it on a real UI Looper. */
    private static void checkUiQueue(boolean baseline) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Class<?> function = Class.forName("kotlin.jvm.functions.Function0");
        Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Object click = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
            (proxy, method, values) -> unit);
        Object readState = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
            (proxy, method, values) -> call("currentPipState", new Class<?>[]{}));
        Class<?> viewClass = Class.forName("com.akashic.shell.AgentPipView");
        Class<?> modeClass = Class.forName("com.akashic.shell.PipMode");
        Object running = modeClass.getField("RUNNING").get(null);
        Object hidden = modeClass.getField("HIDDEN").get(null);
        Object doneMode = modeClass.getField("DONE").get(null);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes), "test PNG failed");
        bitmap.recycle();
        byte[] png = bytes.toByteArray();
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Thread(() -> {
            Looper.prepare();
            Handler handler = new Handler(Looper.myLooper());
            try {
                Object view = viewClass.getConstructor(Context.class, function).newInstance(context, click);
                Method getQueue = android.view.View.class.getDeclaredMethod("getRunQueue");
                getQueue.setAccessible(true);
                Object queue = getQueue.invoke(view);
                Method drain = queue.getClass().getDeclaredMethod("executeActions", Handler.class);
                drain.setAccessible(true);
                Method update = baseline
                    ? viewClass.getMethod("updateState", modeClass, String.class, byte[].class)
                    : viewClass.getMethod("updateState", function);
                call("updatePip", new Class<?>[]{modeClass, String.class, byte[].class}, running, "old", png);
                for (int i = 0; i < 2; i++) {
                    if (baseline) update.invoke(view, running, "old", png);
                    else update.invoke(view, readState);
                }
                call("updatePip", new Class<?>[]{modeClass, String.class, byte[].class}, hidden, "stopped", null);
                drain.invoke(queue, handler);
                handler.post(() -> {
                    try {
                        if (baseline) {
                            check(viewField(view, "mode") == running && viewField(view, "frame") != null,
                                "baseline no longer reproduces queued retired RUNNING");
                            System.out.println("REPRODUCED native View.post: queued old RUNNING rendered after HIDDEN");
                        } else {
                            check(viewField(view, "mode") == hidden && viewField(view, "frame") == null,
                                "queued old RUNNING restored retired UI");
                            call("updatePip", new Class<?>[]{modeClass, String.class, byte[].class}, doneMode, "done", png);
                            update.invoke(view, readState);
                            drain.invoke(queue, handler);
                            handler.post(() -> {
                                try {
                                    check(viewField(view, "mode") == doneMode && viewField(view, "frame") != null,
                                        "normal DONE preview was lost");
                                    call("updatePip", new Class<?>[]{modeClass, String.class, byte[].class}, running, "new-reader", null);
                                    update.invoke(view, readState);
                                    drain.invoke(queue, handler);
                                    handler.post(() -> {
                                        try {
                                            check(viewField(view, "mode") == running && viewField(view, "frame") == null,
                                                "new reader without PNG retained old UI bitmap");
                                            System.out.println("PASS native View.post: held old queue, DONE, new reader without PNG");
                                        } catch (Throwable error) { failure.set(error); }
                                        finally { Looper.myLooper().quit(); }
                                    });
                                } catch (Throwable error) { failure.set(error); Looper.myLooper().quit(); }
                            });
                        }
                    } catch (Throwable error) { failure.set(error); }
                    finally { if (baseline || failure.get() != null) Looper.myLooper().quit(); }
                });
                Looper.loop();
            } catch (Throwable error) { failure.set(error); }
            finally { complete.countDown(); }
        }, "frame-ui-probe").start();
        check(complete.await(8, TimeUnit.SECONDS), "native UI queue probe timed out");
        if (failure.get() != null) throw new AssertionError("native UI queue probe failed", failure.get());
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        controller = Class.forName("com.akashic.shell.PhoneBridgeController");
        instance = controller.getField("INSTANCE").get(null);
        pair = Class.forName("kotlin.Pair");
        if (args.length == 1 && args[0].equals("baseline-ui")) {
            checkUiQueue(true);
            return;
        }
        if (args.length == 1 && args[0].equals("baseline")) {
            byte[] preview = new byte[]{1, 2, 3};
            set("latestPreviewPng", preview);
            call("releaseVirtualDisplay", new Class<?>[]{});
            check(get("latestPreviewPng") == preview, "baseline no longer reproduces retained preview");
            System.out.println("REPRODUCED baseline: release retained retired preview");
            return;
        }

        ImageReader old = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2);
        startReader(old);
        Image image = produce(old, Color.RED);
        check((Boolean) call("copyFrame", new Class<?>[]{ImageReader.class, Image.class, int.class, int.class},
            old, image, 16, 16), "active reader raw copy failed");
        Object preview = call("encodePreview", new Class<?>[]{ImageReader.class}, old);
        call("publishPreview", new Class<?>[]{ImageReader.class, pair}, old, preview);
        byte[] png = (byte[]) call("encodeFullPng", new Class<?>[]{ImageReader.class}, old);
        check(BitmapFactory.decodeByteArray(png, 0, png.length).getPixel(0, 0) == Color.RED,
            "real RGBA buffer did not become a red PNG");
        check(png == call("encodeFullPng", new Class<?>[]{ImageReader.class}, old),
            "static observe did not reuse valid same-reader PNG");
        byte[] finalPreview = (byte[]) call("releaseVirtualDisplay", new Class<?>[]{});
        check(finalPreview != null && get("latestPreviewPng") == null, "release did not separate terminal preview");
        check(get("frameBitmap") == null && get("encodedPng") == null, "release retained active image cache");
        call("publishPreview", new Class<?>[]{ImageReader.class, pair}, old, preview);
        check(get("latestPreviewPng") == null, "retired encode completion republished preview");
        check(!(Boolean) call("copyFrame", new Class<?>[]{ImageReader.class, Image.class, int.class, int.class},
            old, image, 16, 16), "retired reader touched a closed Image");
        image.close();

        Class<?> mode = Class.forName("com.akashic.shell.PipMode");
        Object done = mode.getField("DONE").get(null);
        call("updatePip", new Class<?>[]{mode, String.class, byte[].class}, done, "done", finalPreview);
        check(get("latestPreviewPng") == finalPreview, "DONE lost explicit terminal preview");
        ImageReader next = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2);
        startReader(next);
        check(get("latestPreviewPng") == null, "new reader inherited terminal preview");
        check(call("encodeFullPng", new Class<?>[]{ImageReader.class}, old) == null,
            "retired reader supplied a full screenshot");
        call("publishPreview", new Class<?>[]{ImageReader.class, pair}, old, preview);
        check(get("latestPreviewPng") == null, "old publisher overwrote new reader cache");
        call("releaseVirtualDisplay", new Class<?>[]{});
        checkUiQueue(false);
        System.out.println("PASS real ImageReader/ImageWriter: raw/PNG, same-frame reuse, release, old-copy/encode rejection, DONE/new-reader separation");
    }
}
