package com.akashic.shell

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 极简原生看屏浮条（零 WebView，毫秒级秒开）：
 * - 初始只占屏幕底部一行输入框，自动弹出键盘；
 * - 截图压缩、页面文本采集与续聊查询全部后台静默执行，零阻塞；
 * - 发送后在输入框上方按内容自然撑开回复区（上限 40% 屏高），点「↗」进入完整 Akashic。
 */
open class AssistActivity : Activity() {
    private val bgPool = Executors.newCachedThreadPool()
    private val invokeSeq = AtomicInteger(0)
    private val streamConn = AtomicReference<HttpURLConnection?>(null)
    private val postConn = AtomicReference<HttpURLConnection?>(null)

    private lateinit var prefs: ShellPrefs
    private lateinit var card: LinearLayout
    private lateinit var replyBox: LinearLayout
    private lateinit var replyScroll: ScrollView
    private lateinit var replyText: TextView
    private lateinit var openFullBtn: TextView
    private lateinit var resumeBar: TextView
    private lateinit var inputEdit: EditText
    private lateinit var screenChip: TextView
    private lateinit var sendBtn: TextView

    @Volatile private var destroyed = false
    @Volatile private var includeScreen = true
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeSessionId: String? = null
    @Volatile private var capturedSource = "nav_handle"
    @Volatile private var capturedPkg = ""
    @Volatile private var capturedAppLabel = ""
    @Volatile private var capturedTime = ""
    @Volatile private var capturedUiTree = ""
    @Volatile private var capturedPngB64: String? = null
    @Volatile private var contextAttachedOnce = false
    @Volatile private var busy = false

    private val seenMsgIds = LinkedHashSet<String>()
    private val transcript = StringBuilder()

    protected open fun isTrustedInvokeAction(action: String?): Boolean =
        action == Intent.ACTION_ASSIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTrustedInvokeAction(intent?.action)) {
            finish()
            return
        }
        prefs = ShellPrefs(this)
        buildMinimalUi()
        prepareInvokeAsync(intent)
        inputEdit.postDelayed({
            if (!destroyed) {
                inputEdit.requestFocus()
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 40L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isTrustedInvokeAction(intent.action)) return
        setIntent(intent)
        resetForNewInvoke()
        prepareInvokeAsync(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::card.isInitialized) return
        applyThemeColors()
        val maxReplyH = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        replyScroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (replyScroll.height > maxReplyH) height = maxReplyH
        }
    }

    override fun onDestroy() {
        destroyed = true
        invokeSeq.incrementAndGet()
        streamConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        postConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        bgPool.shutdownNow()
        super.onDestroy()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildMinimalUi() {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(72, 0, 0, 0))
            setOnClickListener { finish() }
        }

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
        }

        // 1. 回复区（初始隐藏，发消息后按内容撑开）
        replyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(2), 0, dp(2), dp(8))
        }

        val replyTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val titleHint = TextView(this).apply {
            text = "Akashic"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        openFullBtn = TextView(this).apply {
            text = "继续 ↗"
            textSize = 12f
            setPadding(dp(8), dp(2), dp(4), dp(2))
            visibility = View.GONE
            setOnClickListener { openInMainApp(sessionId) }
        }
        replyTop.addView(titleHint)
        replyTop.addView(openFullBtn)

        replyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        replyText = TextView(this).apply {
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        replyScroll.addView(replyText)
        replyBox.addView(replyTop)
        replyBox.addView(replyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 2. 可选极细续聊提示（仅 10 分钟内有上次时出现）
        resumeBar = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(6))
            visibility = View.GONE
            setOnClickListener {
                val sid = resumeSessionId ?: return@setOnClickListener
                sessionId = sid
                visibility = View.GONE
                replyBox.visibility = View.VISIBLE
                openFullBtn.visibility = View.VISIBLE
                replyText.text = "已切到上次对话，继续发消息即可。"
                startSse(sid)
            }
        }

        // 3. 底部单行输入栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        screenChip = TextView(this).apply {
            text = "屏"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                includeScreen = !includeScreen
                updateChipVisual()
            }
        }

        inputEdit = EditText(this).apply {
            hint = "问问这一屏…"
            textSize = 15f
            background = null
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCurrentInput()
                    true
                } else false
            }
        }

        sendBtn = TextView(this).apply {
            text = "发送"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { sendCurrentInput() }
        }

        bar.addView(screenChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(inputEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(sendBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        card.addView(replyBox)
        card.addView(resumeBar)
        card.addView(bar)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        }
        root.addView(card, lp)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            view.setPadding(0, 0, 0, maxOf(ime.bottom, nav.bottom))
            insets
        }

        applyThemeColors()
        setContentView(root)
    }

    private fun applyThemeColors() {
        val dark = isNightMode()
        val bg = if (dark) Color.parseColor("#1E1D1A") else Color.parseColor("#F9F8F4")
        val stroke = if (dark) Color.parseColor("#35332E") else Color.parseColor("#DDD9CE")
        val ink = if (dark) Color.parseColor("#E2DFD7") else Color.parseColor("#292622")
        val muted = if (dark) Color.parseColor("#9A968E") else Color.parseColor("#736F68")
        val accent = if (dark) Color.parseColor("#A4C1E0") else Color.parseColor("#2D4A6A")
        val onAccent = if (dark) Color.parseColor("#102031") else Color.parseColor("#FBFAF6")

        card.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), stroke)
        }
        replyText.setTextColor(ink)
        openFullBtn.setTextColor(accent)
        resumeBar.setTextColor(muted)
        inputEdit.setTextColor(ink)
        inputEdit.setHintTextColor(muted)
        sendBtn.setTextColor(onAccent)
        sendBtn.background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(14).toFloat()
        }
        updateChipVisual()
    }

    private fun updateChipVisual() {
        val dark = isNightMode()
        val activeBg = if (dark) Color.parseColor("#2D3A47") else Color.parseColor("#E3EBF3")
        val activeFg = if (dark) Color.parseColor("#A4C1E0") else Color.parseColor("#2D4A6A")
        val idleBg = if (dark) Color.parseColor("#282622") else Color.parseColor("#EFECE4")
        val idleFg = if (dark) Color.parseColor("#8A867E") else Color.parseColor("#85817A")

        screenChip.text = if (includeScreen) "带屏" else "纯问"
        screenChip.setTextColor(if (includeScreen) activeFg else idleFg)
        screenChip.background = GradientDrawable().apply {
            setColor(if (includeScreen) activeBg else idleBg)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun resetForNewInvoke() {
        streamConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        postConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        sessionId = null
        resumeSessionId = null
        contextAttachedOnce = false
        includeScreen = true
        busy = false
        seenMsgIds.clear()
        transcript.setLength(0)
        replyBox.visibility = View.GONE
        openFullBtn.visibility = View.GONE
        resumeBar.visibility = View.GONE
        replyText.text = ""
        updateChipVisual()
    }

    private fun prepareInvokeAsync(intent: Intent?) {
        val mySeq = invokeSeq.incrementAndGet()
        capturedSource = when (intent?.action) {
            ACTION_LAUNCH_CONTEXTUAL_SEARCH -> "nav_handle"
            Intent.ACTION_ASSIST -> "assistant"
            else -> "assistant"
        }
        capturedTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val extraBitmap = extractBitmap(intent)
        val visiblePkgs = intent?.getStringArrayListExtra(EXTRA_VISIBLE_PACKAGE_NAMES)
        capturedPkg = visiblePkgs?.firstOrNull { it != packageName } ?: ""
        capturedAppLabel = resolveAppLabel(capturedPkg)
        capturedPngB64 = null
        capturedUiTree = ""

        // 1. 后台静默编码截图与提取无障碍文本，绝不阻塞弹出
        bgPool.execute {
            if (destroyed || invokeSeq.get() != mySeq) return@execute
            if (extraBitmap != null) {
                capturedPngB64 = encodeBitmapPngBase64(extraBitmap)
            }
            val a11y = AkashicAccessibilityService.instance
            if (a11y != null) {
                runCatching {
                    val readout = a11y.dumpScreen(Display.DEFAULT_DISPLAY, RefRegistry())
                    if (readout.packageName.isNotEmpty() && readout.packageName != packageName) {
                        if (capturedPkg.isEmpty()) {
                            capturedPkg = readout.packageName
                            capturedAppLabel = resolveAppLabel(capturedPkg)
                        }
                        val lines = LinkedHashSet<String>()
                        val arr = readout.tree.nodes
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val txt = obj.optString("text").ifBlank {
                                obj.optString("content_description").ifBlank { obj.optString("name") }
                            }.trim()
                            if (txt.isNotEmpty()) {
                                lines.add(txt)
                                if (lines.size >= 50) break
                            }
                        }
                        capturedUiTree = lines.joinToString("\n").take(2400)
                    }
                }
            }
        }

        // 2. 后台静默拉取 10 分钟内续聊候选（有就悄悄显示一行小字）
        val server = prefs.serverUrl?.trimEnd('/') ?: return
        bgPool.execute {
            if (destroyed || invokeSeq.get() != mySeq) return@execute
            runCatching {
                val res = getJson("$server/api/phone-assist/resume", server)
                val resume = res.optJSONObject("resume") ?: return@runCatching
                val sid = resume.optString("session_id")
                val title = resume.optString("title")
                if (sid.isNotBlank()) {
                    runOnUiThread {
                        if (!destroyed && invokeSeq.get() == mySeq && sessionId == null) {
                            resumeSessionId = sid
                            resumeBar.text = "接着上次：$title"
                            resumeBar.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun sendCurrentInput() {
        val text = inputEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        val server = prefs.serverUrl?.trimEnd('/')
        if (server.isNullOrEmpty()) {
            showReplyStatus("尚未配置服务器，请先打开 Akashic 主应用。")
            return
        }
        val mySeq = invokeSeq.get()
        busy = true
        sendBtn.alpha = 0.5f
        resumeBar.visibility = View.GONE
        replyBox.visibility = View.VISIBLE
        if (transcript.isEmpty()) {
            replyText.text = "思考中…"
        } else {
            replyText.text = "${transcript}\n\n思考中…"
        }

        val sendWithScreen = includeScreen && !contextAttachedOnce
        bgPool.execute {
            try {
                val payload = JSONObject()
                    .put("text", text)
                    .put("source", capturedSource)
                    .put("package_name", capturedPkg)
                    .put("app_label", capturedAppLabel)
                    .put("captured_at", capturedTime)
                    .put("include_screenshot", sendWithScreen)
                sessionId?.let { payload.put("session_id", it) }
                if (sendWithScreen) {
                    if (capturedUiTree.isNotEmpty()) payload.put("ui_tree_text", capturedUiTree)
                    capturedPngB64?.let { payload.put("screenshot_png_b64", it) }
                }

                val resp = postJson("$server/api/phone-assist/ask", server, payload)
                val sid = resp.optString("session_id")
                if (sid.isBlank()) throw IllegalStateException("服务器未返回 session_id")
                if (sendWithScreen) contextAttachedOnce = true

                val isFirstStream = (sessionId == null || sessionId != sid)
                sessionId = sid
                runOnUiThread {
                    if (destroyed || invokeSeq.get() != mySeq) return@runOnUiThread
                    inputEdit.setText("")
                    openFullBtn.visibility = View.VISIBLE
                    busy = false
                    sendBtn.alpha = 1f
                    if (isFirstStream) startSse(sid)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (destroyed || invokeSeq.get() != mySeq) return@runOnUiThread
                    busy = false
                    sendBtn.alpha = 1f
                    showReplyStatus("发送失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun startSse(sid: String) {
        val server = prefs.serverUrl?.trimEnd('/') ?: return
        val mySeq = invokeSeq.get()
        streamConn.getAndSet(null)?.let { runCatching { it.disconnect() } }

        bgPool.execute {
            val encoded = URLEncoder.encode(sid, "UTF-8")
            val conn = (URL("$server/api/phone-assist/stream/$encoded?after_seq=-1").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 0
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Origin", server)
                CookieManager.getInstance().getCookie(server)?.let { setRequestProperty("Cookie", it) }
            }
            streamConn.set(conn)
            try {
                if (conn.responseCode !in 200..299) {
                    runOnUiThread {
                        if (!destroyed && invokeSeq.get() == mySeq) {
                            showReplyStatus("连接回复流失败（HTTP ${conn.responseCode}）")
                        }
                    }
                    return@execute
                }
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var eventName = ""
                    while (!destroyed && invokeSeq.get() == mySeq) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim()
                        } else if (line.startsWith("data:") && eventName == "message") {
                            val jsonStr = line.substring(5).trim()
                            runCatching { JSONObject(jsonStr) }.getOrNull()?.let { obj ->
                                handleStreamMessage(mySeq, obj)
                            }
                        } else if (line.isEmpty()) {
                            eventName = ""
                        }
                    }
                }
            } catch (_: Throwable) {
                // 正常退出或断连不刷屏
            } finally {
                streamConn.compareAndSet(conn, null)
                conn.disconnect()
            }
        }
    }

    private fun handleStreamMessage(mySeq: Int, obj: JSONObject) {
        val mid = obj.optString("message_id")
        if (mid.isBlank()) return
        val role = obj.optString("role")
        val text = obj.optString("text").trim()
        val finish = obj.optString("finish")
        runOnUiThread {
            if (destroyed || invokeSeq.get() != mySeq) return@runOnUiThread
            if (!seenMsgIds.add(mid)) return@runOnUiThread
            if (role == "assistant" && text.isNotEmpty()) {
                if (transcript.isNotEmpty()) transcript.append("\n\n")
                transcript.append(text)
                replyText.text = if (finish == "continue") "${transcript}\n\n执行中…" else transcript.toString()
                constrainReplyHeight()
            } else if (role == "assistant" && finish == "continue") {
                val base = if (transcript.isNotEmpty()) "${transcript}\n\n" else ""
                replyText.text = "${base}执行工具中…"
            } else if (role == "control" && text.isNotEmpty()) {
                val base = if (transcript.isNotEmpty()) "${transcript}\n\n" else ""
                replyText.text = "${base}$text"
            }
        }
    }

    private fun constrainReplyHeight() {
        val maxH = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        replyScroll.post {
            if (destroyed) return@post
            val lp = replyScroll.layoutParams
            if (replyText.height > maxH && lp.height != maxH) {
                lp.height = maxH
                replyScroll.layoutParams = lp
            }
            replyScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showReplyStatus(msg: String) {
        replyBox.visibility = View.VISIBLE
        replyText.text = msg
    }

    @Suppress("DEPRECATION")
    private fun extractBitmap(intent: Intent?): Bitmap? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SCREENSHOT, Bitmap::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_SCREENSHOT) as? Bitmap
        }
    }

    private fun encodeBitmapPngBase64(bitmap: Bitmap): String? {
        val maxW = 720
        val maxH = 1600
        val scale = minOf(
            1f,
            maxW.toFloat() / bitmap.width.coerceAtLeast(1),
            maxH.toFloat() / bitmap.height.coerceAtLeast(1),
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_UPLOAD_PNG_BYTES) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun resolveAppLabel(pkg: String): String {
        if (pkg.isBlank()) return "当前屏幕"
        return runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    private fun getJson(urlStr: String, origin: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Origin", origin)
            CookieManager.getInstance().getCookie(origin)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return JSONObject(readBoundedUtf8(conn.inputStream, 16 * 1024))
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(urlStr: String, origin: String, payload: JSONObject): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Origin", origin)
            CookieManager.getInstance().getCookie(origin)?.let { setRequestProperty("Cookie", it) }
        }
        postConn.set(conn)
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readBoundedUtf8(it, 64 * 1024) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")
            return JSONObject(text)
        } finally {
            postConn.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun readBoundedUtf8(stream: java.io.InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            val keep = minOf(n, maxBytes - total)
            if (keep > 0) {
                out.write(buf, 0, keep)
                total += keep
            }
            if (total >= maxBytes) break
        }
        return out.toString("UTF-8")
    }

    private fun openInMainApp(sid: String?) {
        val next = Intent(this, MainActivity::class.java).apply {
            action = if (sid.isNullOrBlank()) Intent.ACTION_MAIN else Notifications.ACTION_OPEN_SESSION
            if (!sid.isNullOrBlank()) putExtra(Notifications.EXTRA_SESSION, sid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(next)
        finish()
    }

    companion object {
        const val ACTION_LAUNCH_CONTEXTUAL_SEARCH = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
        const val EXTRA_SCREENSHOT = "android.app.contextualsearch.extra.SCREENSHOT"
        const val EXTRA_VISIBLE_PACKAGE_NAMES = "android.app.contextualsearch.extra.VISIBLE_PACKAGE_NAMES"
        private const val MAX_UPLOAD_PNG_BYTES = 8 * 1024 * 1024
    }
}
