package com.akashic.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
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

// 端侧后台控制桥：向 Akashic 服务端维持 /api/phone/stream 长连接，管理 VirtualDisplay / 无障碍操作与右下角画中画小窗。
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
    @Volatile var latestPreviewPng: ByteArray? = null
        private set

    var pipListener: ((PipMode, String, ByteArray?) -> Unit)? = null

    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val wake = Object()

    private var displayThread: HandlerThread? = null
    private var displayHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val activePackages = LinkedHashSet<String>()
    private var lastNodeRefs: Map<Int, AccessibilityNodeInfo> = emptyMap()

    fun sync(context: Context) {
        val prefs = ShellPrefs(context)
        val should = prefs.serverUrl != null && prefs.phoneUseEnabled
        if (should) {
            start(context.applicationContext)
        } else {
            stop()
        }
    }

    @Synchronized
    fun start(appContext: Context) {
        if (running && worker?.isAlive == true) {
            synchronized(wake) { wake.notifyAll() }
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
        synchronized(wake) { wake.notifyAll() }
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
        virtualDisplayId?.let { return it }
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
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    val strideWidth = plane.rowStride / plane.pixelStride
                    val bmp = Bitmap.createBitmap(strideWidth, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer.rewind())
                    val cropped = if (strideWidth != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                    if (cropped !== bmp) bmp.recycle()
                    val scaled = Bitmap.createScaledBitmap(cropped, 360, (360f * h / w).toInt().coerceAtLeast(360), true)
                    if (scaled !== cropped) cropped.recycle()
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
                    scaled.recycle()
                    latestPreviewPng = out.toByteArray()
                    if (pipMode != PipMode.HIDDEN) {
                        pipListener?.invoke(pipMode, pipLabel, latestPreviewPng)
                    }
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }, hnd)

            // 先尝试带 TRUSTED + OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED 标志创建独立焦点虚拟屏，若无 shell 权限则自动回退到普通 VirtualDisplay
            val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) or (1 shl 7) or (1 shl 8)
            val trustedFlags = baseFlags or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14) or (1 shl 16)
            val vd = runCatching {
                dm.createVirtualDisplay("akashic-agent", w, h, dpi, reader.surface, trustedFlags)
            }.getOrElse {
                runCatching {
                    dm.createVirtualDisplay("akashic-agent", w, h, dpi, reader.surface, baseFlags)
                }.getOrNull()
            }
            if (vd != null) {
                displayThread = ht
                displayHandler = hnd
                imageReader = reader
                virtualDisplay = vd
                val id = vd.display.displayId
                virtualDisplayId = id
                return id
            } else {
                reader.close()
                ht.quitSafely()
                return Display.DEFAULT_DISPLAY
            }
        }
    }

    private fun releaseVirtualDisplay() {
        synchronized(this) {
            virtualDisplay?.release()
            virtualDisplay = null
            virtualDisplayId = null
            imageReader?.close()
            imageReader = null
            displayThread?.quitSafely()
            displayThread = null
            displayHandler = null
            activePackages.clear()
            takeoverReason = null
            takeoverPackage = null
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
                if (running) wake.wait(waitMs)
            }
        }
    }

    private fun buildHello(context: Context): JSONObject {
        val a11yReady = AkashicAccessibilityService.instance != null
        val mode = if (a11yReady) "virtual_display" else "accessibility_only"
        val pkgs = JSONArray()
        synchronized(this) { activePackages.forEach { pkgs.put(it) } }
        return JSONObject()
            .put("type", "hello")
            .put("device_id", deviceId(context))
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("model", Build.MODEL ?: "Android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("shell_version", BuildInfo.VERSION)
            .put("display_mode", mode)
            .put("virtual_display_id", virtualDisplayId ?: JSONObject.NULL)
            .put("own_focus", Build.VERSION.SDK_INT >= 34)
            .put("steal_top_focus_disabled", Build.VERSION.SDK_INT >= 34)
            .put("pip_state", pipMode.id)
            .put("active_packages", pkgs)
            .put("takeover_reason", takeoverReason ?: JSONObject.NULL)
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
        Thread({
            val reply = executeRpc(context, requestId, op, payload)
            postReply(server, reply)
        }, "AkashicPhoneRpc-$requestId").start()
    }

    private fun postReply(server: String, body: JSONObject) {
        try {
            val conn = Server.open("$server/api/phone/reply", readTimeoutMs = 15_000)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", server)
            CookieManager.getInstance().getCookie(server)?.let { conn.setRequestProperty("Cookie", it) }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
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
                    val dispId = ensureVirtualDisplay(context)
                    updatePip(if (takeoverReason != null) PipMode.NEEDS_YOU else PipMode.RUNNING, "正在观察屏幕", null)
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    val screen = buildScreenObject(context, dispId, wantImg)
                    response.put("ok", true).put("result", screen)
                }
                "action" -> {
                    val dispId = ensureVirtualDisplay(context)
                    val act = payload.optString("action")
                    val msg = runAction(context, dispId, act, payload)
                    SystemClock.sleep(320L)
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    val screen = buildScreenObject(context, dispId, wantImg)
                    response.put("ok", true).put(
                        "result",
                        JSONObject().put("message", msg).put("screen", screen),
                    )
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
                    )
                }
                else -> response.put("ok", false).put("error", "未知端侧操作: $op")
            }
        } catch (t: Throwable) {
            response.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
        }
        return response
    }

    private fun runAction(context: Context, dispId: Int, action: String, payload: JSONObject): String {
        val a11y = AkashicAccessibilityService.instance
        return when (action) {
            "launch_app" -> {
                val pkg = payload.optString("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: error("手机上未找到可启动的应用: $pkg")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                synchronized(this) { activePackages.add(pkg) }
                updatePip(PipMode.RUNNING, "正在打开 $pkg", null)
                runCatching {
                    val opts = android.app.ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dispId != Display.DEFAULT_DISPLAY) {
                        opts.launchDisplayId = dispId
                    }
                    context.startActivity(intent, opts.toBundle())
                }.onFailure {
                    context.startActivity(intent)
                }
                "已在手机后台启动应用 $pkg"
            }
            "tap" -> {
                val nodeId = payload.optInt("node_id", -1)
                if (nodeId > 0) {
                    val node = lastNodeRefs[nodeId]
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        updatePip(PipMode.RUNNING, "点击控件 #$nodeId", null)
                        return "已点击控件 [$nodeId]"
                    }
                    if (node != null && a11y != null) {
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        if (a11y.performTap(dispId, r.exactCenterX(), r.exactCenterY())) {
                            updatePip(PipMode.RUNNING, "点击坐标 #$nodeId", null)
                            return "已通过手势点击控件 [$nodeId]"
                        }
                    }
                }
                val x = payload.optInt("x", -1)
                val y = payload.optInt("y", -1)
                if (x >= 0 && y >= 0 && a11y != null && a11y.performTap(dispId, x.toFloat(), y.toFloat())) {
                    updatePip(PipMode.RUNNING, "点击 ($x, $y)", null)
                    "已点击坐标 ($x, $y)"
                } else {
                    error("无障碍服务未就绪或点击目标不可达，请确认已在系统设置开启 Akashic 无障碍服务")
                }
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
                if (a11y == null) error("无障碍服务未开启")
                val dm = context.resources.displayMetrics
                val cx = dm.widthPixels / 2f
                val h = dm.heightPixels.toFloat()
                when (dir) {
                    "down" -> a11y.performSwipe(dispId, cx, h * 0.75f, cx, h * 0.25f, 320L)
                    "up" -> a11y.performSwipe(dispId, cx, h * 0.25f, cx, h * 0.75f, 320L)
                    "left" -> a11y.performSwipe(dispId, dm.widthPixels * 0.8f, h / 2f, dm.widthPixels * 0.2f, h / 2f, 320L)
                    "right" -> a11y.performSwipe(dispId, dm.widthPixels * 0.2f, h / 2f, dm.widthPixels * 0.8f, h / 2f, 320L)
                }
                updatePip(PipMode.RUNNING, "滚动 ($dir)", null)
                "已执行滚动 ($dir)"
            }
            "swipe" -> {
                if (a11y == null) error("无障碍服务未开启")
                val x1 = payload.optInt("x", 500).toFloat()
                val y1 = payload.optInt("y", 1500).toFloat()
                val x2 = payload.optInt("end_x", 500).toFloat()
                val y2 = payload.optInt("end_y", 500).toFloat()
                val dur = payload.optLong("duration_ms", 300L)
                a11y.performSwipe(dispId, x1, y1, x2, y2, dur)
                "已滑动 ($x1,$y1)->($x2,$y2)"
            }
            "back" -> {
                a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "已执行返回键"
            }
            "press_key" -> {
                val key = payload.optString("key_code")
                if (key.equals("BACK", ignoreCase = true) || key == KeyEvent.KEYCODE_BACK.toString()) {
                    a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                } else if (key.equals("HOME", ignoreCase = true)) {
                    a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
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
                takeoverReason = null
                updatePip(PipMode.RUNNING, "后台恢复运行", null)
                "已从用户主屏收回控制权继续后台执行"
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
        val a11y = AkashicAccessibilityService.instance
        val dm = context.resources.displayMetrics
        val readout = a11y?.dumpScreen(dispId)
        if (readout != null) {
            lastNodeRefs = readout.nodeRefs
        }
        val pngBytes = if (includeScreenshot) {
            latestPreviewPng ?: a11y?.captureScreenshotPng(dispId)
        } else {
            null
        }
        if (pngBytes != null) {
            latestPreviewPng = pngBytes
            pipListener?.invoke(pipMode, pipLabel, pngBytes)
        }
        return JSONObject()
            .put("display_id", dispId)
            .put("width", dm.widthPixels.takeIf { it > 0 } ?: 1080)
            .put("height", dm.heightPixels.takeIf { it > 0 } ?: 2400)
            .put("rotation", 0)
            .put("package_name", readout?.packageName ?: activePackages.lastOrNull() ?: "com.akashic.shell")
            .put("activity_name", readout?.activityName ?: "MainActivity")
            .put("pip_state", pipMode.id)
            .put("audio_muted", true)
            .put("nodes", readout?.nodes ?: JSONArray())
            .put("takeover_reason", takeoverReason ?: JSONObject.NULL)
            .apply {
                if (pngBytes != null && pngBytes.isNotEmpty()) {
                    put("screenshot_b64", Base64.encodeToString(pngBytes, Base64.NO_WRAP))
                }
            }
    }
}
