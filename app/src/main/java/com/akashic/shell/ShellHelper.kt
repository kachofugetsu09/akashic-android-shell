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
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.CountDownLatch
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

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("Caller ${Binder.getCallingUid()} not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        return try {
            createVirtualDisplay(surface, width, height, dpi, trusted = true)
        } catch (_: SecurityException) {
            runCatching { createVirtualDisplay(surface, width, height, dpi, trusted = false) }.getOrDefault(-1)
        } catch (t: Throwable) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        var flags = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (1 shl 13)
            if (trusted) flags = flags or (1 shl 10) or (1 shl 11) or (1 shl 12)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
            // OWN_FOCUS (1<<14) + STEAL_TOP_FOCUS_DISABLED (1<<16)：独立焦点且绝不抢主屏焦点
            flags = flags or (1 shl 14) or (1 shl 16)
        }
        val vd = displayManager.createVirtualDisplay("akashic-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenAndImePolicy(id)
        return id
    }

    @SuppressLint("PrivateApi")
    private fun setFullscreenAndImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            runCatching { wm.javaClass.getMethod("setWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 1) }
            runCatching { wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 0) }
        }
    }

    override fun releaseDisplay(displayId: Int) {
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    override fun releaseAll() {
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        all.forEach { runCatching { it.release() } }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        if (synchronized(displays) { displayId !in displays }) return 3
        // 如果该应用已经在主屏或其他屏存在后台任务，先尝试挪到虚拟屏或直接在虚拟屏启动
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return 2
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val res = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, -2,
            ) as Int
            res >= 0
        } catch (_: Throwable) {
            val comp = intent.component?.flattenToShortString() ?: return 2
            execCmd("am", "start", "--display", "$displayId", "-n", comp) == 0
        }
        return if (ok) 0 else 3
    }

    override fun moveTaskToDisplay(packageName: String, targetDisplayId: Int): Boolean {
        return try {
            val tasks = allTasks()
            val idField = taskInfoClass.getField("taskId")
            val target = tasks.firstOrNull { t ->
                listOf("topActivity", "baseActivity").any { field ->
                    (runCatching { taskInfoClass.getField(field).get(t) }.getOrNull() as? ComponentName)?.packageName == packageName
                }
            } ?: return false
            val taskId = idField.getInt(target)
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(taskManager, taskId, targetDisplayId)
            true
        } catch (_: Throwable) {
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

    private fun allTasks(): List<Any> = try {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val m4 = methods.firstOrNull { it.parameterTypes.size == 4 }
        val list = if (m4 != null) m4.invoke(taskManager, 50, false, false, Display.INVALID_DISPLAY)
        else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 50, false, false)
        (list as? List<*>)?.filterNotNull().orEmpty()
    } catch (_: Throwable) {
        emptyList()
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
    }
}

object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) exitProcess(2)
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        Looper.prepareMainLooper()
        val service = AkashicShellService(appUid)
        service.enableAppAccessibility(packageName)
        deliverBinder(packageName, appUid, service)
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun deliverBinder(packageName: String, appUid: Int, service: AkashicShellService) {
        val authority = "$packageName.helper"
        val userId = appUid / 100_000
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = runCatching {
            am.javaClass.getMethod(
                "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java,
            ).invoke(am, authority, userId, token, "AkashicHelper")
        }.getOrNull() ?: exitProcess(1)
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
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
            val appToken = res?.getBinder("token") ?: exitProcess(0)
            appToken.linkToDeath({
                runCatching { service.releaseAll() }
                exitProcess(0)
            }, 0)
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
        if (method != "attach" || Binder.getCallingUid() != 2000) return null
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
    private val appToken = Binder()
    @Volatile var shell: IAkashicShell? = null
        private set
    @Volatile var statusText: String = "未配对（当前为普通无障碍模式）"
        private set
    @Volatile var pairingInProgress: Boolean = false
        private set
    private var handoffLatch: CountDownLatch? = null

    fun acceptBinder(binder: IBinder): Bundle {
        val iface = IAkashicShell.Stub.asInterface(binder)
        runCatching {
            binder.linkToDeath({
                if (shell === iface) {
                    shell = null
                    statusText = "助手已断开"
                    ShellStatus.notifyChanged()
                }
            }, 0)
        }
        shell = iface
        statusText = "已激活 UID 2000 后台独立虚拟屏"
        handoffLatch?.countDown()
        ShellStatus.notifyChanged()
        return Bundle().apply {
            putBoolean("accepted", true)
            putBinder("token", appToken)
        }
    }

    fun tryAutoStartIfPaired(context: Context) {
        if (shell != null || !ShellPrefs(context).adbPaired) return
        Thread({
            runCatching { launchHelperOverAdb(context) }
        }, "AkashicHelperAutoStart").start()
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
                    ?: error("未发现无线调试配对弹窗，请先在「开发者选项 → 无线调试」中点开「使用配对码配对设备」")
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

    @SuppressLint("PrivateApi")
    fun launchHelperOverAdb(context: Context) {
        if (shell != null) return
        val prefs = ShellPrefs(context)
        val candidates = LinkedHashSet<Int>()
        prefs.lastAdbConnectPort.takeIf { it > 0 }?.let { candidates.add(it) }
        runCatching {
            val prop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tls.port") as? String
            prop?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { candidates.add(it) }
        }
        candidates.addAll(AdbWireless.findPorts(context, AdbWireless.SERVICE_CONNECT, 3_500L))
        if (candidates.isEmpty()) error("未找到无线调试连接端口，请确认「无线调试」开关已打开")

        var lastErr: Throwable? = null
        for (port in candidates) {
            try {
                AdbConnection.open(context, port).use { conn ->
                    prefs.setLastAdbConnectPort(port)
                    val latch = CountDownLatch(1)
                    handoffLatch = latch
                    val apk = context.applicationInfo.sourceDir
                    val pkg = context.packageName
                    val uid = Process.myUid()
                    val cmd = "(CLASSPATH='$apk' setsid app_process /system/bin --nice-name='$pkg:helper' com.akashic.shell.HelperMain '$pkg' $uid >/dev/null 2>&1 &); sleep 1"
                    conn.exec(cmd).use { _ ->
                        latch.await(8, TimeUnit.SECONDS)
                    }
                    if (shell != null) return
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw lastErr ?: IllegalStateException("无法连接本机无线调试端口")
    }
}
