package com.akashic.shell;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated Binder fault fixture; it never creates or moves a real Android task. */
public final class TaskTransferProbe extends Instrumentation {
    private static int VD;
    private static final int TASK = 42;
    private final Bundle report = new Bundle();
    private Object owner;
    private Method execute;
    private ImageReader reader;
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        int result = Activity.RESULT_OK;
        boolean isolated = false;
        try {
            require(getTargetContext().getPackageName().endsWith(".resumetest"), "isolated package required");
            isolated = true;
            VD = Integer.parseInt(arguments.getString("metadata_display_id", "-1"));
            require(VD > 0 && getTargetContext().getSystemService(DisplayManager.class).getDisplay(VD) != null,
                    "an existing non-main metadata display is required; no task will be moved");
            Class<?> ownerType = Class.forName("com.akashic.shell.PhoneBridgeController$ConnectionOwner");
            java.lang.reflect.Constructor<?> constructor = ownerType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            owner = constructor.newInstance("http://fixture.invalid", "fixture-connection");
            execute = PhoneBridgeController.class.getDeclaredMethod("executeRpc", Context.class,
                    ownerType, String.class, String.class, JSONObject.class);
            execute.setAccessible(true);
            runCases();
            report.putBoolean("ok", true);
            report.putBoolean("real_task_migration_tested", false);
            report.putBoolean("production_app_touched", false);
        } catch (Exception error) {
            report.putBoolean("ok", false);
            report.putString("error", error.toString());
            result = Activity.RESULT_CANCELED;
        } finally {
            try {
                if (isolated) {
                    field(PhoneBridgeController.class, "virtualDisplayId").set(null, null);
                    field(PhoneBridgeController.class, "isTrustedVirtualDisplay").set(null, false);
                    field(PhoneBridgeController.class, "takeoverReason").set(null, null);
                    field(PhoneBridgeController.class, "handedTaskId").set(null, null);
                    field(PhoneBridgeController.class, "activeOwner").set(null, null);
                    field(ShellHelperHub.class, "shell").set(null, null);
                    setReader(null);
                    if (reader != null) reader.close();
                }
            } catch (ReflectiveOperationException error) {
                report.putBoolean("ok", false);
                report.putString("cleanup_error", error.toString());
                result = Activity.RESULT_CANCELED;
            }
        }
        finish(result, report);
    }

    /** Exercise actual Controller branches through a generated AIDL Proxy and fault Binder. */
    private void runCases() throws Exception {
        FaultBinder helper = reset(false, null);
        require(!resume().getBoolean("ok") && helper.keepCalls == 0 && helper.moveCalls == 0, "unrequested resume sent effects");
        report.putBoolean("unrequested_resume_not_sent", true);

        helper = reset(true, null);
        JSONObject waiting = resume();
        require(waiting.getBoolean("ok") && helper.moveCalls == 0 && reason() == null, "waiting cancel moved a task");
        require(waiting.getJSONObject("result").getString("message").contains("未从主屏移动"), "waiting cancel claim");
        report.putBoolean("never_handed_resume_only_cleared_wait", true);

        helper = reset(true, null);
        helper.legacy = true;
        PhoneBridgeController.INSTANCE.handleTakeoverTap();
        require(helper.topCalls == 0 && helper.moveCalls == 0 && task() == null && reason() != null, "legacy helper transferred");
        report.putBoolean("legacy_helper_refused_before_id_or_move", true);
        helper = reset(true, TASK);
        helper.legacy = true;
        require(!resume().getBoolean("ok") && helper.moveCalls == 0 && reason() != null && task().equals(TASK), "legacy resume lost owner");
        report.putBoolean("legacy_helper_resume_kept_id_and_reason", true);

        helper = reset(true, null);
        helper.reject = true;
        PhoneBridgeController.INSTANCE.handleTakeoverTap();
        require(task().equals(TASK) && reason() != null, "rejected handoff lost identity");
        require(!resume().getBoolean("ok") && task().equals(TASK) && reason() != null, "rejected resume cleared wait");
        report.putBoolean("rejected_move_kept_id_and_reason", true);

        helper = reset(true, null);
        helper.loseReply = true;
        PhoneBridgeController.INSTANCE.handleTakeoverTap();
        require(helper.location == 0 && task().equals(TASK) && reason() != null, "lost reply lost task ID");
        helper.loseReply = false;
        helper.nextTop = 99;
        PhoneBridgeController.INSTANCE.handleTakeoverTap();
        require(helper.topCalls == 1 && helper.lastTask == TASK, "repeated click selected another task");
        require(resume().getBoolean("ok") && helper.location == VD && reason() == null && task() == null, "exact resume failed");
        report.putBoolean("lost_reply_kept_exact_id_and_retry_did_not_reselect", true);

        helper = reset(true, TASK);
        helper.location = 9;
        require(!resume().getBoolean("ok") && helper.effects == 0 && reason() != null && task().equals(TASK), "third display moved");
        report.putBoolean("third_display_refused_and_wait_kept", true);
        helper = reset(true, TASK);
        helper.exists = false;
        require(!resume().getBoolean("ok") && reason() != null && task().equals(TASK), "missing task cleared wait");
        report.putBoolean("missing_task_refused_and_wait_kept", true);
        helper = reset(true, TASK);
        require(resume().getBoolean("ok") && helper.effects == 0 && reason() == null, "already-owned task was moved");
        report.putBoolean("already_at_target_accepted_without_move", true);

        helper = reset(true, null);
        ReentrantLock lock = (ReentrantLock) field(PhoneBridgeController.class, "effectLock").get(null);
        lock.lock();
        try {
            Thread click = new Thread(() -> PhoneBridgeController.INSTANCE.handleTakeoverTap());
            click.start();
            click.join(2000);
            require(!click.isAlive() && helper.topCalls == 0 && helper.moveCalls == 0, "busy click transferred");
        } finally { lock.unlock(); }
        report.putBoolean("click_obeyed_existing_effect_lock", true);
        // A released generation must stay stopped even if a blocked move later returns or throws.
        for (boolean loseReply : new boolean[]{false, true}) {
            helper = reset(true, null);
            helper.blockMove = true;
            helper.loseReply = loseReply;
            Thread click = new Thread(() -> PhoneBridgeController.INSTANCE.handleTakeoverTap());
            click.start();
            require(helper.moveStarted.await(2, TimeUnit.SECONDS), "move did not reach Binder fixture");
            PhoneBridgeController.INSTANCE.stop();
            helper.moveRelease.countDown();
            click.join(2000);
            require(!click.isAlive() && reason() == null && task() == null &&
                    PhoneBridgeController.INSTANCE.getPipMode() == PipMode.HIDDEN, "late move revived takeover state");
        }
        report.putBoolean("released_generation_ignored_late_move_reply_and_failure", true);
    }

    private FaultBinder reset(boolean waiting, Integer id) throws Exception {
        // Real geometry and reader preconditions; all task effects still end at the fault Binder.
        setReader(null);
        if (reader != null) reader.close();
        reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2);
        setReader(reader);
        FaultBinder helper = new FaultBinder();
        field(ShellHelperHub.class, "shell").set(null, IAkashicShell.Stub.asInterface(helper));
        field(PhoneBridgeController.class, "virtualDisplayId").set(null, VD);
        field(PhoneBridgeController.class, "isTrustedVirtualDisplay").set(null, true);
        field(PhoneBridgeController.class, "takeoverReason").set(null, waiting ? "fixture wait" : null);
        field(PhoneBridgeController.class, "handedTaskId").set(null, id);
        field(PhoneBridgeController.class, "activeOwner").set(null, owner);
        return helper;
    }
    private void setReader(ImageReader value) throws ReflectiveOperationException {
        Method method = PhoneBridgeController.class.getDeclaredMethod("setFrameReader", ImageReader.class);
        method.setAccessible(true);
        method.invoke(PhoneBridgeController.INSTANCE, value);
    }
    private JSONObject resume() throws Exception {
        return (JSONObject) execute.invoke(PhoneBridgeController.INSTANCE, getTargetContext(), owner,
                java.util.UUID.randomUUID().toString(), "action", new JSONObject().put("action", "resume_from_user"));
    }
    private Object reason() throws Exception { return field(PhoneBridgeController.class, "takeoverReason").get(null); }
    private Integer task() throws Exception { return (Integer) field(PhoneBridgeController.class, "handedTaskId").get(null); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }

    /** Test double at the Binder boundary, including a side effect with a lost reply. */
    private static final class FaultBinder extends Binder {
        boolean legacy, reject, loseReply, blockMove, exists = true;
        final CountDownLatch moveStarted = new CountDownLatch(1), moveRelease = new CountDownLatch(1);
        int location = VD, nextTop = TASK, topCalls, moveCalls, keepCalls, effects, lastTask;
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            data.enforceInterface("com.akashic.shell.IAkashicShell");
            if (code == IAkashicShell.Stub.TRANSACTION_identity) {
                reply.writeNoException();
                reply.writeIntArray(legacy ? new int[]{Process.myUid(), Process.myPid()} : new int[]{Process.myUid(), Process.myPid(), IAkashicShell.TASK_TRANSFER_VERSION});
            } else if (code == IAkashicShell.Stub.TRANSACTION_topTaskId) {
                require(data.readInt() == VD, "unexpected source display"); topCalls++;
                reply.writeNoException(); reply.writeInt(nextTop);
            } else if (code == IAkashicShell.Stub.TRANSACTION_moveTaskById) {
                lastTask = data.readInt(); int source = data.readInt(), target = data.readInt(); moveCalls++;
                boolean accepted = !reject && exists && lastTask == TASK && (location == source || location == target);
                if (accepted && location != target) { location = target; effects++; }
                if (blockMove) {
                    moveStarted.countDown();
                    try { require(moveRelease.await(2, TimeUnit.SECONDS), "move fixture was not released"); }
                    catch (InterruptedException error) { throw new IllegalStateException(error); }
                }
                if (loseReply) throw new RemoteException("fixture lost reply after simulated effect");
                reply.writeNoException(); reply.writeBoolean(accepted);
            } else if (code == IAkashicShell.Stub.TRANSACTION_keepAwake) {
                require(data.readInt() == VD, "unexpected awake display"); keepCalls++;
                reply.writeNoException(); reply.writeBoolean(true);
            } else if (code == IAkashicShell.Stub.TRANSACTION_releaseDisplay) {
                data.readInt(); reply.writeNoException();
            } else if (code == IAkashicShell.Stub.TRANSACTION_topActivity) {
                data.readInt(); reply.writeNoException(); reply.writeString(null);
            } else throw new RemoteException("unexpected fixture transaction " + code);
            return true;
        }
    }
}
