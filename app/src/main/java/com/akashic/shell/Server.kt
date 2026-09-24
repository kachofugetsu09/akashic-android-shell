package com.akashic.shell

import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

sealed interface ProbeResult {
    data class Ready(val authRequired: Boolean, val authenticated: Boolean) : ProbeResult
    data object Legacy : ProbeResult
    data object NotAkashic : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

object Server {
    private const val DEFAULT_PORT = 2236
    private const val TIMEOUT_MS = 5_000

    // 把用户输入整理成 scheme://host:port；省略协议的局域网地址默认使用 2236 端口。
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val explicitScheme = "://" in trimmed
        val uri = Uri.parse(if (explicitScheme) trimmed else "http://$trimmed")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) return null
        val port = when {
            uri.port != -1 -> ":${uri.port}"
            !explicitScheme -> ":$DEFAULT_PORT"
            else -> ""
        }
        val hostPart = if (':' in host) "[$host]" else host
        return "$scheme://$hostPart$port"
    }

    fun label(server: String): String = server.substringAfter("://")

    fun sameOrigin(server: String, url: Uri): Boolean {
        val base = Uri.parse(server)
        return url.scheme == base.scheme && url.host == base.host && port(url) == port(base)
    }

    private fun port(uri: Uri): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme == "https" -> 443
        else -> 80
    }

    // 阻塞调用：确认地址上运行的是 Akashic，并读取登录状态。
    fun probe(server: String): ProbeResult = try {
        val (code, body) = get("$server/api/auth/status")
        when {
            code == 200 -> parseStatus(body)
            code == 404 && get("$server/api/shell/state").first == 200 -> ProbeResult.Legacy
            else -> ProbeResult.NotAkashic
        }
    } catch (error: IOException) {
        ProbeResult.Unreachable(describe(error))
    }

    private fun parseStatus(body: String): ProbeResult = try {
        val json = JSONObject(body)
        if (!json.has("auth_required")) {
            ProbeResult.NotAkashic
        } else {
            ProbeResult.Ready(json.getBoolean("auth_required"), json.optBoolean("authenticated"))
        }
    } catch (_: JSONException) {
        ProbeResult.NotAkashic
    }

    // 退出只影响本机会话；网络失败时本地 Cookie 仍会被清除。
    fun logout(server: String) {
        try {
            val connection = open("$server/api/auth/logout")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            connection.responseCode
            connection.disconnect()
        } catch (_: IOException) {
        }
    }

    fun open(url: String, readTimeoutMs: Int = TIMEOUT_MS): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    private fun get(url: String): Pair<Int, String> {
        val connection = open(url)
        try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(64_000) } ?: ""
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    fun describe(error: IOException): String = when (error) {
        is SocketTimeoutException -> "连接超时"
        is ConnectException -> "连接被拒绝，Akashic 可能没有运行"
        is UnknownHostException -> "找不到这个地址"
        is NoRouteToHostException -> "当前网络无法到达这台服务器"
        else -> error.message ?: "网络错误"
    }
}
