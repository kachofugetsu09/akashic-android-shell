package com.akashic.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.util.concurrent.Executors

private enum class PanelMode(val id: String) { SETUP("setup"), ONBOARDING("onboarding"), SETTINGS("settings"), OFFLINE("offline") }

// 唯一的界面：远程 Akashic 页面铺满全屏，本地面板只在连接、引导、设置和离线时盖在上面。
class MainActivity : Activity(), RemoteHost, PanelHost {
    private lateinit var prefs: ShellPrefs
    private lateinit var root: FrameLayout
    private lateinit var panel: WebView
    private lateinit var pipView: AgentPipView
    private var remote: WebView? = null
    @Volatile private var panelMode: PanelMode? = null
    @Volatile private var offlineReason = ""
    private var remoteLoaded = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val background = Executors.newSingleThreadExecutor()

    override val server: String? get() = prefs.serverUrl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ShellPrefs(this)
        Notifications.ensureChannels(this)
        buildLayout()
        registerBack()
        val server = prefs.serverUrl
        if (server == null) {
            showPanel(PanelMode.SETUP)
            return
        }
        when {
            intent?.action == Notifications.ACTION_SETTINGS -> showPanel(PanelMode.SETTINGS)
            intent?.action == Notifications.ACTION_OPEN_SESSION || prefs.autoConnect ->
                ensureRemote().loadUrl(entryUrl(server, intent))
            else -> showPanel(PanelMode.SETUP)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            Intent.ACTION_MAIN -> if (!prefs.autoConnect) showPanel(PanelMode.SETUP)
            Notifications.ACTION_SETTINGS -> if (prefs.serverUrl != null) showPanel(PanelMode.SETTINGS)
            Notifications.ACTION_OPEN_SESSION -> intent.getStringExtra(Notifications.EXTRA_SESSION)?.let(::openSession)
        }
    }

    override fun onResume() {
        super.onResume()
        ShellStatus.listener = ::refreshPanel
        PhoneBridgeController.pipListener = { mode, label, png ->
            pipView.updateState(mode, label, png)
        }
        pipView.updateState(PhoneBridgeController.pipMode, PhoneBridgeController.pipLabel, PhoneBridgeController.latestPreviewPng)
        NotifyService.sync(this)
        refreshPanel()
        if (panelMode == null) syncBarsWithPage()
    }

    override fun onPause() {
        ShellStatus.listener = null
        PhoneBridgeController.pipListener = null
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        background.shutdownNow()
        remote?.destroy()
        panel.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel.setBackgroundColor(getColor(R.color.paper))
        if (panelMode != null) applyBars(getColor(R.color.paper)) else syncBarsWithPage()
    }

    // ---- 布局与系统栏 ----

    private fun buildLayout() {
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.paper))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        panel = createPanelWebView()
        panel.visibility = View.GONE
        pipView = AgentPipView(this) { PhoneBridgeController.handleTakeoverTap() }
        root.addView(pipView, pipView.defaultLayoutParams())
        root.addView(panel, fill())
        setContentView(root)
        applyBars(getColor(R.color.paper))
    }

    private fun fill() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun ensureRemote(): WebView = remote ?: createRemoteWebView(this, this).also {
        remote = it
        root.addView(it, 0, fill())
    }

    // 状态栏和导航栏跟随页面底色，深浅色主题切换后仍然协调。
    private fun applyBars(color: Int) {
        root.setBackgroundColor(color)
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (luminance > 0.5) mask else 0, mask)
    }

    private fun syncBarsWithPage() {
        val view = remote ?: return
        view.evaluateJavascript(PAGE_COLOR_SCRIPT) { raw ->
            val parts = Regex("\\d+").findAll(raw.orEmpty()).map { it.value.toInt() }.toList()
            if (parts.size >= 3 && panelMode == null) applyBars(Color.rgb(parts[0], parts[1], parts[2]))
        }
    }

    // ---- 本地面板 ----

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createPanelWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.allowContentAccess = false
        setBackgroundColor(getColor(R.color.paper))
        addJavascriptInterface(PanelBridge(this@MainActivity), "AkashicShell")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(PANEL_URL)
        }
    }

    private fun showPanel(mode: PanelMode) {
        val reuse = panelMode != null && panel.url?.startsWith(PANEL_URL) == true
        panelMode = mode
        if (reuse) refreshPanel() else panel.loadUrl("$PANEL_URL#${mode.id}")
        panel.visibility = View.VISIBLE
        panel.requestFocus()
        applyBars(getColor(R.color.paper))
    }

    private fun hidePanel() {
        panelMode = null
        panel.visibility = View.GONE
        remote?.requestFocus()
        syncBarsWithPage()
    }

    private fun refreshPanel() {
        if (panelMode == null) return
        panel.evaluateJavascript("window.shellView && shellView.render(${panelState()})", null)
    }

    override fun panelState(): String = JSONObject()
        .put("mode", panelMode?.id ?: "")
        .put("server", prefs.serverUrl ?: "")
        .put("autoConnect", prefs.autoConnect)
        .put("hasServer", prefs.serverUrl != null && remoteLoaded)
        .put("offlineReason", offlineReason)
        .put("permission", notificationPermission())
        .put("notificationsWanted", prefs.notificationsWanted)
        .put("batteryUnrestricted", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        .put("connection", ShellStatus.connection.name)
        .put("connectionLabel", ShellStatus.connection.label)
        .put("phoneUseEnabled", prefs.phoneUseEnabled)
        .put("phoneConnected", PhoneBridgeController.connected)
        .put("phoneConnectionError", PhoneBridgeController.connectionError ?: "")
        .put("accessibilityEnabled", AkashicAccessibilityService.instance != null)
        .put("adbHelperActive", ShellHelperHub.shell != null)
        .put("adbHelperStatus", ShellHelperHub.statusText)
        .put("adbPairingBusy", ShellHelperHub.pairingInProgress)
        .put("deviceId", PhoneBridgeController.deviceId(this))
        .put("version", BuildInfo.VERSION)
        .toString()

    // granted：可以通知；prompt：还能弹系统授权框；blocked：只能去系统设置里打开。
    private fun notificationPermission(): String {
        if (Notifications.canPost(this)) return "granted"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return "blocked"
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) return "blocked"
        val canAsk = !prefs.notificationAsked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        return if (canAsk) "prompt" else "blocked"
    }

    // ---- PanelHost：连接服务器 ----

    override fun connect(address: String, autoConnect: Boolean) {
        val normalized = Server.normalize(address)
        if (normalized == null) {
            deliverConnect(JSONObject().put("ok", false).put("error", "请输入完整服务器地址，如 https://your-server.example 或 http://服务器IP:2236"))
            return
        }
        background.execute {
            val result = Server.probe(normalized)
            runOnUiThread { onProbe(normalized, autoConnect, result) }
        }
    }

    private fun onProbe(server: String, autoConnect: Boolean, result: ProbeResult) {
        val label = Server.label(server)
        val reply = JSONObject().put("server", server)
        when (result) {
            ProbeResult.Ready -> reply.put("ok", true)
            ProbeResult.NotAkashic -> reply.put("ok", false)
                .put("error", "$label 有响应，但不是 Akashic。请检查端口，默认是 2236。")
            is ProbeResult.Unreachable -> reply.put("ok", false)
                .put("error", "连不上 $label：${result.reason}。请确认当前网络能访问该地址，并且 Akashic 正在运行。")
        }
        if (result == ProbeResult.Ready) {
            val changed = prefs.serverUrl != server
            prefs.selectServer(server, autoConnect)
            if (changed) {
                resetRemote()
                NotifyService.reconnect(this)
            }
        }
        deliverConnect(reply)
    }

    private fun deliverConnect(reply: JSONObject) {
        panel.evaluateJavascript("window.shellView && shellView.onConnectResult($reply)", null)
    }

    override fun setAutoConnect(enabled: Boolean) {
        prefs.setAutoConnect(enabled)
        refreshPanel()
    }

    override fun finishSetup() {
        val server = prefs.serverUrl ?: return
        hidePanel()
        if (!remoteLoaded) ensureRemote().loadUrl("$server/")
        NotifyService.reconnect(this)
    }

    // 页面仍不可用时，关闭设置或换服务器面板要回到离线页，而不是露出空白网页。
    override fun closePanel() {
        when {
            panelMode == PanelMode.SETUP && prefs.serverUrl == null -> finish()
            offlineReason.isNotEmpty() -> showPanel(PanelMode.OFFLINE)
            panelMode == PanelMode.SETUP && !remoteLoaded -> finish()
            !remoteLoaded -> showPanel(PanelMode.SETUP)
            else -> hidePanel()
        }
    }

    override fun changeServer() = showPanel(PanelMode.SETUP)

    override fun openSettings() = showPanel(PanelMode.SETTINGS)

    override fun retry() {
        val server = prefs.serverUrl ?: return showPanel(PanelMode.SETUP)
        hidePanel()
        val view = ensureRemote()
        if (view.url.isNullOrEmpty()) view.loadUrl("$server/") else view.reload()
    }

    private fun resetRemote() {
        remote?.let {
            root.removeView(it)
            it.destroy()
        }
        remote = null
        remoteLoaded = false
    }

    // ---- PanelHost：通知与后台 ----

    override fun requestNotificationPermission() {
        when (notificationPermission()) {
            "granted" -> refreshPanel()
            "prompt" -> {
                prefs.markNotificationAsked()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
            else -> openNotificationSettings()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (Notifications.canPost(this) && panelMode == PanelMode.SETTINGS) prefs.setNotificationsWanted(true)
        NotifyService.sync(this)
        refreshPanel()
    }

    override fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            intent.action = Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_MESSAGES)
        }
        launch(intent)
    }

    @SuppressLint("BatteryLife")
    override fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!launch(request)) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    override fun finishOnboarding(enable: Boolean) {
        prefs.finishOnboarding(enable && Notifications.canPost(this))
        NotifyService.sync(this)
        hidePanel()
    }

    override fun setNotifications(enable: Boolean) {
        prefs.setNotificationsWanted(enable)
        NotifyService.sync(this)
        refreshPanel()
    }

    override fun setPhoneUse(enable: Boolean) {
        prefs.setPhoneUseEnabled(enable)
        PhoneBridgeController.sync(this)
        refreshPanel()
    }

    override fun openAccessibilitySettings() {
        if (!launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            launch(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun openWirelessDebuggingSettings() {
        Notifications.showPairingPrompt(this)
        if (!launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
            launch(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun pairWirelessDebugging(code: String, portText: String) {
        val manualPort = portText.trim().toIntOrNull()
        ShellHelperHub.pairAndStart(this, code, manualPort) { _, msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshPanel()
            }
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    // ---- RemoteHost ----

    override fun onPageReady(url: Uri) {
        val server = prefs.serverUrl ?: return
        if (!Server.sameOrigin(server, url)) return
        remoteLoaded = true
        offlineReason = ""
        // 登录页不算就绪；回到服务器其他页面说明已登录，唤醒等待登录的通知连接。
        if (url.path?.startsWith("/_auth") != true) NotifyService.resumeAfterLogin(this)
        if (panelMode == PanelMode.OFFLINE) hidePanel()
        if (!prefs.onboardingDone && panelMode == null) showPanel(PanelMode.ONBOARDING) else syncBarsWithPage()
    }

    override fun onLoadFailed(reason: String) {
        offlineReason = when {
            "CONNECTION_REFUSED" in reason -> "连接被拒绝，Akashic 可能没有运行"
            "TIMED_OUT" in reason -> "连接超时"
            "ADDRESS_UNREACHABLE" in reason || "INTERNET_DISCONNECTED" in reason -> "当前网络无法到达服务器"
            "NAME_NOT_RESOLVED" in reason -> "找不到这个地址"
            else -> reason.ifEmpty { "网络错误" }
        }
        if (panelMode == null || panelMode == PanelMode.OFFLINE) showPanel(PanelMode.OFFLINE) else refreshPanel()
    }

    override fun onRendererGone() {
        val server = prefs.serverUrl
        resetRemote()
        if (server != null) ensureRemote().loadUrl("$server/")
    }

    override fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val chooser = params.createIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        return try {
            startActivityForResult(chooser, REQUEST_FILES)
            true
        } catch (_: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    @Deprecated("Activity result API needs AndroidX; the framework callback is enough here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILES) return super.onActivityResult(requestCode, resultCode, data)
        val callback = fileCallback ?: return
        fileCallback = null
        val clip = data?.clipData
        val uris = when {
            resultCode != RESULT_OK || data == null -> null
            clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            else -> data.data?.let { arrayOf(it) }
        }
        callback.onReceiveValue(uris)
    }

    override fun openExternal(uri: Uri) {
        if (!launch(Intent(Intent.ACTION_VIEW, uri))) Toast.makeText(this, "没有应用能打开这个链接", Toast.LENGTH_SHORT).show()
    }

    // ---- 会话跳转与返回键 ----

    private fun entryUrl(server: String, intent: Intent?): String {
        val session = intent?.takeIf { it.action == Notifications.ACTION_OPEN_SESSION }
            ?.getStringExtra(Notifications.EXTRA_SESSION)
        return if (session == null) "$server/" else "$server/?session=${Uri.encode(session)}"
    }

    // 冷启动与热点击都走同一网页导航，不另建跨 iframe 的就绪协议。
    private fun openSession(sessionId: String) {
        val server = prefs.serverUrl ?: return
        if (panelMode != null) hidePanel()
        ensureRemote().loadUrl("$server/?session=${Uri.encode(sessionId)}")
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    // Android 13+ 已在 registerBack 注册原生回调；这里只保留旧系统入口。
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Only reached below Android 13; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val view = remote
        when (panelMode) {
            PanelMode.SETTINGS -> closePanel()
            PanelMode.ONBOARDING -> finishOnboarding(false)
            PanelMode.SETUP -> closePanel()
            PanelMode.OFFLINE -> moveTaskToBack(true)
            null -> if (view != null && view.canGoBack()) view.goBack() else moveTaskToBack(true)
        }
    }

    private companion object {
        const val PANEL_URL = "file:///android_asset/shell.html"
        const val REQUEST_FILES = 10
        const val REQUEST_NOTIFICATIONS = 11
        const val PAGE_COLOR_SCRIPT = "(function(){var c=[document.body,document.documentElement]" +
            ".map(function(e){return e?getComputedStyle(e).backgroundColor:''})" +
            ".filter(function(v){return v&&v!=='transparent'&&v.indexOf('rgba(0, 0, 0, 0)')<0});return c[0]||''})()"
    }
}
