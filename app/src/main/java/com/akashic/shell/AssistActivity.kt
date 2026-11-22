package com.akashic.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 看屏唤起半屏浮层：
 * - 接收按住底部横条（LAUNCH_CONTEXTUAL_SEARCH）与默认数字助理（ACTION_ASSIST）；
 * - 抓取截图与当前页可见文本上传给外置插件 /api/phone-assist/invoke；
 * - 在底部半屏 WebView 打开插件返回的 sheet_url，点「在 akashic 继续」时跳回 MainActivity。
 */
class AssistActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: ShellPrefs
    private lateinit var webView: WebView
    private lateinit var loadingBox: LinearLayout
    private lateinit var statusLabel: TextView
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ShellPrefs(this)
        buildSheetUi()
        val server = prefs.serverUrl?.trimEnd('/')
        if (server.isNullOrEmpty()) {
            showError("尚未配置 Akashic 服务器，请先打开主应用连接。")
            return
        }
        handleInvoke(server, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val server = prefs.serverUrl?.trimEnd('/') ?: return
        handleInvoke(server, intent)
    }

    override fun onDestroy() {
        destroyed = true
        io.shutdownNow()
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildSheetUi() {
        window.setDecorFitsSystemWindows(false)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setOnClickListener { finish() }
        }

        val sheetHeight = (resources.displayMetrics.heightPixels * 0.66f).toInt()
        val sheet = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(getColor(R.color.paper))
                val r = dp(18).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            isClickable = true
        }

        webView = WebView(this).apply {
            setBackgroundColor(getColor(R.color.paper))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                userAgentString = "$userAgentString AkashicShell/${BuildInfo.VERSION}"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "akashic-shell" && uri.host == "open-session") {
                        val sid = uri.getQueryParameter("session")
                        openInMainApp(sid)
                        return true
                    }
                    val server = prefs.serverUrl ?: return true
                    if (Server.sameOrigin(server, uri)) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loadingBox.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        showError("加载浮层失败：${error.description}")
                    }
                }
            }
        }

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.paper))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(ProgressBar(this@AssistActivity))
            statusLabel = TextView(this@AssistActivity).apply {
                text = "正在抓取当前屏幕…"
                textSize = 13f
                setTextColor(getColor(R.color.action))
                setPadding(0, dp(12), 0, 0)
                gravity = Gravity.CENTER
            }
            addView(statusLabel)
        }

        sheet.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        sheet.addView(loadingBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM)
        root.addView(sheet, lp)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            val bottomPad = maxOf(ime.bottom, nav.bottom)
            sheet.setPadding(0, 0, 0, bottomPad)
            insets
        }
        setContentView(root)
    }

    private fun handleInvoke(server: String, intent: Intent?) {
        loadingBox.visibility = View.VISIBLE
        statusLabel.text = "正在上传屏幕快照…"
        val source = when (intent?.action) {
            ACTION_LAUNCH_CONTEXTUAL_SEARCH -> "nav_handle"
            Intent.ACTION_ASSIST -> "assistant"
            else -> "manual"
        }
        val extraBitmap = extractBitmap(intent)
        val visiblePkgs = intent?.getStringArrayListExtra(EXTRA_VISIBLE_PACKAGE_NAMES)
        val capturedAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        io.execute {
            try {
                var pkg = visiblePkgs?.firstOrNull { it != packageName } ?: ""
                var uiTreeText = ""
                val a11y = AkashicAccessibilityService.instance
                if (a11y != null) {
                    runCatching {
                        val readout = a11y.dumpScreen(Display.DEFAULT_DISPLAY, RefRegistry())
                        if (pkg.isEmpty() && readout.packageName.isNotEmpty() && readout.packageName != packageName) {
                            pkg = readout.packageName
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
                                if (lines.size >= 60) break
                            }
                        }
                        uiTreeText = lines.joinToString("\n").take(2800)
                    }
                }
                val appLabel = resolveAppLabel(pkg)
                val b64 = extraBitmap?.let(::encodeBitmapPngBase64)

                val body = JSONObject()
                    .put("source", source)
                    .put("package_name", pkg)
                    .put("app_label", appLabel)
                    .put("captured_at", capturedAt)
                    .put("ui_tree_text", uiTreeText)
                if (b64 != null) body.put("screenshot_png_b64", b64)

                val resp = postJson("$server/api/phone-assist/invoke", server, body)
                val sheetPath = resp.optString("sheet_url").ifBlank { "/api/phone-assist/sheet" }
                val targetUrl = if (sheetPath.startsWith("http")) sheetPath else "$server$sheetPath"
                if (!destroyed) {
                    runOnUiThread {
                        webView.loadUrl(targetUrl)
                    }
                }
            } catch (t: Throwable) {
                if (!destroyed) {
                    runOnUiThread {
                        showError("唤起失败：${t.message ?: t.javaClass.simpleName}\n请确认已登录 Akashic 且已启用 phone-assist 插件。")
                    }
                }
            }
        }
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

    private fun encodeBitmapPngBase64(bitmap: Bitmap): String {
        val maxW = 720
        val scaled = if (bitmap.width > maxW) {
            val ratio = maxW.toFloat() / bitmap.width.toFloat()
            Bitmap.createScaledBitmap(bitmap, maxW, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
            connectTimeout = 8_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Origin", origin)
            CookieManager.getInstance().getCookie(origin)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(160)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openInMainApp(sessionId: String?) {
        val next = Intent(this, MainActivity::class.java).apply {
            action = if (sessionId.isNullOrBlank()) Intent.ACTION_MAIN else Notifications.ACTION_OPEN_SESSION
            if (!sessionId.isNullOrBlank()) putExtra(Notifications.EXTRA_SESSION, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(next)
        finish()
    }

    private fun showError(msg: String) {
        loadingBox.visibility = View.VISIBLE
        statusLabel.text = msg
    }

    companion object {
        const val ACTION_LAUNCH_CONTEXTUAL_SEARCH = "android.app.action.LAUNCH_CONTEXTUAL_SEARCH"
        const val EXTRA_SCREENSHOT = "android.app.contextualsearch.extra.SCREENSHOT"
        const val EXTRA_VISIBLE_PACKAGE_NAMES = "android.app.contextualsearch.extra.VISIBLE_PACKAGE_NAMES"
    }
}
