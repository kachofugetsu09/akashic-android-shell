package com.akashic.shell

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

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
        download(view, context, url, userAgent, disposition, mimeType)
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

private val downloadIo = Executors.newSingleThreadExecutor()
private const val MAX_INLINE_DOWNLOAD_BYTES = 32 * 1024 * 1024

// 附件下载：http(s) 交给系统下载器；blob:/data: 经一次性通道或直接解码写入系统媒体/下载目录。
private fun download(
    view: WebView,
    context: Context,
    url: String,
    userAgent: String,
    disposition: String?,
    mimeType: String?,
) {
    when {
        url.startsWith("http://") || url.startsWith("https://") -> {
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
        url.startsWith("data:") -> downloadDataUri(view, context, url, disposition, mimeType)
        url.startsWith("blob:") -> downloadBlobUri(view, context, url, disposition, mimeType)
        else -> Toast.makeText(context, "暂不支持保存这种文件", Toast.LENGTH_SHORT).show()
    }
}

private fun downloadDataUri(
    view: WebView,
    context: Context,
    dataUri: String,
    disposition: String?,
    mimeType: String?,
) {
    val comma = dataUri.indexOf(",")
    if (comma <= 5) {
        Toast.makeText(context, "暂不支持保存这种文件", Toast.LENGTH_SHORT).show()
        return
    }
    val meta = dataUri.substring(5, comma)
    val payload = dataUri.substring(comma + 1)
    val isBase64 = meta.split(";").any { it.equals("base64", ignoreCase = true) }
    val headerMime = meta.substringBefore(";").trim().ifEmpty { null }
    val resolvedMime = headerMime ?: mimeType ?: "application/octet-stream"
    val appContext = context.applicationContext
    downloadIo.execute {
        val result = runCatching {
            val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            if (bytes.size > MAX_INLINE_DOWNLOAD_BYTES) error("文件超过 32MB 上限")
            val name = resolveFileName(disposition, resolvedMime)
            saveBytesToMediaStore(appContext, bytes, resolvedMime, name)
            name
        }
        view.post {
            result.onSuccess { name ->
                Toast.makeText(context, "已保存：$name", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "保存失败：${err.message ?: "写入失败"}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun downloadBlobUri(
    view: WebView,
    context: Context,
    blobUrl: String,
    disposition: String?,
    mimeType: String?,
) {
    val ports = view.createWebMessageChannel()
    val hostPort = ports[0]
    val pagePort = ports[1]
    val reqToken = UUID.randomUUID().toString()
    val buffer = ByteArrayOutputStream()
    var resolvedMime = mimeType?.takeIf { it.isNotBlank() }
    var settled = false

    fun finishError(reason: String) {
        if (settled) return
        settled = true
        runCatching { hostPort.close() }
        view.post { Toast.makeText(context, "保存失败：$reason", Toast.LENGTH_SHORT).show() }
    }

    hostPort.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
        override fun onMessage(port: WebMessagePort, message: WebMessage) {
            if (settled) return
            val text = message.data ?: return
            when {
                text.startsWith("M:") -> {
                    val m = text.substring(2).trim()
                    if (m.isNotEmpty()) resolvedMime = m
                }
                text.startsWith("C:") -> {
                    val chunk = runCatching { Base64.decode(text.substring(2), Base64.DEFAULT) }.getOrNull()
                    if (chunk == null) {
                        finishError("数据解码失败")
                        return
                    }
                    if (buffer.size() + chunk.size > MAX_INLINE_DOWNLOAD_BYTES) {
                        finishError("文件超过 32MB 上限")
                        return
                    }
                    buffer.write(chunk)
                }
                text == "D" -> {
                    settled = true
                    runCatching { hostPort.close() }
                    val bytes = buffer.toByteArray()
                    val finalMime = resolvedMime ?: "application/octet-stream"
                    val name = resolveFileName(disposition, finalMime)
                    val appContext = context.applicationContext
                    downloadIo.execute {
                        val saved = runCatching { saveBytesToMediaStore(appContext, bytes, finalMime, name) }
                        view.post {
                            saved.onSuccess {
                                Toast.makeText(context, "已保存：$name", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "保存失败：${err.message ?: "写入失败"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                text.startsWith("E:") -> finishError(text.substring(2).ifBlank { "读取失败" })
            }
        }
    })

    val targetOrigin = view.url?.let(Uri::parse)?.let { uri ->
        if (!uri.scheme.isNullOrBlank() && !uri.encodedAuthority.isNullOrBlank()) {
            Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.encodedAuthority).build()
        } else {
            Uri.EMPTY
        }
    } ?: Uri.EMPTY

    val script = """
        (function() {
            var token = ${JSONObject.quote(reqToken)};
            var blobUrl = ${JSONObject.quote(blobUrl)};
            function onMsg(ev) {
                if (!ev || ev.data !== token || !ev.ports || !ev.ports[0]) return;
                window.removeEventListener("message", onMsg);
                var port = ev.ports[0];
                (async function() {
                    try {
                        var res = await fetch(blobUrl);
                        var blob = await res.blob();
                        port.postMessage("M:" + (blob.type || ""));
                        var CHUNK = 192 * 1024;
                        for (var off = 0; off < blob.size; off += CHUNK) {
                            var slice = blob.slice(off, off + CHUNK);
                            var dataUrl = await new Promise(function(ok, err) {
                                var reader = new FileReader();
                                reader.onload = function() { ok(String(reader.result || "")); };
                                reader.onerror = function() { err(reader.error || new Error("read failed")); };
                                reader.readAsDataURL(slice);
                            });
                            var idx = dataUrl.indexOf(",");
                            port.postMessage("C:" + (idx >= 0 ? dataUrl.slice(idx + 1) : dataUrl));
                        }
                        port.postMessage("D");
                    } catch (err) {
                        port.postMessage("E:" + String(err && err.message ? err.message : err));
                    }
                })();
            }
            window.addEventListener("message", onMsg);
        })();
    """.trimIndent()

    view.evaluateJavascript(script) {
        if (!settled) {
            runCatching {
                view.postWebMessage(WebMessage(reqToken, arrayOf(pagePort)), targetOrigin)
            }.onFailure { err ->
                finishError(err.message ?: "无法建立下载通道")
            }
        }
    }
}

private fun resolveFileName(disposition: String?, mimeType: String): String {
    val guessed = URLUtil.guessFileName("https://akashic.local/download", disposition, mimeType)
        .replace("/", "_")
        .trim()
    if (guessed.isNotBlank() && !guessed.startsWith("download")) return guessed
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.substringBefore(";").trim())
        ?.takeIf { it.isNotBlank() } ?: "bin"
    return "akashic-${System.currentTimeMillis()}.$ext"
}

private fun saveBytesToMediaStore(context: Context, bytes: ByteArray, mimeType: String, name: String) {
    val resolver = context.contentResolver
    val cleanMime = mimeType.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
    val isImage = cleanMime.startsWith("image/")
    val collection = if (isImage) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val relativePath = if (isImage) "${Environment.DIRECTORY_PICTURES}/Akashic" else Environment.DIRECTORY_DOWNLOADS
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, cleanMime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val itemUri = resolver.insert(collection, values) ?: error("媒体库拒绝创建文件")
    try {
        resolver.openOutputStream(itemUri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: error("无法打开输出流")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(itemUri, done, null, null)
    } catch (e: Throwable) {
        runCatching { resolver.delete(itemUri, null, null) }
        throw e
    }
}

object BuildInfo {
    const val VERSION = "0.5.6"
}
