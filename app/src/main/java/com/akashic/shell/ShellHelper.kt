package com.akashic.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun create(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(at)
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val ctx = ShellContext(system)
            if (at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.get(thread) == null) {
                runCatching {
                    val app = Instrumentation.newApplication(Application::class.java, ctx)
                    at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
                }
            }
            return ctx
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
        private fun newActivityThread(at: Class<*>): Any {
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                val cc = Class.forName("android.app.ConfigurationController")
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }
            runCatching {
                val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                abd.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(data, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            }
            return thread
        }
    }
}

class AkashicShellService(private val allowedUid: Int) : IAkashicShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }
    private val taskInfoClass: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
    private val power = DisplayPower()
    // displayId -> 保活租约到期时间（uptimeMillis）
    private val awakeLeases = HashMap<Int, Long>()

    init {
        Thread({ keepAwakeLoop() }, "AkashicKeepAwake").apply {
            isDaemon = true
            start()
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("Caller ${Binder.getCallingUid()} not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid(), IAkashicShell.TASK_TRANSFER_VERSION)

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        return try {
            createVirtualDisplay(surface, width, height, dpi)
        } catch (t: Throwable) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        var flags = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (1 shl 13)
            flags = flags or (1 shl 10) or (1 shl 11) or (1 shl 12)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // OWN_FOCUS (1<<14) + STEAL_TOP_FOCUS_DISABLED (1<<16)：独立焦点且绝不抢主屏焦点
            flags = flags or (1 shl 14) or (1 shl 16)
        }
        val vd = displayManager.createVirtualDisplay("akashic-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        try {
            check(id > Display.DEFAULT_DISPLAY && vd.display.flags and REQUIRED_DISPLAY_FLAGS == REQUIRED_DISPLAY_FLAGS) {
                "后台显示器缺少实际生效的隔离标志 (flags=${vd.display.flags})"
            }
            setFullscreenAndImePolicy(id)
        } catch (error: Exception) {
            vd.release()
            throw error
        }
        synchronized(displays) { displays[id] = vd }
        renewLease(id)
        return id
    }

    // 虚拟屏带 OWN_DISPLAY_GROUP，有独立电源组：主屏熄灭后它会按无操作超时单独休眠，之后只出黑帧。
    // 每次端侧操作续一次租约；恢复时只唤醒独立电源组，WAKEUP 按键会被系统路由到主屏。
    override fun keepAwake(displayId: Int): Boolean {
        if (synchronized(displays) { displayId !in displays }) return false
        val display = displayManager.getDisplay(displayId) ?: return false
        // Display.FLAG_OWN_DISPLAY_GROUP 是实际生效的标志，不是创建时请求的标志。
        if (displayId <= Display.DEFAULT_DISPLAY || display.flags and (1 shl 8) == 0) return false
        renewLease(displayId)
        power.userActivity(displayId)
        if (power.isInteractive(displayId)) return true
        power.wakeUp(displayId)
        repeat(10) {
            SystemClock.sleep(100L)
            if (power.isInteractive(displayId)) return true
        }
        return false
    }

    private fun renewLease(displayId: Int) {
        synchronized(awakeLeases) { awakeLeases[displayId] = SystemClock.uptimeMillis() + KEEP_AWAKE_LEASE_MS }
    }

    private fun keepAwakeLoop() {
        while (true) {
            SystemClock.sleep(KEEP_AWAKE_INTERVAL_MS)
            val now = SystemClock.uptimeMillis()
            val live = synchronized(awakeLeases) {
                awakeLeases.entries.removeAll { it.value <= now }
                awakeLeases.keys.toList()
            }
            live.forEach { id ->
                runCatching { power.userActivity(id) }
                    .onFailure { Log.w(TAG, "userActivity($id) failed", it) }
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun setFullscreenAndImePolicy(displayId: Int) {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        runCatching { wm.javaClass.getMethod("setWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 1) }
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 0)
        check(wm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType).invoke(wm, displayId) == 0) {
            "后台显示器没有使用本地输入法，已拒绝创建"
        }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(awakeLeases) { awakeLeases.remove(displayId) }
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    override fun releaseAll() {
        synchronized(awakeLeases) { awakeLeases.clear() }
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        all.forEach { runCatching { it.release() } }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return 2
        return startIntent(displayId, intent)
    }

    /** 在所属显示器启动目标，保留主屏现有任务。 */
    override fun startIntent(displayId: Int, intent: Intent): Int {
        if (synchronized(displays) { displayId !in displays }) return 3
        // 1. 解析实际 Activity，并查询实时任务归属，不能用包名的第一个任务代替 owner。
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return 2
        val tasks = allTasks()
        val displayField = taskInfoClass.getField("displayId")
        val idField = taskInfoClass.getField("taskId")
        val baseIntentField = taskInfoClass.getField("baseIntent")
        val realActivityField = taskInfoClass.getField("realActivity")
        val component = ComponentName(info.packageName, info.targetActivity ?: info.name)
        val matching = tasks.filter { task ->
            realActivityField.get(task) == component || (baseIntentField.get(task) as Intent).component == component
        }
        val owned = matching.firstOrNull { displayField.getInt(it) == displayId }
        val independent = info.launchMode in setOf(ActivityInfo.LAUNCH_MULTIPLE, ActivityInfo.LAUNCH_SINGLE_TOP, ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK) &&
            info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NEVER
        // Android 16 跨显示器只选 component/document 的 ideal task，不跨屏选 affinity candidate。
        if (!independent && matching.any { displayField.getInt(it) != displayId }) return 4
        // 2. 优先复用 owned VD 的精确 launcher task；新实例明确使用 MULTIPLE_TASK。
        val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (owned != null) {
            options.javaClass.getMethod("setLaunchTaskId", Int::class.javaPrimitiveType).invoke(options, idField.getInt(owned))
        } else if (independent) {
            launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
        val result = start.invoke(
            taskManager, null, ShellContext.SHELL_PACKAGE, null, launch, launch.resolveTypeIfNeeded(context.contentResolver), null, null,
            0, 0, null, options.toBundle(), -2,
        ) as Int
        // Android 16 ActivityManager.isStartResultSuccessful；100+ 是非致命启动错误。
        return if (result in 0..99) 0 else 3
    }

    /** 只选择助手拥有的显示器上的当前任务，绝不从主屏按包名挑选。 */
    override fun topTaskId(sourceDisplayId: Int): Int {
        if (synchronized(displays) { sourceDisplayId !in displays }) return -1
        return try {
            val displayField = taskInfoClass.getField("displayId")
            val topField = taskInfoClass.getField("topActivity")
            val task = allTasks().firstOrNull {
                displayField.getInt(it) == sourceDisplayId && topField.get(it) != null
            } ?: return -1
            taskInfoClass.getField("taskId").getInt(task)
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "topTaskId failed", error)
            -1
        } catch (error: SecurityException) {
            Log.e(TAG, "topTaskId denied", error)
            -1
        }
    }

    /** 只移动记录的确切任务；已在目标可重复确认，第三屏或消失则拒绝。 */
    override fun moveTaskById(taskId: Int, expectedSourceDisplayId: Int, targetDisplayId: Int): Boolean {
        val sourceOwned = synchronized(displays) { expectedSourceDisplayId in displays }
        val targetOwned = synchronized(displays) { targetDisplayId in displays }
        if (!((sourceOwned && targetDisplayId == Display.DEFAULT_DISPLAY) ||
                    (expectedSourceDisplayId == Display.DEFAULT_DISPLAY && targetOwned))) return false
        return try {
            val idField = taskInfoClass.getField("taskId")
            val task = allTasks().firstOrNull { idField.getInt(it) == taskId } ?: return false
            val currentDisplay = taskInfoClass.getField("displayId").getInt(task)
            if (currentDisplay == targetDisplayId) return true
            if (currentDisplay != expectedSourceDisplayId) return false
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(taskManager, taskId, targetDisplayId)
            true
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "moveTaskById failed", error)
            false
        } catch (error: SecurityException) {
            Log.e(TAG, "moveTaskById denied", error)
            false
        }
    }

    override fun topActivity(displayId: Int): String? = try {
        val dispField = taskInfoClass.getField("displayId")
        val topField = taskInfoClass.getField("topActivity")
        allTasks().firstOrNull { dispField.getInt(it) == displayId }?.let {
            (topField.get(it) as? ComponentName)?.flattenToShortString()
        }
    } catch (_: Throwable) {
        null
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val m4 = methods.firstOrNull { it.parameterTypes.size == 4 }
        val list = if (m4 != null) m4.invoke(taskManager, Int.MAX_VALUE, false, false, Display.INVALID_DISPLAY)
        else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, Int.MAX_VALUE, false, false)
        @Suppress("UNCHECKED_CAST")
        return list as List<Any>
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 50L, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            if (injectEvent(down, displayId, false)) {
                SystemClock.sleep(45L)
                injectEvent(up, displayId, false)
            } else {
                execCmd("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val steps = (durationMs / 16).coerceIn(4, 60)
        val start = SystemClock.uptimeMillis()
        fun obtain(step: Int, action: Int): MotionEvent {
            val t = step.toFloat() / steps
            val cx = x1 + (x2 - x1) * t
            val cy = y1 + (y2 - y1) * t
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = cx; y = cy; pressure = 1f; size = 1f }
            return MotionEvent.obtain(start, start + (durationMs * t).toLong(), action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val d = obtain(0, MotionEvent.ACTION_DOWN)
        val okDown = try { injectEvent(d, displayId, false) } finally { d.recycle() }
        if (!okDown) return execCmd("input", "-d", "$displayId", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs") == 0
        for (i in 1 until steps) {
            SystemClock.sleep((durationMs / steps).toLong().coerceAtLeast(8L))
            val m = obtain(i, MotionEvent.ACTION_MOVE)
            try { injectEvent(m, displayId, false) } finally { m.recycle() }
        }
        val u = obtain(steps, MotionEvent.ACTION_UP)
        return try { injectEvent(u, displayId, false) } finally { u.recycle() }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return if (injectEvent(down, displayId, true)) {
            injectEvent(up, displayId, true)
        } else {
            execCmd("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        }
    }

    @SuppressLint("PrivateApi")
    private fun injectEvent(event: InputEvent, displayId: Int, isKey: Boolean): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val mgr = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
        mgr.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(mgr, event, if (isKey) 1 else 0) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun enableAppAccessibility(packageName: String) {
        val comp = "$packageName/$packageName.AkashicAccessibilityService"
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c",
                "cur=$(settings get secure enabled_accessibility_services); " +
                "case \":\$cur:\" in *\":$comp:\"* ) ;; * ) " +
                "if [ -z \"\$cur\" ] || [ \"\$cur\" = \"null\" ]; then next=\"$comp\"; else next=\"\$cur:$comp\"; fi; " +
                "settings put secure enabled_accessibility_services \"\$next\"; ;; esac; " +
                "settings put secure accessibility_enabled 1"
            ).start()
            p.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun execCmd(vararg cmd: String): Int = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (p.waitFor(8, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
    } catch (_: Throwable) {
        -1
    }

    companion object {
        private const val TAG = "AkashicShellService"
        // Display 的 TRUSTED、OWN_DISPLAY_GROUP、ALWAYS_UNLOCKED、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED。
        private const val REQUIRED_DISPLAY_FLAGS = (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 11) or (1 shl 12)
        private const val KEEP_AWAKE_INTERVAL_MS = 4_000L
        private const val KEEP_AWAKE_LEASE_MS = 5 * 60_000L
    }
}

// IPowerManager 按显示查询与上报用户活动（隐藏接口，UID 2000 有权限；做法同 scrcpy --keep-active）。
@SuppressLint("PrivateApi")
internal class DisplayPower {
    private val service: Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "power") as IBinder
        Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()
    private val userActivityMethod = runCatching {
        service!!.javaClass.getMethod(
            "userActivity", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
    }.getOrNull()
    private val interactiveMethod = runCatching {
        service!!.javaClass.getMethod("isDisplayInteractive", Int::class.javaPrimitiveType)
    }.getOrNull()

    private val wakeMethod = runCatching {
        service!!.javaClass.getMethod(
            "wakeUpWithDisplayId", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
    }.getOrNull()

    fun userActivity(displayId: Int) {
        checkNotNull(userActivityMethod) { "设备不支持按显示器上报用户活动" }
            .invoke(service, displayId, SystemClock.uptimeMillis(), 0, 0)
    }

    fun isInteractive(displayId: Int): Boolean =
        checkNotNull(interactiveMethod) { "设备不支持查询显示器电源状态" }
            .invoke(service, displayId) as Boolean

    fun wakeUp(displayId: Int) {
        checkNotNull(wakeMethod) { "设备不支持独立唤醒后台显示器，未唤醒主屏" }
            .invoke(service, SystemClock.uptimeMillis(), 2, "akashic:phone-display", ShellContext.SHELL_PACKAGE, displayId)
    }

}

object HelperMain {
    private const val TAG = "AkashicHelperMain"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) exitProcess(2)
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        Looper.prepareMainLooper()
        val service = AkashicShellService(appUid)
        deliverBinder(packageName, appUid, service)
        service.enableAppAccessibility(packageName)
        Log.i(TAG, "HelperMain entered Looper.loop() for $packageName ($appUid)")
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun deliverBinder(packageName: String, appUid: Int, service: AkashicShellService) {
        val authority = "$packageName.helper"
        val userId = appUid / 100_000
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = try {
            am.javaClass.getMethod(
                "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java,
            ).invoke(am, authority, userId, token, "AkashicHelper")
        } catch (t: Throwable) {
            Log.e(TAG, "getContentProviderExternal failed", t)
            null
        } ?: exitProcess(1)
        try {
            val providerField = holder.javaClass.getDeclaredField("provider").apply { isAccessible = true }
            val provider = providerField.get(holder) ?: run {
                Log.e(TAG, "holder.provider is null")
                exitProcess(1)
            }
            val extras = Bundle().apply {
                putBinder("binder", service)
                putInt("uid", Process.myUid())
                putInt("pid", Process.myPid())
            }
            val call = provider.javaClass.methods.first {
                it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
            }
            val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
            val res = call.invoke(provider, caller, authority, "attach", null, extras) as? Bundle
            if (res?.containsKey("accepted") == true && !res.getBoolean("accepted")) {
                Log.i(TAG, "Existing helper kept; duplicate handoff rejected")
                exitProcess(0)
            }
            val appToken = res?.getBinder("token") ?: run {
                Log.e(TAG, "provider.call returned null or missing token: $res")
                exitProcess(0)
            }
            Log.i(TAG, "Successfully delivered IAkashicShell binder to $packageName")
            appToken.linkToDeath({
                runCatching { service.releaseAll() }
                exitProcess(0)
            }, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "deliverBinder failed", t)
            exitProcess(1)
        } finally {
            runCatching {
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                ).invoke(am, authority, token, userId)
            }
        }
    }
}

class HelperHandoffProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callerUid = Binder.getCallingUid()
        if (method != "attach" || (callerUid != 2000 && callerUid != 0)) return null
        val binder = extras?.getBinder("binder") ?: return null
        return ShellHelperHub.acceptBinder(binder)
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

object ShellHelperHub {
    private const val TAG = "AkashicShellHub"
    private val appToken = Binder()
    @Volatile var shell: IAkashicShell? = null
        private set
    @Volatile var statusText: String = "未配对（当前为普通无障碍模式）"
        private set
    @Volatile var pairingInProgress: Boolean = false
        private set
    @Volatile private var handoffLatch: CountDownLatch? = null
    private val startLock = Any()
    private var startTask: FutureTask<Unit>? = null

    /** 原子保留唯一活助手，拒绝的进程不获得 appToken。 */
    fun acceptBinder(binder: IBinder): Bundle {
        val iface = IAkashicShell.Stub.asInterface(binder)
        val reply = Bundle().apply { putBoolean("accepted", false) }
        var changed = false
        synchronized(startLock) {
            // 1. 活 owner 不被重复进程替换；只有确认死亡后才允许接替。
            val current = shell
            if (current != null && current.asBinder().isBinderAlive) return reply
            if (current != null) {
                shell = null
                statusText = "助手已断开"
                changed = true
            }
            val death = IBinder.DeathRecipient {
                val cleared = synchronized(startLock) {
                    if (shell !== iface) false else {
                        shell = null
                        statusText = "助手已断开"
                        true
                    }
                }
                if (cleared) {
                    ShellStatus.notifyChanged()
                    PhoneBridgeController.notifyStateChanged()
                }
            }
            try {
                binder.linkToDeath(death, 0)
            } catch (error: RemoteException) {
                Log.w(TAG, "Helper binder was already dead during handoff", error)
                return@synchronized
            }
            if (!binder.isBinderAlive) {
                Log.w(TAG, "Helper binder died during handoff")
                return@synchronized
            }
            // 2. 与死亡清理共享短临界区，不让链接后的死亡回调错过本次发布。
            shell = iface
            statusText = "已激活 UID 2000 后台独立虚拟屏"
            reply.putBoolean("accepted", true)
            reply.putBinder("token", appToken)
            handoffLatch?.countDown()
            changed = true
        }
        if (changed) {
            ShellStatus.notifyChanged()
            PhoneBridgeController.notifyStateChanged()
        }
        return reply
    }

    /** 多次同步只预留一个后台启动任务。 */
    fun tryAutoStartIfPaired(context: Context) {
        // 先预留启动任务，重复 sync 不再创建等待同一次启动的线程。
        synchronized(startLock) {
            if (shell != null || startTask != null) return
            val task = newStart(context)
            var started = false
            try {
                Thread({ runCatching { runStart(task) } }, "AkashicHelperAutoStart").start()
                started = true
            } finally {
                if (!started) task.cancel(false)
            }
        }
    }

    fun pairAndStart(context: Context, rawCode: String, manualPort: Int?, onDone: (Boolean, String) -> Unit) {
        val code = rawCode.trim()
        if (!code.matches(Regex("\\d{6}"))) {
            onDone(false, "请输入 6 位数字配对码")
            return
        }
        pairingInProgress = true
        statusText = "正在搜索本机无线调试配对端口…"
        ShellStatus.notifyChanged()
        Thread({
            try {
                val port = manualPort?.takeIf { it in 1..65535 }
                    ?: AdbWireless.findPorts(context, AdbWireless.SERVICE_PAIRING, 8_000L).firstOrNull()
                    ?: error("未发现无线调试配对弹窗（打开配对码窗口后请勿切回本应用，直接下拉通知栏输入配对码）")
                statusText = "正在与端口 $port 配对…"
                ShellStatus.notifyChanged()
                AdbWireless.pair(context, port, code)
                ShellPrefs(context).setAdbPaired(true)
                statusText = "配对成功，正在启动 UID 2000 虚拟屏助手…"
                ShellStatus.notifyChanged()
                launchHelperOverAdb(context)
                onDone(true, "配对成功！后台独立焦点虚拟屏已激活")
            } catch (t: Throwable) {
                val msg = t.message ?: "配对失败"
                statusText = msg
                onDone(false, msg)
            } finally {
                pairingInProgress = false
                ShellStatus.notifyChanged()
            }
        }, "AkashicAdbPair").start()
    }

    /** 配对与自动启动共享任务，等待不占用启动锁。 */
    fun launchHelperOverAdb(context: Context) {
        val task = synchronized(startLock) {
            if (shell != null) return
            startTask ?: newStart(context)
        }
        // 发现、ADB 和 Binder 交接均在锁外，acceptBinder 可以正常完成交接。
        runStart(task)
    }

    /** 调用方持有 startLock；FutureTask 只执行一次，所有入口共享结果。 */
    private fun newStart(context: Context): FutureTask<Unit> {
        val appContext = context.applicationContext
        return object : FutureTask<Unit>({ startHelperOverAdb(appContext) }) {
            override fun done() {
                synchronized(startLock) {
                    if (startTask === this) startTask = null
                }
            }
        }.also { startTask = it }
    }

    /** 执行或加入同一次启动，并保留原始错误类型。 */
    private fun runStart(task: FutureTask<Unit>) {
        task.run()
        try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    @SuppressLint("PrivateApi")
    private fun startHelperOverAdb(context: Context) {
        if (shell != null) return
        val prefs = ShellPrefs(context)
        val candidates = LinkedHashSet<Int>()
        // 优先尝试 5555（adbd tcpip 5555 模式在切换移动数据流量时依然监听 127.0.0.1:5555）
        candidates.add(5555)
        prefs.lastAdbConnectPort.takeIf { it > 0 }?.let { candidates.add(it) }
        runCatching {
            val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tls.port") as? String
            prop?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { candidates.add(it) }
        }
        runCatching {
            val tcpProp = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tcp.port") as? String
            tcpProp?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { candidates.add(it) }
        }
        if (prefs.adbPaired) {
            candidates.addAll(AdbWireless.findPorts(context, AdbWireless.SERVICE_CONNECT, 3_500L))
        }

        var lastErr: Throwable? = null
        for (port in candidates) {
            try {
                AdbConnection.open(context, port, timeoutMs = if (port == 5555) 2_500 else 6_000).use { conn ->
                    if (port != 5555) prefs.setLastAdbConnectPort(port)
                    prefs.setAdbPaired(true)
                    val latch = CountDownLatch(1)
                    handoffLatch = latch
                    val apk = context.applicationInfo.sourceDir
                    val pkg = context.packageName
                    val uid = Process.myUid()
                    val cmd = "(setprop service.adb.tcp.port 5555 >/dev/null 2>&1 || true; CLASSPATH='$apk' nohup setsid app_process /system/bin --nice-name='$pkg:helper' com.akashic.shell.HelperMain '$pkg' $uid >/dev/null 2>&1 &); sleep 1"
                    conn.exec(cmd).use { _ ->
                        latch.await(8, TimeUnit.SECONDS)
                    }
                    if (shell != null) return
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        if (prefs.adbPaired) {
            throw lastErr ?: IllegalStateException("无法连接本机无线调试端口")
        }
    }
}
