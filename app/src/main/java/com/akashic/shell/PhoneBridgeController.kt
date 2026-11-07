package com.akashic.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

// 端侧后台控制桥：向 Akashic 服务端维持 /api/phone/stream 长连接，管理 UID 2000 特权 VirtualDisplay 与右下角画中画小窗。
object PhoneBridgeController {
    private const val TAG = "AkashicPhoneBridge"
    private const val READ_TIMEOUT_MS = 45_000
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    @Volatile var connected: Boolean = false
        private set
    @Volatile var pipMode: PipMode = PipMode.HIDDEN
        private set
    @Volatile var pipLabel: String = "后台待命"
        private set
    @Volatile var takeoverReason: String? = null
        private set
    @Volatile var takeoverPackage: String? = null
        private set
    @Volatile var virtualDisplayId: Int? = null
        private set
    @Volatile var isTrustedVirtualDisplay: Boolean = false
        private set
    @Volatile var latestPreviewPng: ByteArray? = null
        private set
    @Volatile var latestFullPng: ByteArray? = null
        private set

    var pipListener: ((PipMode, String, ByteArray?) -> Unit)? = null

    @Volatile private var latestAppContext: Context? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val wake = Any()

    private var displayThread: HandlerThread? = null
    private var displayHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var localVirtualDisplay: VirtualDisplay? = null
    private val activePackages = LinkedHashSet<String>()
    private var lastNodeRefs: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun sync(context: Context) {
        latestAppContext = context.applicationContext
        val prefs = ShellPrefs(context)
        val should = prefs.serverUrl != null && prefs.phoneUseEnabled
        if (should) {
            ShellHelperHub.tryAutoStartIfPaired(context.applicationContext)
            start(context.applicationContext)
        } else {
            stop()
        }
    }

    @Synchronized
    fun start(appContext: Context) {
        if (running && worker?.isAlive == true) {
            synchronized(wake) { (wake as Object).notifyAll() }
            return
        }
        running = true
        worker = Thread({ loop(appContext) }, "AkashicPhoneBridge").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        activeConnection?.disconnect()
        synchronized(wake) { (wake as Object).notifyAll() }
        releaseVirtualDisplay()
        connected = false
        updatePip(PipMode.HIDDEN, "已关闭", null)
        ShellStatus.notifyChanged()
    }

    fun deviceId(context: Context): String {
        val aid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.take(8).orEmpty()
        val modelSlug = Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (aid.isNotEmpty()) "$modelSlug-$aid" else modelSlug.ifEmpty { "android-phone" }
    }

    fun handleTakeoverTap(context: Context) {
        val pkg = takeoverPackage ?: activePackages.lastOrNull()
        if (pkg != null) {
            val sh = ShellHelperHub.shell
            if (sh != null) {
                runCatching { sh.moveTaskToDisplay(pkg, Display.DEFAULT_DISPLAY) }
            }
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
            }
        }
    }

    private fun updatePip(mode: PipMode, label: String, png: ByteArray?) {
        pipMode = mode
        pipLabel = label
        if (png != null) latestPreviewPng = png
        pipListener?.invoke(mode, label, png ?: latestPreviewPng)
        ShellStatus.notifyChanged()
    }

    private fun ensureVirtualDisplay(context: Context): Int {
        virtualDisplayId?.let { currentId ->
            if (isTrustedVirtualDisplay || ShellHelperHub.shell == null) return currentId
            // 如果之前还没配对，现在刚刚配对上了 UID 2000 助手，释放旧屏重建 Trusted 虚拟屏
            releaseVirtualDisplay()
        }
        synchronized(this) {
            virtualDisplayId?.let { return it }
            val dm = context.getSystemService(DisplayManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
            val w = (metrics.widthPixels.takeIf { it > 0 } ?: 1080)
            val h = (metrics.heightPixels.takeIf { it > 0 } ?: 2400)
            val dpi = (metrics.densityDpi.takeIf { it > 0 } ?: 420)

            val ht = HandlerThread("akashic-vd").apply { start() }
            val hnd = Handler(ht.looper)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            var lastFrameMs = 0L
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrameMs < 90L && latestPreviewPng != null) return@setOnImageAvailableListener
                    lastFrameMs = now
                    val plane = img.planes[0]
                    val strideWidth = plane.rowStride / plane.pixelStride
                    val rawBmp = Bitmap.createBitmap(strideWidth, h, Bitmap.Config.ARGB_8888)
                    rawBmp.copyPixelsFromBuffer(plane.buffer.rewind())

                    val fullBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(fullBmp).drawBitmap(rawBmp, Rect(0, 0, w, h), Rect(0, 0, w, h), bitmapPaint)
                    rawBmp.recycle()

                    val fullOut = ByteArrayOutputStream()
                    fullBmp.compress(Bitmap.CompressFormat.PNG, 90, fullOut)
                    latestFullPng = fullOut.toByteArray()

                    val prevW = 360
                    val prevH = (360f * h / w).toInt().coerceAtLeast(360)
                    val prevBmp = Bitmap.createScaledBitmap(fullBmp, prevW, prevH, true)
                    fullBmp.recycle()
                    val prevOut = ByteArrayOutputStream()
                    prevBmp.compress(Bitmap.CompressFormat.PNG, 85, prevOut)
                    prevBmp.recycle()
                    latestPreviewPng = prevOut.toByteArray()

                    if (pipMode != PipMode.HIDDEN) {
                        pipListener?.invoke(pipMode, pipLabel, latestPreviewPng)
                    }
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }, hnd)

            // 1. 优先通过已配对的 UID 2000 ShellService 创建带 TRUSTED + OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED 的后台虚拟屏
            val sh = ShellHelperHub.shell ?: run {
                ShellHelperHub.tryAutoStartIfPaired(context)
                SystemClock.sleep(500L)
                ShellHelperHub.shell
            }
            if (sh != null) {
                val id = runCatching { sh.createDisplay(reader.surface, w, h, dpi) }.getOrDefault(-1)
                if (id > 0) {
                    displayThread = ht
                    displayHandler = hnd
                    imageReader = reader
                    virtualDisplayId = id
                    isTrustedVirtualDisplay = true
                    return id
                }
            }

            // 2. 未配对无线调试时，不建无效的空虚拟屏，直接使用主屏 ID (0) 并通过 AccessibilityService.takeScreenshot 实时抓图供小窗显示
            reader.close()
            ht.quitSafely()
            virtualDisplayId = Display.DEFAULT_DISPLAY
            isTrustedVirtualDisplay = false
            return Display.DEFAULT_DISPLAY
        }
    }

    // 每次操作前给虚拟屏续保活租约；主屏熄灭期间它若已休眠且唤不醒，就释放重建一块新的。
    private fun ensureAwakeDisplay(context: Context): Int {
        val id = ensureVirtualDisplay(context)
        val sh = ShellHelperHub.shell
        if (!isTrustedVirtualDisplay || id <= 0 || sh == null) return id
        if (runCatching { sh.keepAwake(id) }.getOrDefault(true)) return id
        Log.i(TAG, "virtual display #$id asleep and could not be woken, recreating")
        releaseVirtualDisplay()
        return ensureVirtualDisplay(context)
    }

    private fun releaseVirtualDisplay() {
        synchronized(this) {
            val id = virtualDisplayId
            if (isTrustedVirtualDisplay && id != null && id > 0) {
                runCatching { ShellHelperHub.shell?.releaseDisplay(id) }
            }
            localVirtualDisplay?.release()
            localVirtualDisplay = null
            virtualDisplayId = null
            isTrustedVirtualDisplay = false
            imageReader?.close()
            imageReader = null
            displayThread?.quitSafely()
            displayThread = null
            displayHandler = null
            activePackages.clear()
            takeoverReason = null
            takeoverPackage = null
            latestFullPng = null
        }
    }

    private fun loop(context: Context) {
        val prefs = ShellPrefs(context)
        var attempts = 0
        while (running) {
            val server = prefs.serverUrl
            if (server == null || !prefs.phoneUseEnabled) break
            val established = connectOnce(context, server)
            connected = false
            ShellStatus.notifyChanged()
            if (!running) break
            val waitMs = if (established) {
                attempts = 0
                1_000L
            } else {
                val ms = BACKOFF_MS[minOf(attempts, BACKOFF_MS.lastIndex)]
                attempts++
                ms
            }
            synchronized(wake) {
                if (running) (wake as Object).wait(waitMs)
            }
        }
    }

    private fun buildHello(context: Context): JSONObject {
        return currentStateJson(context)
            .put("type", "hello")
            .put("device_id", deviceId(context))
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("model", Build.MODEL ?: "Android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("shell_version", BuildInfo.VERSION)
    }

    private fun currentStateJson(context: Context): JSONObject {
        val shReady = ShellHelperHub.shell != null
        val a11yReady = AkashicAccessibilityService.instance != null
        val mode = when {
            shReady -> "virtual_display"
            a11yReady -> "accessibility_only"
            else -> "pairing_required"
        }
        val pkgs = JSONArray()
        synchronized(this) { activePackages.forEach { pkgs.put(it) } }
        return JSONObject()
            .put("display_mode", mode)
            .put("virtual_display_id", virtualDisplayId ?: JSONObject.NULL)
            .put("own_focus", shReady && Build.VERSION.SDK_INT >= 34)
            .put("steal_top_focus_disabled", shReady && Build.VERSION.SDK_INT >= 34)
            .put("pip_state", pipMode.id)
            .put("active_packages", pkgs)
            .put("takeover_reason", takeoverReason ?: JSONObject.NULL)
    }

    fun notifyStateChanged() {
        val ctx = latestAppContext ?: return
        val server = ShellPrefs(ctx).serverUrl ?: return
        Thread({
            val frame = currentStateJson(ctx)
                .put("type", "state")
                .put("device_id", deviceId(ctx))
            postReply(server, null, frame)
        }, "AkashicPhoneStatePush").start()
    }

    private fun connectOnce(context: Context, server: String): Boolean {
        val conn = try {
            Server.open("$server/api/phone/stream", readTimeoutMs = READ_TIMEOUT_MS)
        } catch (_: IOException) {
            return false
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Origin", server)
        CookieManager.getInstance().getCookie(server)?.let { conn.setRequestProperty("Cookie", it) }
        activeConnection = conn
        var established = false
        try {
            val helloBytes = buildHello(context).toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(helloBytes) }
            if (conn.responseCode != 200) return false
            connected = true
            ShellStatus.notifyChanged()
            var event = ""
            val data = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                established = true
                                handleSseEvent(context, server, event, data.toString())
                            }
                            event = ""
                            data.setLength(0)
                        }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                    }
                }
            }
        } catch (_: IOException) {
        } catch (t: Throwable) {
            Log.w(TAG, "phone stream error", t)
        } finally {
            activeConnection = null
            conn.disconnect()
        }
        return established
    }

    private fun handleSseEvent(context: Context, server: String, event: String, rawData: String) {
        if (event == "heartbeat" || event == "welcome") return
        val json = runCatching { JSONObject(rawData) }.getOrNull() ?: return
        if (json.optString("type") != "request") return
        val requestId = json.optString("request_id")
        val op = json.optString("op")
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val cookie = runCatching { CookieManager.getInstance().getCookie(server) }.getOrNull()
        Thread({
            val reply = executeRpc(context, requestId, op, payload)
            postReply(server, cookie, reply)
        }, "AkashicPhoneRpc-$requestId").start()
    }

    private fun postReply(server: String, cookie: String?, body: JSONObject) {
        try {
            val conn = Server.open("$server/api/phone/reply", readTimeoutMs = 15_000)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", server)
            val resolvedCookie = cookie ?: runCatching { CookieManager.getInstance().getCookie(server) }.getOrNull()
            resolvedCookie?.let { conn.setRequestProperty("Cookie", it) }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "postReply HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "postReply failed", t)
        }
    }

    private fun executeRpc(context: Context, requestId: String, op: String, payload: JSONObject): JSONObject {
        val devId = deviceId(context)
        val response = JSONObject()
            .put("type", "response")
            .put("device_id", devId)
            .put("request_id", requestId)

        try {
            when (op) {
                "observe" -> {
                    val dispId = ensureAwakeDisplay(context)
                    updatePip(if (takeoverReason != null) PipMode.NEEDS_YOU else PipMode.RUNNING, "正在观察屏幕", null)
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    val screen = buildScreenObject(context, dispId, wantImg)
                    response.put("ok", true).put("result", screen).put("state", currentStateJson(context))
                }
                "action" -> {
                    val dispId = ensureAwakeDisplay(context)
                    val act = payload.optString("action")
                    val msg = runAction(context, dispId, act, payload)
                    val settleMs = if (act == "launch_app") 1_100L else 480L
                    SystemClock.sleep(settleMs)
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    var screen = buildScreenObject(context, dispId, wantImg)
                    if (act == "launch_app" && (screen.optJSONArray("nodes")?.length() ?: 0) == 0) {
                        SystemClock.sleep(800L)
                        screen = buildScreenObject(context, dispId, wantImg)
                    }
                    response.put("ok", true).put(
                        "result",
                        JSONObject().put("message", msg).put("screen", screen),
                    ).put("state", currentStateJson(context))
                }
                "finish" -> {
                    releaseVirtualDisplay()
                    updatePip(PipMode.DONE, "已完成", null)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (pipMode == PipMode.DONE) updatePip(PipMode.HIDDEN, "后台待命", null)
                    }, 2_200L)
                    response.put("ok", true).put(
                        "result",
                        JSONObject()
                            .put("kept", payload.optJSONArray("keep") ?: JSONArray())
                            .put("closed", payload.optJSONArray("close") ?: JSONArray()),
                    ).put("state", currentStateJson(context))
                }
                else -> response.put("ok", false).put("error", "未知端侧操作: $op")
            }
        } catch (t: Throwable) {
            response.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
        }
        return response
    }

    private fun runAction(context: Context, dispId: Int, action: String, payload: JSONObject): String {
        val sh = ShellHelperHub.shell
        val a11y = AkashicAccessibilityService.instance
        return when (action) {
            "launch_app" -> {
                val pkg = payload.optString("package_name")
                synchronized(this) { activePackages.add(pkg) }
                updatePip(PipMode.RUNNING, "后台运行 $pkg", null)
                if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    val code = sh.startApp(dispId, pkg)
                    if (code == 2) error("手机上未找到可启动的应用: $pkg")
                    if (code != 0) error("UID 2000 后台虚拟屏启动 $pkg 失败 (code=$code)")
                    "已在后台独立虚拟屏 (#$dispId) 启动应用 $pkg（主屏幕不跳转）"
                } else {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: error("手机上未找到可启动的应用: $pkg")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已启动应用 $pkg（提示：尚未完成无线调试配对，当前在前台启动；在 Akashic 设置中配对无线调试后即可在后台独立虚拟屏运行）"
                }
            }
            "tap" -> {
                val nodeId = payload.optInt("node_id", -1)
                if (nodeId > 0) {
                    val node = lastNodeRefs[nodeId]
                    if (node != null) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                            if (sh.injectTap(dispId, r.centerX(), r.centerY())) {
                                updatePip(PipMode.RUNNING, "点击控件 #$nodeId", null)
                                return "已在后台虚拟屏点击控件 [$nodeId] (${r.centerX()}, ${r.centerY()})"
                            }
                        }
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            updatePip(PipMode.RUNNING, "点击控件 #$nodeId", null)
                            return "已点击控件 [$nodeId]"
                        }
                        if (a11y != null && a11y.performTap(dispId, r.exactCenterX(), r.exactCenterY())) {
                            updatePip(PipMode.RUNNING, "点击坐标 #$nodeId", null)
                            return "已通过手势点击控件 [$nodeId]"
                        }
                    }
                }
                val x = payload.optInt("x", -1)
                val y = payload.optInt("y", -1)
                if (x >= 0 && y >= 0) {
                    if (sh != null && isTrustedVirtualDisplay && dispId > 0 && sh.injectTap(dispId, x, y)) {
                        updatePip(PipMode.RUNNING, "点击 ($x, $y)", null)
                        return "已在后台虚拟屏点击坐标 ($x, $y)"
                    }
                    if (a11y != null && a11y.performTap(dispId, x.toFloat(), y.toFloat())) {
                        updatePip(PipMode.RUNNING, "点击 ($x, $y)", null)
                        return "已点击坐标 ($x, $y)"
                    }
                }
                error("点击目标不可达，请确认已开启无障碍或无线调试配对")
            }
            "type_text" -> {
                val text = payload.optString("text")
                val nodeId = payload.optInt("node_id", -1)
                val targetNode = if (nodeId > 0) lastNodeRefs[nodeId] else lastNodeRefs.values.firstOrNull { it.isFocused || it.isEditable }
                if (targetNode != null && a11y != null && a11y.setNodeText(targetNode, text)) {
                    updatePip(PipMode.RUNNING, "输入文本", null)
                    "已输入文本 (${text.length} 字)"
                } else {
                    setClipboardSilently(context, text)
                    targetNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    updatePip(PipMode.RUNNING, "粘贴文本", null)
                    "已通过静默剪贴板填入文本 (${text.length} 字)"
                }
            }
            "clear_text" -> {
                val nodeId = payload.optInt("node_id", -1)
                val targetNode = if (nodeId > 0) lastNodeRefs[nodeId] else lastNodeRefs.values.firstOrNull { it.isFocused || it.isEditable }
                if (targetNode != null && a11y != null) {
                    a11y.setNodeText(targetNode, "")
                }
                "已清空输入框"
            }
            "scroll" -> {
                val dir = payload.optString("direction", "down")
                val dm = context.resources.displayMetrics
                val cx = dm.widthPixels / 2
                val h = dm.heightPixels
                val (x1, y1, x2, y2) = when (dir) {
                    "down" -> listOf(cx, (h * 0.75f).toInt(), cx, (h * 0.25f).toInt())
                    "up" -> listOf(cx, (h * 0.25f).toInt(), cx, (h * 0.75f).toInt())
                    "left" -> listOf((dm.widthPixels * 0.8f).toInt(), h / 2, (dm.widthPixels * 0.2f).toInt(), h / 2)
                    else -> listOf((dm.widthPixels * 0.2f).toInt(), h / 2, (dm.widthPixels * 0.8f).toInt(), h / 2)
                }
                if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    sh.injectSwipe(dispId, x1, y1, x2, y2, 320)
                } else {
                    a11y?.performSwipe(dispId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 320L)
                }
                updatePip(PipMode.RUNNING, "滚动 ($dir)", null)
                "已执行滚动 ($dir)"
            }
            "swipe" -> {
                val x1 = payload.optInt("x", 500)
                val y1 = payload.optInt("y", 1500)
                val x2 = payload.optInt("end_x", 500)
                val y2 = payload.optInt("end_y", 500)
                val dur = payload.optInt("duration_ms", 300)
                if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    sh.injectSwipe(dispId, x1, y1, x2, y2, dur)
                } else {
                    a11y?.performSwipe(dispId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dur.toLong())
                }
                "已滑动 ($x1,$y1)->($x2,$y2)"
            }
            "back" -> {
                if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    sh.injectKey(dispId, KeyEvent.KEYCODE_BACK)
                } else {
                    a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                "已执行返回键"
            }
            "press_key" -> {
                val key = payload.optString("key_code")
                val code = if (key.equals("BACK", ignoreCase = true)) KeyEvent.KEYCODE_BACK
                else if (key.equals("ENTER", ignoreCase = true)) KeyEvent.KEYCODE_ENTER
                else key.toIntOrNull() ?: KeyEvent.KEYCODE_BACK
                if (sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    sh.injectKey(dispId, code)
                } else if (code == KeyEvent.KEYCODE_BACK) {
                    a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                "已发送按键 $key"
            }
            "open_link" -> {
                val uri = payload.optString("uri")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                payload.optString("package_name").takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
                context.startActivity(intent)
                "已打开链接 $uri"
            }
            "share_text" -> {
                val pkg = payload.optString("package_name")
                val text = payload.optString("text")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (pkg.isNotEmpty()) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "已将文本分享至 $pkg"
            }
            "hand_to_user" -> {
                val reason = payload.optString("reason", "需要您在手机主屏幕确认操作")
                takeoverReason = reason
                takeoverPackage = activePackages.lastOrNull()
                updatePip(PipMode.NEEDS_YOU, "点此接管操作", null)
                Notifications.showTakeover(context, reason)
                "已挂起后台操作并通知用户点击右下角橙色呼吸小窗或通知接管 ($reason)"
            }
            "resume_from_user" -> {
                val pkg = takeoverPackage ?: activePackages.lastOrNull()
                if (pkg != null && sh != null && isTrustedVirtualDisplay && dispId > 0) {
                    runCatching { sh.moveTaskToDisplay(pkg, dispId) }
                }
                takeoverReason = null
                updatePip(PipMode.RUNNING, "后台恢复运行", null)
                "已从用户主屏收回控制权继续后台虚拟屏执行"
            }
            "wait" -> {
                SystemClock.sleep(payload.optLong("duration_ms", 1000L).coerceIn(100L, 10_000L))
                "已完成等待"
            }
            else -> "已执行 $action"
        }
    }

    private fun setClipboardSilently(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Akashic", text)
            val extras = PersistableBundle().apply {
                putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clip.description.extras = extras
            cm.setPrimaryClip(clip)
        }
    }

    private fun buildScreenObject(context: Context, dispId: Int, includeScreenshot: Boolean): JSONObject {
        val sh = ShellHelperHub.shell
        val a11y = AkashicAccessibilityService.instance
        val dm = context.resources.displayMetrics
        val readout = a11y?.dumpScreen(dispId)
        if (readout != null) {
            lastNodeRefs = readout.nodeRefs
        }
        val topComp = if (sh != null && dispId > 0) runCatching { sh.topActivity(dispId) }.getOrNull() else null
        val resolvedPkg = topComp?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            ?: readout?.packageName
            ?: activePackages.lastOrNull()
            ?: "com.akashic.shell"
        val resolvedAct = topComp?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }
            ?: readout?.activityName
            ?: "MainActivity"

        // 实时捕获截图并同时刷新右下角画中画小窗（解决小窗黑屏与截图空白问题）
        val pngBytes: ByteArray? = if (isTrustedVirtualDisplay && dispId > 0) {
            if (latestFullPng == null) SystemClock.sleep(280L)
            latestFullPng ?: a11y?.captureScreenshotPng(dispId)
        } else {
            val a11yPng = a11y?.captureScreenshotPng(Display.DEFAULT_DISPLAY)
            if (a11yPng != null && a11yPng.isNotEmpty()) {
                latestFullPng = a11yPng
                latestPreviewPng = a11yPng
                pipListener?.invoke(pipMode, pipLabel, a11yPng)
            }
            a11yPng ?: latestFullPng
        }
        val returnedPng = if (includeScreenshot) pngBytes else null

        return JSONObject()
            .put("display_id", dispId)
            .put("width", dm.widthPixels.takeIf { it > 0 } ?: 1080)
            .put("height", dm.heightPixels.takeIf { it > 0 } ?: 2400)
            .put("rotation", 0)
            .put("package_name", resolvedPkg)
            .put("activity_name", resolvedAct)
            .put("pip_state", pipMode.id)
            .put("audio_muted", true)
            .put("nodes", readout?.nodes ?: JSONArray())
            .put("takeover_reason", takeoverReason ?: JSONObject.NULL)
            .apply {
                if (returnedPng != null && returnedPng.isNotEmpty()) {
                    put("screenshot_b64", Base64.encodeToString(returnedPng, Base64.NO_WRAP))
                }
            }
    }
}
