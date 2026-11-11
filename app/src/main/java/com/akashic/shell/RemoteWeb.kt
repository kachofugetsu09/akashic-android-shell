package com.akashic.shell

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

// 远程页面与宿主之间的全部回调；远程 WebView 不注入任何 JS 接口。
interface RemoteHost {
    val server: String?
    fun onPageReady(url: Uri)
    fun onLoadFailed(reason: String)
    fun onRendererGone()
    fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean
    fun openExternal(uri: Uri)
}

@SuppressLint("SetJavaScriptEnabled")
fun createRemoteWebView(context: Context, host: RemoteHost): WebView {
    val view = WebView(context)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        userAgentString = "$userAgentString AkashicShell/${BuildInfo.VERSION}"
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    view.webViewClient = RemoteClient(host)
    view.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = host.pickFiles(fileChooserParams, filePathCallback)
    }
    view.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
        download(context, url, userAgent, disposition, mimeType)
    }
    return view
}

private class RemoteClient(private val host: RemoteHost) : WebViewClient() {
    private var failed = false

    // 同源页面留在应用内，其余链接交给系统浏览器或对应应用。
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val server = host.server ?: return true
        if (Server.sameOrigin(server, request.url)) return false
        host.openExternal(request.url)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        failed = false
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!failed) host.onPageReady(Uri.parse(url))
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        failed = true
        host.onLoadFailed(error.description?.toString().orEmpty())
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.onRendererGone()
        return true
    }
}

// 附件下载交给系统下载器，并带上当前登录 Cookie。
private fun download(context: Context, url: String, userAgent: String, disposition: String?, mimeType: String?) {
    if (!url.startsWith("http")) {
        Toast.makeText(context, "暂不支持保存这种文件", Toast.LENGTH_SHORT).show()
        return
    }
    val name = URLUtil.guessFileName(url, disposition, mimeType)
    val request = DownloadManager.Request(Uri.parse(url))
        .setMimeType(mimeType)
        .addRequestHeader("User-Agent", userAgent)
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    context.getSystemService(DownloadManager::class.java).enqueue(request)
    Toast.makeText(context, "开始下载：$name", Toast.LENGTH_SHORT).show()
}

object BuildInfo {
    const val VERSION = "0.4.1"
}
