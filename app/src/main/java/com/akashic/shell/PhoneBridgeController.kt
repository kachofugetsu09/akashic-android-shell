package com.akashic.shell

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
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
import java.util.concurrent.locks.ReentrantLock

// 端侧后台控制桥：向 Akashic 服务端维持 /api/phone/stream 长连接，管理 UID 2000 特权 VirtualDisplay 与右下角画中画小窗。
object PhoneBridgeController {
    private const val TAG = "AkashicPhoneBridge"
    // 服务端每 10s 发心跳；25s 收不到就认定长连接已失效并重连。
    private const val READ_TIMEOUT_MS = 25_000
    private const val REPLY_READ_TIMEOUT_MS = 30_000
    private const val REPLY_ATTEMPTS = 3
    private const val RPC_WAKE_LOCK_MS = 120_000L
    private const val PREVIEW_INTERVAL_MS = 500L
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    private enum class PhoneAction {
        LAUNCH_APP, TAP, TYPE_TEXT, CLEAR_TEXT, SCROLL, SWIPE, BACK, PRESS_KEY,
        OPEN_LINK, SHARE_TEXT, HAND_TO_USER, RESUME_FROM_USER, WAIT,
    }

    private data class ConnectionOwner(val server: String, val connectionId: String)

    @Volatile private var activeOwner: ConnectionOwner? = null
    val connected: Boolean get() = activeOwner != null
    @Volatile var connectionError: String? = null
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

    var pipListener: ((PipMode, String, ByteArray?) -> Unit)? = null

    @Volatile private var latestAppContext: Context? = null
    private var worker: Thread? = null
    @Volatile private var runToken: Any? = null
    private val effectLock = ReentrantLock()
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val wake = Any()

    private var displayThread: HandlerThread? = null
    private var displayHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var localVirtualDisplay: VirtualDisplay? = null
    private val activePackages = LinkedHashSet<String>()
    private data class NodeSnapshot(
        val ref: String,
        val owner: ConnectionOwner,
        val displayId: Int,
        val displayGeneration: Any,
        val nodes: Map<Int, AccessibilityNodeInfo>,
    )
    private data class CapturedScreen(val json: JSONObject, val nodes: Map<Int, AccessibilityNodeInfo>)

    @Volatile private var nodeSnapshot: NodeSnapshot? = null
    @Volatile private var displayGeneration = Any()
    // 虚拟屏最新一帧（行宽含 stride 填充）；截图按需编码，不在每帧上编码整屏 PNG。
    private val frameLock = Any()
    private var frameBitmap: Bitmap? = null
    private var frameSize = 0 to 0
    private var frameSeq = 0L
    private var encodedSeq = -1L
    private var encodedPng: ByteArray? = null
    // 最近的端侧失败（回传失败等），随下一次 ack 报给服务端。
    private val recentErrors = ArrayDeque<String>()
    private val handledRequests = LinkedHashSet<String>()

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
        if (runToken != null && worker?.isAlive == true) {
            synchronized(wake) { (wake as Object).notifyAll() }
            return
        }
        val token = Any()
        runToken = token
        worker = Thread({ loop(appContext, token) }, "AkashicPhoneBridge").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        runToken = null
        activeConnection?.disconnect()
        synchronized(wake) { (wake as Object).notifyAll() }
        releaseVirtualDisplay()
        activeOwner = null
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
        synchronized(frameLock) {
            pipMode = mode
            pipLabel = label
            // DONE 可保留刚释放屏幕的末帧，其它模式不能借用已退役 reader 的图。
            if (imageReader == null && mode != PipMode.DONE) latestPreviewPng = null
            if (png != null) latestPreviewPng = png
            pipListener?.invoke(mode, label, latestPreviewPng)
        }
        ShellStatus.notifyChanged()
    }

    fun currentPipState(): Triple<PipMode, String, ByteArray?> = synchronized(frameLock) {
        Triple(pipMode, pipLabel, latestPreviewPng)
    }

    private fun ensureVirtualDisplay(context: Context): Int {
        virtualDisplayId?.let { currentId ->
            if (isTrustedVirtualDisplay && ShellHelperHub.shell != null) return currentId
            // 助手断开后，清理已失去 owner 的显示器引用。
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
            setFrameReader(reader)
            var lastPreviewMs = 0L
            reader.setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (error: IllegalStateException) {
                    // release 可与 acquire 并发；只有已退役 reader 的关闭异常可忽略。
                    if (synchronized(frameLock) { imageReader === r }) {
                        recordError("后台帧读取失败: ${error.message}")
                    }
                    return@setOnImageAvailableListener
                } ?: return@setOnImageAvailableListener
                try {
                    if (!copyFrame(r, img, w, h)) return@setOnImageAvailableListener
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPreviewMs >= PREVIEW_INTERVAL_MS || latestPreviewPng == null) {
                        lastPreviewMs = now
                        encodePreview(r)?.let { publishPreview(r, it) }
                    }
                } catch (error: RuntimeException) {
                    if (synchronized(frameLock) { imageReader === r }) {
                        recordError("后台帧处理失败: ${error.message}")
                    }
                } finally {
                    try {
                        img.close()
                    } catch (error: IllegalStateException) {
                        if (synchronized(frameLock) { imageReader === r }) {
                            recordError("后台帧关闭失败: ${error.message}")
                        }
                    }
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
                    virtualDisplayId = id
                    isTrustedVirtualDisplay = true
                    return id
                }
            }

            setFrameReader(null)
            reader.close()
            ht.quitSafely()
            error("后台手机控制未就绪：请连接 UID 2000 助手并确认设备支持独立显示器；未接管主屏")
        }
    }

    // 电源状态未知或无法独立唤醒时保留任务并报错，不借主屏唤醒或重建掩盖失败。
    private fun ensureAwakeDisplay(context: Context): Int {
        val id = ensureVirtualDisplay(context)
        val sh = ShellHelperHub.shell
        check(isTrustedVirtualDisplay && id > Display.DEFAULT_DISPLAY && sh != null) {
            "后台显示器未就绪，未接管主屏"
        }
        check(sh.keepAwake(id)) { "后台显示器无法独立唤醒；已保留当前任务，未唤醒主屏" }
        return id
    }

    private fun releaseVirtualDisplay(): ByteArray? {
        return synchronized(this) {
            // 先撤销帧 owner，旧回调和锁外 PNG 编码完成后也不能重新发布。
            val retiredReader: ImageReader?
            val finalPreview: ByteArray?
            synchronized(frameLock) {
                retiredReader = imageReader
                finalPreview = if (retiredReader != null) latestPreviewPng else null
                setFrameReader(null)
            }
            val id = virtualDisplayId
            if (isTrustedVirtualDisplay && id != null && id > 0) {
                runCatching { ShellHelperHub.shell?.releaseDisplay(id) }
            }
            localVirtualDisplay?.release()
            localVirtualDisplay = null
            virtualDisplayId = null
            isTrustedVirtualDisplay = false
            retiredReader?.close()
            displayThread?.quitSafely()
            displayThread = null
            displayHandler = null
            activePackages.clear()
            nodeSnapshot = null
            displayGeneration = Any()
            takeoverReason = null
            takeoverPackage = null
            finalPreview
        }
    }

    private fun loop(context: Context, token: Any) {
        val prefs = ShellPrefs(context)
        var attempts = 0
        while (runToken === token) {
            val server = prefs.serverUrl
            if (server == null || !prefs.phoneUseEnabled) break
            val established = connectOnce(context, server, token)
            ShellStatus.notifyChanged()
            if (runToken !== token) break
            val waitMs = if (established) {
                attempts = 0
                1_000L
            } else {
                val ms = BACKOFF_MS[minOf(attempts, BACKOFF_MS.lastIndex)]
                attempts++
                ms
            }
            synchronized(wake) {
                if (runToken === token) (wake as Object).wait(waitMs)
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
            .put("supports_ack", true)
            .put("supports_connection_id", true)
            .put("supports_snapshot_ref", true)
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
        val owner = activeOwner ?: return
        Thread({
            val frame = currentStateJson(ctx)
                .put("type", "state")
                .put("device_id", deviceId(ctx))
            postReply(owner, null, frame)
        }, "AkashicPhoneStatePush").start()
    }

    private fun connectOnce(context: Context, server: String, token: Any): Boolean {
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
        synchronized(this) {
            if (runToken !== token) {
                conn.disconnect()
                return false
            }
            activeConnection = conn
        }
        var established = false
        var owner: ConnectionOwner? = null
        try {
            val helloBytes = buildHello(context).toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(helloBytes) }
            if (conn.responseCode != 200) {
                reportConnectionError(token, conn, "服务端拒绝手机连接（HTTP ${conn.responseCode}），请确认 Shell 与插件版本匹配")
                return false
            }
            var event = ""
            val data = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (runToken === token) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                if (event == "welcome") {
                                    check(owner == null) { "服务端重复发送 welcome，请升级手机控制插件" }
                                    val welcome = JSONObject(data.toString())
                                    val id = welcome.opt("connection_id")
                                    check(id is String && id.isNotBlank() && id.length <= 128) {
                                        "服务端未提供有效 connection_id，请升级手机控制插件至 0.2.0 或更新版本"
                                    }
                                    check(welcome.optString("device_id") == deviceId(context)) { "服务端 welcome 的设备身份不匹配" }
                                    synchronized(this) {
                                        if (runToken !== token || activeConnection !== conn) return false
                                        owner = ConnectionOwner(server, id)
                                        nodeSnapshot = null
                                        activeOwner = owner
                                        connectionError = null
                                    }
                                    established = true
                                    ShellStatus.notifyChanged()
                                } else {
                                    val current = checkNotNull(owner) { "服务端没有先发送 welcome，请升级手机控制插件" }
                                    handleSseEvent(context, current, event, data.toString())
                                }
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
        } catch (error: IllegalStateException) {
            reportConnectionError(token, conn, error.message ?: "手机连接协议错误")
            recordError(error.message ?: "手机连接协议错误")
        } catch (error: org.json.JSONException) {
            reportConnectionError(token, conn, "服务端手机连接数据无效，请确认 Shell 与插件版本匹配")
            recordError("phone stream JSON error: ${error.message}")
        } finally {
            synchronized(this) {
                if (activeOwner === owner) {
                    nodeSnapshot = null
                    activeOwner = null
                }
                if (activeConnection === conn) activeConnection = null
            }
            conn.disconnect()
            ShellStatus.notifyChanged()
        }
        return established
    }

    // 停止后的旧连接错误不能覆盖新 worker 的状态。
    @Synchronized
    private fun reportConnectionError(token: Any, connection: HttpURLConnection, message: String) {
        if (runToken !== token || activeConnection !== connection) return
        connectionError = message
        ShellStatus.notifyChanged()
    }

    private fun handleSseEvent(context: Context, owner: ConnectionOwner, event: String, rawData: String) {
        if (event == "heartbeat") return
        val json = JSONObject(rawData)
        if (json.optString("type") != "request") return
        val requestId = json.getString("request_id")
        check(requestId.isNotBlank() && requestId.length <= 128) { "服务端请求 ID 无效" }
        val op = json.getString("op")
        val payload = json.getJSONObject("payload")
        if (!markHandled(requestId)) return
        val cookie = CookieManager.getInstance().getCookie(owner.server)
        Thread({ runRpc(context, owner, cookie, requestId, op, payload) }, "AkashicPhoneRpc-$requestId").start()
    }

    // 熄屏时 CPU 随时可能挂起：从收到请求到结果送达全程持 partial wake lock。
    private fun runRpc(context: Context, owner: ConnectionOwner, cookie: String?, requestId: String, op: String, payload: JSONObject) {
        if (activeOwner !== owner) return
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "akashic:phone-rpc")
            .apply { setReferenceCounted(false); acquire(RPC_WAKE_LOCK_MS) }
        try {
            // 1. 先回 ack，服务端据此区分"请求没送到"和"执行或回传慢"
            val ack = JSONObject()
                .put("type", "ack")
                .put("device_id", deviceId(context))
                .put("request_id", requestId)
                .put("recent_errors", drainRecentErrors())
            if (!postReply(owner, cookie, ack, attempts = 1) || activeOwner !== owner) return
            // 2. 执行并回传结果，失败重试
            val started = SystemClock.uptimeMillis()
            val reply = executeRpc(context, owner, requestId, op, payload)
            val bytes = reply.toString().toByteArray(Charsets.UTF_8)
            Log.i(TAG, "rpc $requestId $op executed in ${SystemClock.uptimeMillis() - started}ms, reply ${bytes.size / 1024}KB")
            postReply(owner, cookie, reply, attempts = REPLY_ATTEMPTS)
        } finally {
            lock.release()
        }
    }

    // 同一请求只执行一次，避免服务端重发或重复事件导致点击两次。
    private fun markHandled(requestId: String): Boolean = synchronized(handledRequests) {
        if (!handledRequests.add(requestId)) return false
        while (handledRequests.size > 64) handledRequests.remove(handledRequests.first())
        true
    }

    private fun recordError(message: String) {
        Log.w(TAG, message)
        synchronized(recentErrors) {
            recentErrors.addLast(message)
            while (recentErrors.size > 10) recentErrors.removeFirst()
        }
    }

    private fun drainRecentErrors(): JSONArray = synchronized(recentErrors) {
        JSONArray(recentErrors.toList()).also { recentErrors.clear() }
    }

    private fun postReply(owner: ConnectionOwner, cookie: String?, body: JSONObject, attempts: Int = 1): Boolean {
        body.put("connection_id", owner.connectionId)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val kind = "${body.optString("type")} ${body.optString("request_id")}".trim()
        repeat(attempts) { attempt ->
            val started = SystemClock.uptimeMillis()
            try {
                val conn = Server.open("${owner.server}/api/phone/reply", readTimeoutMs = REPLY_READ_TIMEOUT_MS)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", owner.server)
                val resolvedCookie = cookie ?: CookieManager.getInstance().getCookie(owner.server)
                resolvedCookie?.let { conn.setRequestProperty("Cookie", it) }
                try {
                    conn.outputStream.use { it.write(bytes) }
                    val code = conn.responseCode
                    val elapsed = SystemClock.uptimeMillis() - started
                    if (code in 200..299) {
                        if (elapsed > 3_000L) recordError("postReply $kind slow: ${bytes.size / 1024}KB in ${elapsed}ms")
                        return true
                    }
                    recordError("postReply $kind HTTP $code after ${elapsed}ms (attempt ${attempt + 1})")
                    if (code in 400..499) return false
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                val elapsed = SystemClock.uptimeMillis() - started
                recordError("postReply $kind ${t.javaClass.simpleName}: ${t.message} after ${elapsed}ms, ${bytes.size / 1024}KB (attempt ${attempt + 1})")
            }
        }
        return false
    }

    private fun executeRpc(context: Context, owner: ConnectionOwner, requestId: String, op: String, payload: JSONObject): JSONObject {
        val devId = deviceId(context)
        val response = JSONObject()
            .put("type", "response")
            .put("device_id", devId)
            .put("request_id", requestId)

        if (!effectLock.tryLock()) {
            return response.put("ok", false).put("error", "PHONE_BUSY: 上一个手机操作仍在执行；本请求没有排队或执行，请稍后重新观察")
        }
        val timing = JSONObject()
        var mark = SystemClock.uptimeMillis()
        fun lap(name: String) {
            val now = SystemClock.uptimeMillis()
            timing.put(name, now - mark)
            mark = now
        }
        try {
            check(activeOwner === owner) { "PHONE_STALE_CONNECTION: 请求所属连接已失效，未开始操作" }
            when (op) {
                "observe" -> {
                    val dispId = ensureAwakeDisplay(context)
                    lap("awake")
                    updatePip(if (takeoverReason != null) PipMode.NEEDS_YOU else PipMode.RUNNING, "正在观察屏幕", null)
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    val generation = displayGeneration
                    val captured = buildScreenObject(context, dispId, wantImg)
                    val screen = publishScreen(owner, requestId, dispId, generation, captured)
                    lap("screen")
                    response.put("ok", true).put("result", screen).put("state", currentStateJson(context))
                }
                "action" -> {
                    val act = readAction(payload)
                    if (act == PhoneAction.TYPE_TEXT || act == PhoneAction.CLEAR_TEXT ||
                        (act == PhoneAction.TAP && !payload.isNull("node_id") && payload.has("node_id"))) {
                        checkNodeSnapshot(owner, payload)
                    }
                    val dispId = if (act == PhoneAction.HAND_TO_USER) virtualDisplayId ?: Display.INVALID_DISPLAY else ensureAwakeDisplay(context)
                    lap("awake")
                    val generation = displayGeneration
                    val result = runAction(context, dispId, act, payload)
                    val textReadBack = result.text?.let { matchesText(it, dispId) } == true
                    lap("action")
                    val wantImg = payload.optBoolean("include_screenshot", false)
                    val settleMs = when {
                        act == PhoneAction.LAUNCH_APP -> 1_100L
                        textReadBack && !wantImg -> 0L
                        else -> 480L
                    }
                    SystemClock.sleep(settleMs)
                    result.text?.let { text ->
                        check(matchesText(text, dispId)) {
                            "PHONE_INPUT_NOT_CONFIRMED: 未能在原后台输入框读回完整目标值；内容可能已部分改变，请重新观察，不要直接重试"
                        }
                    }
                    var captured = buildScreenObject(context, dispId, wantImg)
                    if (act == PhoneAction.LAUNCH_APP && (captured.json.optJSONArray("nodes")?.length() ?: 0) == 0) {
                        SystemClock.sleep(800L)
                        captured = buildScreenObject(context, dispId, wantImg)
                    }
                    val screen = publishScreen(owner, requestId, dispId, generation, captured)
                    lap("screen")
                    response.put("ok", true).put(
                        "result",
                        JSONObject().put("message", result.message).put("screen", screen),
                    ).put("state", currentStateJson(context))
                }
                "finish" -> {
                    val finalPreview = releaseVirtualDisplay()
                    updatePip(PipMode.DONE, "已完成", finalPreview)
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
                else -> error("未知端侧操作: $op")
            }
        } catch (t: Throwable) {
            nodeSnapshot = null
            response.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
        } finally {
            effectLock.unlock()
        }
        val screen = response.optJSONObject("result")?.let { it.optJSONObject("screen") ?: it }
        screen?.optJSONArray("nodes")?.let { timing.put("nodes", it.length()) }
        screen?.optString("screenshot_b64")?.takeIf { it.isNotEmpty() }?.let { timing.put("png_kb", it.length * 3 / 4 / 1024) }
        return response.put("timing", timing)
    }

    private data class TextChange(val target: AccessibilityNodeInfo, val value: String)
    private data class ActionResult(val message: String, val text: TextChange?)

    /** 执行动作，只把实时读回一致的输入标记为已确认。 */
    private fun runAction(context: Context, dispId: Int, action: PhoneAction, payload: JSONObject): ActionResult {
        val sh = ShellHelperHub.shell
        val a11y = AkashicAccessibilityService.instance
        val backgroundDisplay = sh != null && isTrustedVirtualDisplay && dispId > Display.DEFAULT_DISPLAY
        check(backgroundDisplay || action == PhoneAction.HAND_TO_USER) { "后台显示器已失效，未接管主屏" }
        var textChange: TextChange? = null
        val message = when (action) {
            PhoneAction.LAUNCH_APP -> {
                val pkg = payload.getString("package_name")
                val code = checkNotNull(sh) { "后台助手未连接" }.startApp(dispId, pkg)
                if (code == 2) error("手机上未找到可启动的应用: $pkg")
                if (code == 4) error("该应用无法证明可安全创建独立实例，请用户显式移交")
                check(code == 0) { "后台虚拟屏启动 $pkg 失败 (code=$code)" }
                synchronized(this) { activePackages.add(pkg) }
                updatePip(PipMode.RUNNING, "后台运行 $pkg", null)
                "已向后台显示器 (#$dispId) 提交启动应用 $pkg 的请求"
            }
            PhoneAction.TAP -> {
                val nodeId = payload.optInt("node_id", -1)
                val x: Int
                val y: Int
                if (nodeId > 0) {
                    val node = nodeForAction(dispId, nodeId)
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    x = bounds.centerX()
                    y = bounds.centerY()
                } else {
                    x = payload.getInt("x")
                    y = payload.getInt("y")
                }
                check(sh != null && sh.injectTap(dispId, x, y)) { "后台点击未被接受，请重新观察屏幕" }
                updatePip(PipMode.RUNNING, "点击 ($x, $y)", null)
                "已在后台显示器点击 ($x, $y)"
            }
            PhoneAction.TYPE_TEXT, PhoneAction.CLEAR_TEXT -> {
                val text = if (action == PhoneAction.CLEAR_TEXT) "" else payload.getString("text")
                val nodeId = payload.optInt("node_id", -1)
                val id = if (nodeId > 0) nodeId else checkNotNull(nodeSnapshot).nodes.entries.firstOrNull {
                    it.value.isEditable && it.value.isFocused
                }?.key ?: error("当前没有聚焦输入框，请观察并指定 node_id")
                val target = nodeForAction(dispId, id)
                check(target.isEditable) { "目标不是可编辑输入框" }
                check(a11y != null && a11y.setNodeText(target, text)) {
                    "输入框拒绝 ACTION_SET_TEXT；未覆盖用户剪贴板，请使用应用支持的输入方式"
                }
                textChange = TextChange(target, text)
                updatePip(PipMode.RUNNING, "输入文本", null)
                if (action == PhoneAction.CLEAR_TEXT) "已清空输入框" else "已输入文本 (${text.length} 字)"
            }
            PhoneAction.SCROLL -> {
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
                check(sh != null && sh.injectSwipe(dispId, x1, y1, x2, y2, 320)) { "后台滚动未被接受" }
                updatePip(PipMode.RUNNING, "滚动 ($dir)", null)
                "已执行滚动 ($dir)"
            }
            PhoneAction.SWIPE -> {
                val x1 = payload.getInt("x")
                val y1 = payload.getInt("y")
                val x2 = payload.getInt("end_x")
                val y2 = payload.getInt("end_y")
                val dur = payload.optInt("duration_ms", 300)
                check(sh != null && sh.injectSwipe(dispId, x1, y1, x2, y2, dur)) { "后台滑动未被接受" }
                "已滑动 ($x1,$y1)->($x2,$y2)"
            }
            PhoneAction.BACK -> {
                check(sh != null && sh.injectKey(dispId, KeyEvent.KEYCODE_BACK)) { "后台返回键未被接受" }
                "已执行返回键"
            }
            PhoneAction.PRESS_KEY -> {
                val key = payload.getString("key_code")
                val code = appKeyCode(key)
                check(sh != null && sh.injectKey(dispId, code)) { "后台按键未被接受" }
                "已向后台应用发送按键 $key"
            }
            PhoneAction.OPEN_LINK -> {
                val uri = payload.optString("uri")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!payload.isNull("package_name")) payload.optString("package_name").takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
                checkIntentStarted(sh, dispId, intent)
                "已向后台显示器提交打开链接请求 $uri"
            }
            PhoneAction.SHARE_TEXT -> {
                val pkg = payload.optString("package_name")
                val text = payload.optString("text")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (pkg.isNotEmpty()) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                checkIntentStarted(sh, dispId, intent)
                "已向后台显示器提交分享界面请求 $pkg"
            }
            PhoneAction.HAND_TO_USER -> {
                val reason = payload.optString("reason", "需要您在手机主屏幕确认操作")
                takeoverReason = reason
                takeoverPackage = activePackages.lastOrNull()
                updatePip(PipMode.NEEDS_YOU, "点此接管操作", null)
                Notifications.showTakeover(context, reason)
                "已挂起后台操作并通知用户点击右下角橙色呼吸小窗或通知接管 ($reason)"
            }
            PhoneAction.RESUME_FROM_USER -> {
                val pkg = takeoverPackage ?: activePackages.lastOrNull()
                if (pkg != null && sh != null && backgroundDisplay) {
                    runCatching { sh.moveTaskToDisplay(pkg, dispId) }
                }
                takeoverReason = null
                updatePip(PipMode.RUNNING, "后台恢复运行", null)
                "已从用户主屏收回控制权继续后台虚拟屏执行"
            }
            PhoneAction.WAIT -> {
                SystemClock.sleep(payload.optLong("duration_ms", 1000L))
                "已完成等待"
            }
        }
        return ActionResult(message, textChange)
    }

    /** 只把原后台节点的实时全文当作输入确认，hint 标志明确表示空输入。 */
    private fun matchesText(change: TextChange, displayId: Int): Boolean {
        val target = change.target
        return target.refresh() && target.isVisibleToUser && target.window?.displayId == displayId &&
            (if (target.isShowingHintText) "" else target.text?.toString()) == change.value
    }

    /** 先核对快照身份，再读取短编号；缺失引用不会借用最新快照。 */
    private fun checkNodeSnapshot(owner: ConnectionOwner, payload: JSONObject) {
        val ref = payload.opt("snapshot_ref")
        require(ref is String && ref.isNotBlank()) {
            "PHONE_SNAPSHOT_REF_REQUIRED: 节点操作必须携带 snapshot_ref，请升级 phone-use 至 0.3.0 并重新观察"
        }
        val snapshot = nodeSnapshot
        check(snapshot != null && snapshot.ref == ref && snapshot.owner === owner &&
            snapshot.displayId == virtualDisplayId && snapshot.displayGeneration === displayGeneration) {
            "PHONE_SNAPSHOT_STALE: 快照已过期，未执行节点操作，请重新观察"
        }
    }

    /** 只发布本次 RPC 的最终树，引用和节点映射共用一个不可变槽位。 */
    @Synchronized
    private fun publishScreen(owner: ConnectionOwner, requestId: String, displayId: Int,
                              generation: Any, captured: CapturedScreen): JSONObject {
        check(activeOwner === owner && displayId == (virtualDisplayId ?: Display.INVALID_DISPLAY) && generation === displayGeneration) {
            "PHONE_SNAPSHOT_STALE: 捕获期间连接或后台显示器已变化，请重新观察"
        }
        captured.json.put("snapshot_ref", requestId)
        nodeSnapshot = if (displayId <= Display.DEFAULT_DISPLAY || captured.nodes.isEmpty()) null else
            NodeSnapshot(requestId, owner, displayId, generation, captured.nodes)
        return captured.json
    }

    /** 节点必须仍可见且属于本次后台显示器，禁止用旧缓存操作用户主屏。 */
    private fun nodeForAction(displayId: Int, nodeId: Int): AccessibilityNodeInfo {
        val node = nodeSnapshot?.nodes?.get(nodeId) ?: error("PHONE_NODE_STALE: 节点不存在，请重新观察")
        check(node.refresh()) { "PHONE_NODE_STALE: 节点已失效，请重新观察" }
        check(node.window?.displayId == displayId && displayId > Display.DEFAULT_DISPLAY) {
            "PHONE_WRONG_DISPLAY: 节点已离开后台显示器，未执行操作"
        }
        check(node.isVisibleToUser) { "PHONE_NODE_STALE: 节点已不可见，请重新观察" }
        return node
    }

    private fun checkIntentStarted(shell: IAkashicShell?, displayId: Int, intent: Intent) {
        val code = checkNotNull(shell) { "后台助手未连接" }.startIntent(displayId, intent)
        check(code != 4) { "目标主屏任务无法安全创建独立实例，请用户显式移交" }
        check(code == 0) { "后台目标无法启动 (code=$code)" }
    }

    private fun appKeyCode(key: String): Int {
        val code = when (key.uppercase()) {
            "BACK" -> KeyEvent.KEYCODE_BACK
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            else -> key.toIntOrNull() ?: error("未知应用按键 $key")
        }
        val allowed = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
            code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            code in KeyEvent.KEYCODE_COMMA..KeyEvent.KEYCODE_PERIOD ||
            code == KeyEvent.KEYCODE_TAB || code == KeyEvent.KEYCODE_SPACE ||
            code in KeyEvent.KEYCODE_ENTER..KeyEvent.KEYCODE_SLASH ||
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
            code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN ||
            code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_PLUS,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_INSERT)
        check(allowed) { "后台仅支持应用文字和导航键；HOME、最近任务、音量及电源等系统控制由用户操作" }
        return code
    }

    /** 在创建显示器或输入之前校验动作参数，缺值不能静默变成别的动作。 */
    private fun readAction(payload: JSONObject): PhoneAction {
        val raw = payload.getString("action")
        val action = PhoneAction.entries.firstOrNull { it.name.lowercase() == raw }
            ?: error("未知手机动作: $raw")
        fun string(name: String) { require(payload.opt(name) is String) { "$name 必须是字符串" } }
        fun integer(name: String, range: IntRange) {
            val value = payload.opt(name)
            require(value is Int && value in range) { "$name 必须是范围 $range 内的整数" }
        }
        if (payload.has("node_id") && !payload.isNull("node_id")) integer("node_id", 1..Int.MAX_VALUE)
        if (payload.has("package_name") && !payload.isNull("package_name")) string("package_name")
        when (action) {
            PhoneAction.LAUNCH_APP -> { string("package_name"); require(payload.getString("package_name").isNotBlank()) { "package_name 不能为空" } }
            PhoneAction.TAP -> if (!payload.has("node_id") || payload.isNull("node_id")) { integer("x", 0..Int.MAX_VALUE); integer("y", 0..Int.MAX_VALUE) }
            PhoneAction.TYPE_TEXT -> { string("text"); require(payload.getString("text").isNotEmpty()) { "text 不能为空，请用 clear_text 清空" } }
            PhoneAction.SHARE_TEXT -> { string("text"); string("package_name"); require(payload.getString("package_name").isNotBlank() && payload.getString("text").isNotEmpty()) { "分享必须指定应用与非空文本" } }
            PhoneAction.SWIPE -> {
                listOf("x", "y", "end_x", "end_y").forEach { integer(it, 0..Int.MAX_VALUE) }
                if (payload.has("duration_ms")) integer("duration_ms", 10..10_000)
            }
            PhoneAction.SCROLL -> {
                string("direction")
                require(payload.getString("direction") in setOf("up", "down", "left", "right")) { "未知滚动方向" }
            }
            PhoneAction.PRESS_KEY -> { string("key_code"); appKeyCode(payload.getString("key_code")) }
            PhoneAction.OPEN_LINK -> { string("uri"); require(payload.getString("uri").isNotBlank()) { "uri 不能为空" } }
            PhoneAction.HAND_TO_USER -> { string("reason"); require(payload.getString("reason").isNotBlank()) { "移交原因不能为空" } }
            PhoneAction.WAIT -> if (payload.has("duration_ms")) integer("duration_ms", 10..10_000)
            else -> Unit
        }
        return action
    }

    /** raw 和编码缓存只属于当前 reader，释放或换代时一起失效。 */
    private fun setFrameReader(reader: ImageReader?) = synchronized(frameLock) {
        imageReader = reader
        frameBitmap?.recycle()
        frameBitmap = null
        frameSize = 0 to 0
        frameSeq = 0L
        encodedSeq = -1L
        encodedPng = null
        latestPreviewPng = null
    }

    private fun copyFrame(reader: ImageReader, image: Image, width: Int, height: Int): Boolean = synchronized(frameLock) {
        if (imageReader !== reader) return false
        val plane = image.planes[0]
        val strideWidth = plane.rowStride / plane.pixelStride
        val buf = frameBitmap?.takeIf { it.width == strideWidth && it.height == height }
            ?: Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888).also {
                frameBitmap?.recycle()
                frameBitmap = it
            }
        buf.copyPixelsFromBuffer(plane.buffer.rewind())
        frameSize = width to height
        frameSeq++
        true
    }

    private fun hasFrame(reader: ImageReader): Boolean = synchronized(frameLock) {
        imageReader === reader && frameBitmap != null
    }

    // 按需把最新一帧编码成整屏 PNG；同一帧只编码一次。
    private fun encodeFullPng(reader: ImageReader): ByteArray? {
        synchronized(frameLock) {
            if (imageReader !== reader) return null
            val src = frameBitmap ?: return null
            if (encodedSeq == frameSeq) return encodedPng
            val (w, h) = frameSize
            val full = Bitmap.createBitmap(src, 0, 0, w, h)
            val out = ByteArrayOutputStream()
            try {
                check(full.compress(Bitmap.CompressFormat.PNG, 90, out)) { "后台整屏 PNG 编码失败" }
                encodedSeq = frameSeq
                encodedPng = out.toByteArray()
                return encodedPng
            } finally {
                if (full !== src) full.recycle()
            }
        }
    }

    private fun encodePreview(reader: ImageReader): Pair<Long, ByteArray>? {
        var seq: Long
        val scaled = synchronized(frameLock) {
            if (imageReader !== reader) return null
            val src = frameBitmap ?: return null
            seq = frameSeq
            val (w, h) = frameSize
            val scale = 360f / w
            Bitmap.createBitmap(src, 0, 0, w, h, Matrix().apply { setScale(scale, scale) }, true)
        }
        val out = ByteArrayOutputStream()
        try {
            check(scaled.compress(Bitmap.CompressFormat.PNG, 85, out)) { "后台预览 PNG 编码失败" }
            return seq to out.toByteArray()
        } finally {
            scaled.recycle()
        }
    }

    /** 缩放和 PNG 编码可在锁外完成，发布前必须再次核对 reader 和帧。 */
    private fun publishPreview(reader: ImageReader, preview: Pair<Long, ByteArray>) = synchronized(frameLock) {
        if (imageReader !== reader || frameSeq != preview.first) return
        latestPreviewPng = preview.second
        if (pipMode != PipMode.HIDDEN) pipListener?.invoke(pipMode, pipLabel, preview.second)
    }

    private fun buildScreenObject(context: Context, dispId: Int, includeScreenshot: Boolean): CapturedScreen {
        val sh = ShellHelperHub.shell
        val a11y = AkashicAccessibilityService.instance
        val dm = context.resources.displayMetrics
        val readout = a11y?.dumpScreen(dispId)
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
            val reader = synchronized(frameLock) { imageReader }
            if (reader != null && !hasFrame(reader)) SystemClock.sleep(280L)
            if (includeScreenshot && reader != null) encodeFullPng(reader) ?: a11y?.captureScreenshotPng(dispId) else null
        } else {
            // 非 reader 来源的单次截图不写入虚拟屏缓存，也不以旧图掩盖失败。
            a11y?.captureScreenshotPng(Display.DEFAULT_DISPLAY)
        }
        val returnedPng = if (includeScreenshot) pngBytes else null

        val screen = JSONObject()
            .put("display_id", dispId)
            .put("width", dm.widthPixels.takeIf { it > 0 } ?: 1080)
            .put("height", dm.heightPixels.takeIf { it > 0 } ?: 2400)
            .put("rotation", 0)
            .put("package_name", resolvedPkg)
            .put("activity_name", resolvedAct)
            .put("pip_state", pipMode.id)
            .put("audio_muted", false)
            .put("nodes", readout?.nodes ?: JSONArray())
            .put("takeover_reason", takeoverReason ?: JSONObject.NULL)
            .apply {
                if (returnedPng != null && returnedPng.isNotEmpty()) {
                    put("screenshot_b64", Base64.encodeToString(returnedPng, Base64.NO_WRAP))
                }
            }
        return CapturedScreen(screen, readout?.nodeRefs ?: emptyMap())
    }
}
