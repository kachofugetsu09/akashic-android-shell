package com.akashic.shell.probes;

import android.graphics.PixelFormat;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.io.File;

/** Actual SF-owned VD calibration; run only in the device owner's approved display window. */
public final class FrameClockProbe {
    private static Object call(Class<?> type, Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass one new host-verified gate filepath");
        File gate = new File(args[0]);
        if (gate.exists()) throw new AssertionError("Gate must not exist before this attempt");
        if (Process.myUid() != 2000) throw new AssertionError("UID2000 required");
        Looper.prepareMainLooper();
        Class<?> serviceType = Class.forName("com.akashic.shell.AkashicShellService");
        Object service = serviceType.getConstructor(int.class).newInstance(2000);
        Class<?> controller = Class.forName("com.akashic.shell.PhoneBridgeController");
        Object instance = controller.getField("INSTANCE").get(null);
        ImageReader reader = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 3);
        long born = System.nanoTime();
        call(controller, instance, "setFrameReader", new Class<?>[]{ImageReader.class}, reader);
        int displayId = -1;
        int samples = 0;
        long previous = 0;
        try {
            displayId = (Integer) serviceType.getMethod("createDisplay", Surface.class, int.class, int.class, int.class)
                .invoke(service, reader.getSurface(), 320, 480, 160);
            if (displayId <= 0) throw new AssertionError("real isolated VD creation failed");
            if (!(Boolean) serviceType.getMethod("keepAwake", int.class).invoke(service, displayId)) {
                throw new AssertionError("owned display did not wake independently");
            }
            Intent intent = new Intent().setComponent(new ComponentName(
                "dev.akashic.phonelimit", "dev.akashic.phonelimit.ScrollActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            int code = (Integer) serviceType.getMethod("startIntent", int.class, Intent.class).invoke(service, displayId, intent);
            System.out.println("SF_APP_START display=" + displayId + " code=" + code + " target=" + intent.getComponent());
            if (code != 0) throw new AssertionError("own test Activity was not accepted: " + code);
            System.out.println("SF_APP_GATE display=" + displayId + " pid=" + Process.myPid() + " file=" + gate);
            long gateDeadline = SystemClock.uptimeMillis() + 30_000;
            while (!gate.isFile()) {
                if (SystemClock.uptimeMillis() >= gateDeadline) {
                    throw new AssertionError("APP_DISPLAY_UNCONFIRMED: host did not verify task/provider on VD " + displayId);
                }
                SystemClock.sleep(20);
            }
            System.out.println("SF_APP_VERIFIED display=" + displayId + " host_gate=" + gate);
            Field displays = serviceType.getDeclaredField("displays");
            displays.setAccessible(true);
            VirtualDisplay owned = (VirtualDisplay) ((Map<?, ?>) displays.get(service)).get(displayId);
            if (owned == null) throw new AssertionError("new VD missing from this service's own map");
            System.out.println("SF_SOURCE display=" + displayId + " flags=" + owned.getDisplay().getFlags() + " born_ns=" + born);
            for (int phase = 0; phase < 3; phase++) {
                // Resizing only our VD requests real SF recomposition; no ImageWriter or app input.
                if (phase > 0) owned.resize(320 + phase * 2, 480, 160);
                long deadline = SystemClock.uptimeMillis() + 4_000;
                boolean received = false;
                while (SystemClock.uptimeMillis() < deadline) {
                    long beforeAcquire = System.nanoTime();
                    Image image = reader.acquireLatestImage();
                    long consumer = System.nanoTime();
                    if (image == null) { SystemClock.sleep(5); continue; }
                    try {
                        long producer = image.getTimestamp();
                        System.out.println("SF_FRAME phase=" + phase + " display=" + displayId + " born_ns=" + born
                            + " producer_ns=" + producer + " before_acquire_ns=" + beforeAcquire
                            + " consumer_ns=" + consumer + " age_ns=" + (consumer - producer)
                            + " width=" + image.getWidth() + " height=" + image.getHeight());
                        if (producer < born || producer > consumer || producer < previous) {
                            throw new AssertionError("SF timestamp does not match the owned reader's MONOTONIC domain");
                        }
                        if (!(Boolean) call(controller, instance, "copyFrame", new Class<?>[]{ImageReader.class,
                            Image.class, int.class, int.class}, reader, image, image.getWidth(), image.getHeight())) {
                            throw new AssertionError("controller rejected the real SF sample");
                        }
                        previous = producer;
                        samples++;
                        received = true;
                        break;
                    } finally { image.close(); }
                }
                if (!received) throw new AssertionError("SF produced no frame for phase " + phase);
            }
            System.out.println("PASS owned SurfaceFlinger VD clock samples=" + samples + " display=" + displayId);
        } finally {
            // Only release the VD created here, never releaseAll/destroy or another live helper's VD.
            try {
                if (displayId > 0) serviceType.getMethod("releaseDisplay", int.class).invoke(service, displayId);
            } finally {
                call(controller, instance, "releaseVirtualDisplay", new Class<?>[]{});
            }
        }
    }
}
