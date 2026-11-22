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
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
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
 * 极致简约原生看屏浮条：
 * - 零 WebView、零续聊条、唤起时不自动弹键盘，点输入框再输入；
 * - 左侧真实画面缩略图（点图原地看大图确认发什么，点角上 × 剥离）；
 * - 点发送 0ms 乐观上屏（轻震动、立刻清输入框收键盘、问题直接上屏）；
 * - JPEG 轻量压缩上传 + SSE 实时逐字流式吐字。
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
    private lateinit var promptEcho: TextView
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
    @Volatile private var capturedImgB64: String? = null
    @Volatile private var contextAttachedOnce = false
    @Volatile private var busy = false

    private var previewBitmap: Bitmap? = null
    private val seenMsgIds = LinkedHashSet<String>()
    private val committedTranscript = StringBuilder()

    protected open fun isTrustedInvokeAction(action: String?): Boolean =
        action == Intent.ACTION_ASSIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTrustedInvokeAction(intent?.action)) {
            finish()
            return
        }
        prefs = ShellPrefs(this)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                @Suppress("DEPRECATION") WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        buildMinimalUi()
        prepareInvoke(intent)
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

    private fun animateCard() {
        val tr = AutoTransition().apply { duration = 140L }
        TransitionManager.beginDelayedTransition(stack, tr)
    }

    private fun buildMinimalUi() {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setBackgroundColor(Color.argb(56, 0, 0, 0))
            setOnClickListener {
                if (previewCard.visibility == View.VISIBLE) {
                    animateCard()
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

        // 1. 点击缩略图后原地展开的真实待发画面预览卡
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
            setOnClickListener {
                animateCard()
                previewCard.visibility = View.GONE
            }
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

        // 回复区（初始隐藏，点发送瞬间乐观展开）
        replyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(2), dp(8))
        }
        val replyTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        promptEcho = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener { finish() }
        }
        replyTop.addView(promptEcho)
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

        // 单行输入栏
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
                    animateCard()
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
            setOnClickListener {
                requestFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
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
        ).apply { bottomMargin = dp(8) })
        stack.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { setMargins(dp(10), 0, dp(10), dp(10)) }
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
        promptEcho.setTextColor(muted)
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

    private fun hideKeyboard() {
        root.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(inputEdit.windowToken, 0)
    }

    private fun removeScreenshot() {
        animateCard()
        includeScreen = false
        capturedImgB64 = null
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
        committedTranscript.setLength(0)
        replyBox.visibility = View.GONE
        openFullBtn.visibility = View.GONE
        previewCard.visibility = View.GONE
        thumbWrap.visibility = View.GONE
        promptEcho.text = ""
        replyText.text = ""
        inputEdit.hint = "问这一屏…"
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
        capturedImgB64 = null
        capturedUiTree = ""

        if (extraBitmap != null) {
            previewBitmap = extraBitmap
            thumbView.setImageBitmap(extraBitmap)
            previewImage.setImageBitmap(extraBitmap)
            thumbWrap.visibility = View.VISIBLE
        } else {
            thumbWrap.visibility = View.GONE
        }

        // 后台静默压缩为轻量 JPEG（~80KB）+ 读无障碍树，零阻塞弹出
        bgPool.execute {
            if (destroyed || invokeSeq.get() != mySeq) return@execute
            if (extraBitmap != null && includeScreen) {
                capturedImgB64 = encodeBitmapJpegBase64(extraBitmap)
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
        val sendWithScreen = includeScreen && !contextAttachedOnce
        val bitmapSnap = previewBitmap

        // 0ms 乐观上屏：轻震动、立刻清空输入框并收键盘、把刚问的话放到回复区顶行
        sendBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        animateCard()
        busy = true
        sendBtn.alpha = 0.45f
        inputEdit.setText("")
        inputEdit.hint = "继续追问…"
        hideKeyboard()
        previewCard.visibility = View.GONE
        if (sendWithScreen) thumbWrap.visibility = View.GONE
        promptEcho.text = text
        replyBox.visibility = View.VISIBLE
        replyText.text = if (committedTranscript.isEmpty()) "…" else "${committedTranscript}\n\n…"

        bgPool.execute {
            try {
                val imgB64 = if (sendWithScreen) {
                    capturedImgB64 ?: bitmapSnap?.let(::encodeBitmapJpegBase64)?.also { capturedImgB64 = it }
                } else null

                val payload = JSONObject()
                    .put("text", text)
                    .put("source", capturedSource)
                    .put("package_name", capturedPkg)
                    .put("app_label", capturedAppLabel)
                    .put("captured_at", capturedTime)
                    .put("include_screenshot", sendWithScreen && (imgB64 != null || capturedUiTree.isNotEmpty()))
                sessionId?.let { payload.put("session_id", it) }
                if (sendWithScreen) {
                    if (capturedUiTree.isNotEmpty()) payload.put("ui_tree_text", capturedUiTree)
                    if (imgB64 != null) payload.put("screenshot_png_b64", imgB64)
                }

                val resp = postJson("$server/api/phone-assist/ask", server, payload)
                val sid = resp.optString("session_id")
                if (sid.isBlank()) throw IllegalStateException("服务器未返回 session_id")
                if (sendWithScreen) contextAttachedOnce = true

                val isFirstStream = (sessionId == null || sessionId != sid)
                sessionId = sid
                runOnUiThread {
                    if (destroyed || invokeSeq.get() != mySeq) return@runOnUiThread
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
                    // 失败时把草稿还回输入框，绝不丢字
                    if (inputEdit.text.isNullOrEmpty()) inputEdit.setText(text)
                    if (sendWithScreen && previewBitmap != null) thumbWrap.visibility = View.VISIBLE
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
                        } else if (line.startsWith("data:")) {
                            val jsonStr = line.substring(5).trim()
                            if (eventName == "preview") {
                                runCatching { JSONObject(jsonStr) }.getOrNull()?.let { obj ->
                                    handlePreview(mySeq, obj)
                                }
                            } else if (eventName == "message") {
                                runCatching { JSONObject(jsonStr) }.getOrNull()?.let { obj ->
                                    handleStreamMessage(mySeq, obj)
                                }
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

    private fun handlePreview(mySeq: Int, obj: JSONObject) {
        val liveText = obj.optString("text")
        val thinking = obj.optBoolean("thinking", false)
        runOnUiThread {
            if (destroyed || invokeSeq.get() != mySeq) return@runOnUiThread
            val base = if (committedTranscript.isNotEmpty()) "${committedTranscript}\n\n" else ""
            if (liveText.isNotEmpty()) {
                replyText.text = base + liveText
                constrainReplyHeight()
            } else if (thinking) {
                replyText.text = base + "思考中…"
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
                if (committedTranscript.isNotEmpty()) committedTranscript.append("\n\n")
                committedTranscript.append(text)
                replyText.text = if (finish == "continue") "${committedTranscript}\n\n…" else committedTranscript.toString()
                constrainReplyHeight()
            } else if (role == "assistant" && finish == "continue") {
                val tools = obj.optJSONArray("tools")
                val toolHint = if (tools != null && tools.length() > 0) "正在调用 ${tools.optString(0)}…" else "…"
                val base = if (committedTranscript.isNotEmpty()) "${committedTranscript}\n\n" else ""
                replyText.text = base + toolHint
            } else if (role == "control" && text.isNotEmpty()) {
                val base = if (committedTranscript.isNotEmpty()) "${committedTranscript}\n\n" else ""
                replyText.text = base + text
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

    private fun encodeBitmapJpegBase64(bitmap: Bitmap): String? {
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
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (scaled !== bitmap) scaled.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_UPLOAD_IMG_BYTES) return null
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
        private const val MAX_UPLOAD_IMG_BYTES = 8 * 1024 * 1024
    }
}
