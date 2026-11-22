package com.akashic.shell

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
 * 极致简约原生看屏浮条（零 WebView、零续聊条、毫秒级秒开）：
 * - 初始只占底部一行圆角输入框，自动弹出键盘；
 * - 拿到系统截图时，输入框左侧直接显示真实画面缩略图（点图原地看大图确认发什么，点角上 × 剥离）；
 * - 没拿到截图就不占位置；发送后在输入框上方按内容自然撑开，右上角只留 ↗ 与 ×。
 */
open class AssistActivity : Activity() {
    private val bgPool = Executors.newCachedThreadPool()
    private val invokeSeq = AtomicInteger(0)
    private val streamConn = AtomicReference<HttpURLConnection?>(null)
    private val postConn = AtomicReference<HttpURLConnection?>(null)

    private lateinit var prefs: ShellPrefs
    private lateinit var root: FrameLayout
    private lateinit var stack: LinearLayout
    private lateinit var previewCard: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewRemoveBtn: TextView
    private lateinit var previewCloseBtn: TextView
    private lateinit var card: LinearLayout
    private lateinit var replyBox: LinearLayout
    private lateinit var replyScroll: ScrollView
    private lateinit var replyText: TextView
    private lateinit var openFullBtn: TextView
    private lateinit var closeBtn: TextView
    private lateinit var thumbWrap: FrameLayout
    private lateinit var thumbView: ImageView
    private lateinit var thumbRemove: TextView
    private lateinit var inputEdit: EditText
    private lateinit var sendBtn: TextView

    @Volatile private var destroyed = false
    @Volatile private var includeScreen = true
    @Volatile private var sessionId: String? = null
    @Volatile private var capturedSource = "nav_handle"
    @Volatile private var capturedPkg = ""
    @Volatile private var capturedAppLabel = ""
    @Volatile private var capturedTime = ""
    @Volatile private var capturedUiTree = ""
    @Volatile private var capturedPngB64: String? = null
    @Volatile private var contextAttachedOnce = false
    @Volatile private var busy = false

    private var previewBitmap: Bitmap? = null
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
        prepareInvoke(intent)
        inputEdit.postDelayed({
            if (!destroyed) {
                inputEdit.requestFocus()
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 35L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isTrustedInvokeAction(intent.action)) return
        setIntent(intent)
        resetForNewInvoke()
        prepareInvoke(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::card.isInitialized) return
        applyThemeColors()
        constrainReplyHeight()
    }

    override fun onDestroy() {
        destroyed = true
        invokeSeq.incrementAndGet()
        streamConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        postConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        bgPool.shutdownNow()
        previewBitmap = null
        super.onDestroy()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun clipRounded(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    private fun buildMinimalUi() {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(56, 0, 0, 0))
            setOnClickListener {
                if (previewCard.visibility == View.VISIBLE) {
                    previewCard.visibility = View.GONE
                } else {
                    finish()
                }
            }
        }

        stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
        }

        // 1. 点击缩略图后原地展开的真实待发画面预览卡（默认隐藏）
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(8))
            isClickable = true
        }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = (resources.displayMetrics.heightPixels * 0.44f).toInt()
        }
        clipRounded(previewImage, dp(10).toFloat())

        val prevBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        previewCloseBtn = TextView(this).apply {
            text = "收起"
            textSize = 12f
            setPadding(dp(4), dp(4), dp(12), dp(4))
            setOnClickListener { previewCard.visibility = View.GONE }
        }
        val prevSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        previewRemoveBtn = TextView(this).apply {
            text = "去掉这张图"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { removeScreenshot() }
        }
        prevBar.addView(previewCloseBtn)
        prevBar.addView(prevSpacer)
        prevBar.addView(previewRemoveBtn)

        previewCard.addView(previewImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        previewCard.addView(prevBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 2. 底部极简悬浮卡片
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
        }

        // 回复区（初始隐藏，发送后按内容撑开）
        replyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(2), dp(8))
        }
        val replyTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        openFullBtn = TextView(this).apply {
            text = "↗"
            textSize = 15f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
            setOnClickListener { openInMainApp(sessionId) }
        }
        closeBtn = TextView(this).apply {
            text = "×"
            textSize = 17f
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { finish() }
        }
        replyTop.addView(openFullBtn)
        replyTop.addView(closeBtn)

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

        // 单行输入栏：真实缩略图（有图才显示） + 输入框 + 发送箭头
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        thumbWrap = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), dp(6), dp(2))
        }
        thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener {
                if (previewBitmap != null) {
                    previewCard.visibility = if (previewCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }
        clipRounded(thumbView, dp(7).toFloat())
        thumbRemove = TextView(this).apply {
            text = "×"
            textSize = 10f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setOnClickListener { removeScreenshot() }
        }
        thumbWrap.addView(thumbView, FrameLayout.LayoutParams(dp(30), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
        thumbWrap.addView(thumbRemove, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.END or Gravity.TOP))

        inputEdit = EditText(this).apply {
            hint = "问这一屏…"
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
            text = "↑"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { sendCurrentInput() }
        }

        bar.addView(thumbWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(inputEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginStart = dp(4)
        })

        card.addView(replyBox)
        card.addView(bar)

        stack.addView(previewCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(8)
        })
        stack.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        }
        root.addView(stack, lp)

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
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), stroke)
        }
        previewCard.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), stroke)
        }
        thumbRemove.setTextColor(bg)
        thumbRemove.background = GradientDrawable().apply {
            setColor(ink)
            cornerRadius = dp(99).toFloat()
        }
        previewCloseBtn.setTextColor(muted)
        previewRemoveBtn.setTextColor(accent)
        replyText.setTextColor(ink)
        openFullBtn.setTextColor(accent)
        closeBtn.setTextColor(muted)
        inputEdit.setTextColor(ink)
        inputEdit.setHintTextColor(muted)
        sendBtn.setTextColor(onAccent)
        sendBtn.background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(99).toFloat()
        }
    }

    private fun removeScreenshot() {
        includeScreen = false
        capturedPngB64 = null
        previewBitmap = null
        thumbWrap.visibility = View.GONE
        previewCard.visibility = View.GONE
    }

    private fun resetForNewInvoke() {
        streamConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        postConn.getAndSet(null)?.let { runCatching { it.disconnect() } }
        sessionId = null
        contextAttachedOnce = false
        includeScreen = true
        busy = false
        previewBitmap = null
        seenMsgIds.clear()
        transcript.setLength(0)
        replyBox.visibility = View.GONE
        openFullBtn.visibility = View.GONE
        previewCard.visibility = View.GONE
        thumbWrap.visibility = View.GONE
        replyText.text = ""
    }

    private fun prepareInvoke(intent: Intent?) {
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

        // 有真实截图立刻渲染到缩略图和预览图，不写任何文字占位
        if (extraBitmap != null) {
            previewBitmap = extraBitmap
            thumbView.setImageBitmap(extraBitmap)
            previewImage.setImageBitmap(extraBitmap)
            thumbWrap.visibility = View.VISIBLE
        } else {
            thumbWrap.visibility = View.GONE
        }

        // 截图压缩与无障碍文本采集在后台静默跑，零阻塞
        bgPool.execute {
            if (destroyed || invokeSeq.get() != mySeq) return@execute
            if (extraBitmap != null && includeScreen) {
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
    }

    private fun sendCurrentInput() {
        val text = inputEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        val server = prefs.serverUrl?.trimEnd('/')
        if (server.isNullOrEmpty()) {
            showReplyStatus("尚未配置服务器，请先打开主应用连接。")
            return
        }
        val mySeq = invokeSeq.get()
        busy = true
        sendBtn.alpha = 0.45f
        previewCard.visibility = View.GONE
        replyBox.visibility = View.VISIBLE
        replyText.text = if (transcript.isEmpty()) "思考中…" else "${transcript}\n\n思考中…"

        val sendWithScreen = includeScreen && !contextAttachedOnce
        val bitmapSnap = previewBitmap
        bgPool.execute {
            try {
                // 如果用户打字极快、后台还没编码完，这里原地补编一次
                val pngB64 = if (sendWithScreen) {
                    capturedPngB64 ?: bitmapSnap?.let(::encodeBitmapPngBase64)?.also { capturedPngB64 = it }
                } else null

                val payload = JSONObject()
                    .put("text", text)
                    .put("source", capturedSource)
                    .put("package_name", capturedPkg)
                    .put("app_label", capturedAppLabel)
                    .put("captured_at", capturedTime)
                    .put("include_screenshot", sendWithScreen && (pngB64 != null || capturedUiTree.isNotEmpty()))
                sessionId?.let { payload.put("session_id", it) }
                if (sendWithScreen) {
                    if (capturedUiTree.isNotEmpty()) payload.put("ui_tree_text", capturedUiTree)
                    if (pngB64 != null) payload.put("screenshot_png_b64", pngB64)
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
                    thumbWrap.visibility = View.GONE
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
                replyText.text = "${base}执行中…"
            } else if (role == "control" && text.isNotEmpty()) {
                val base = if (transcript.isNotEmpty()) "${transcript}\n\n" else ""
                replyText.text = "${base}$text"
            }
        }
    }

    private fun constrainReplyHeight() {
        val maxH = (resources.displayMetrics.heightPixels * 0.36f).toInt()
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
