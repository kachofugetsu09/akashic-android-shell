package com.akashic.shell

import android.net.Uri
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
    data object Ready : ProbeResult
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

    // 聊天是否可用独立于通知能力，不用认证接口判断服务器版本。
    fun probe(server: String): ProbeResult = try {
        val (code, body) = get("$server/api/shell/state")
        if (code == 200 && JSONObject(body).has("chatReady")) ProbeResult.Ready else ProbeResult.NotAkashic
    } catch (error: IOException) {
        ProbeResult.Unreachable(describe(error))
    } catch (_: JSONException) {
        ProbeResult.NotAkashic
    }

    fun open(url: String, readTimeoutMs: Int = TIMEOUT_MS): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
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
