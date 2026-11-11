package com.akashic.shell

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import android.util.Log
import android.webkit.CookieManager
import java.time.format.DateTimeParseException

private sealed interface StreamOutcome {
    data class Dropped(val established: Boolean) : StreamOutcome
    data object Unsupported : StreamOutcome
    data object LoginRequired : StreamOutcome
}

// 一个前台服务承载通知订阅和后台手机控制；通知关闭不影响已启用的手机控制。
class NotifyService : Service() {
    private val wake = Object()
    @Volatile private var running = false
    @Volatile private var notificationsEnabled = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var worker: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var prefs: ShellPrefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ShellPrefs(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wasEnabled = notificationsEnabled
        notificationsEnabled = shouldSubscribe(this)
        if (!notificationsEnabled) ShellStatus.update(ConnectionState.STOPPED)
        val notification = ongoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifications.ONGOING_ID, notification)
        }
        // 系统以 null intent 重建 sticky 服务时，也必须恢复控制桥。
        PhoneBridgeController.sync(this)
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (notificationsEnabled && worker == null) {
            running = true
            if (networkCallback == null) registerNetworkCallback()
            worker = Thread(::runLoop, "akashic-notify").apply { isDaemon = true; start() }
        } else if (!notificationsEnabled || !wasEnabled || intent?.action == ACTION_RECONNECT) {
            // 暂停同一个线程，避免快速关开时旧线程随共享 running 标志复活。
            wakeUp(abort = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        notificationsEnabled = false
        PhoneBridgeController.stop()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wakeUp(abort = true)
        ShellStatus.update(ConnectionState.STOPPED)
        super.onDestroy()
    }

    // 1. 连接通知流；2. 按结果决定等待时长；3. 网络恢复或手动重连会提前结束等待。
    private fun runLoop() {
        var failures = 0
        while (running) {
            if (!waitForNotifications()) break
            val server = prefs.serverUrl ?: break
            setState(if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RETRYING)
            val outcome = try {
                streamOnce(server)
            } catch (error: JSONException) {
                return fail(error)
            } catch (error: IllegalArgumentException) {
                return fail(error)
            } catch (error: IllegalStateException) {
                return fail(error)
            } catch (error: DateTimeParseException) {
                return fail(error)
            }
            if (!running) break
            if (!notificationsEnabled) continue
            // 地址变化不继承旧服务器的失败或不支持等待。
            if (server != prefs.serverUrl) {
                failures = 0
                continue
            }
            val waitMs = when (outcome) {
                is StreamOutcome.Dropped -> {
                    failures = if (outcome.established) 1 else failures + 1
                    if (running) setState(ConnectionState.RETRYING)
                    BACKOFF_MS[minOf(failures, BACKOFF_MS.size) - 1]
                }
                StreamOutcome.Unsupported -> { failures = 0; setState(ConnectionState.UNSUPPORTED); 300_000L }
                // 登录失效不是数据错误：等用户在页面里重新登录后由 reconnect 唤醒，期间低频重试。
                StreamOutcome.LoginRequired -> { failures = 0; setState(ConnectionState.LOGIN_REQUIRED); 300_000L }
            }
            pause(waitMs)
        }
    }

    // 每次重连重新探测；旧服务端的 422 只降级当前连接，不跨服务器保留。
    private fun streamOnce(server: String): StreamOutcome {
        var requestReadEvents = true
        while (true) {
            if (!running || !notificationsEnabled) return StreamOutcome.Dropped(established = false)
            val current = try {
                Server.open("$server/api/chat/notifications/stream", readTimeoutMs = READ_TIMEOUT_MS)
            } catch (_: IOException) {
                return StreamOutcome.Dropped(established = false)
            }
            current.requestMethod = "POST"
            current.doOutput = true
            current.setRequestProperty("Content-Type", "application/json")
            current.setRequestProperty("Accept", "text/event-stream")
            // 服务端开启登录后，通知流与页面共用 WebView 里的登录 Cookie。
            CookieManager.getInstance().getCookie(server)?.let { current.setRequestProperty("Cookie", it) }
            connection = current
            try {
                if (!running || !notificationsEnabled) return StreamOutcome.Dropped(established = false)
                val body = JSONObject().put("cursor", prefs.notificationCursor ?: JSONObject.NULL)
                if (requestReadEvents) body.put("read_events", true)
                current.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                when (current.responseCode) {
                    200 -> {
                        check(current.contentType?.startsWith("text/event-stream") == true) { "通知接口未返回 SSE" }
                        return readEvents(current, server)
                    }
                    404, 405 -> return StreamOutcome.Unsupported
                    422 -> {
                        check(requestReadEvents) { "通知接口拒绝请求：422" }
                        requestReadEvents = false
                    }
                    401, 403 -> return StreamOutcome.LoginRequired
                    400 -> error("通知接口拒绝请求：400")
                    else -> return StreamOutcome.Dropped(established = false)
                }
            } catch (_: IOException) {
                return StreamOutcome.Dropped(established = false)
            } finally {
                connection = null
                current.disconnect()
            }
        }
    }

    // 逐行解析 SSE；心跳每 15 秒一次，超过读超时即视为连接失效。
    private fun readEvents(current: HttpURLConnection, server: String): StreamOutcome {
        var established = false
        var event = ""
        val data = StringBuilder()
        try {
            current.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (running && notificationsEnabled && server == prefs.serverUrl) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            established = dispatch(event, data.toString(), server) || established
                            event = ""
                            data.setLength(0)
                        }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                    }
                }
            }
        } catch (_: IOException) {
        }
        return StreamOutcome.Dropped(established)
    }

    // 先交给系统通知，再保存进度；中途退出允许重放，不能跳过提醒。
    private fun dispatch(event: String, data: String, server: String): Boolean {
        if (!running || !notificationsEnabled || server != prefs.serverUrl) return false
        val json = JSONObject(data)
        when (event) {
            "ready" -> {
                prefs.saveCursor(server, json.getJSONObject("cursor"))
                // 离线期间在别处读过的会话，重连后撤掉对应提醒。
                json.optJSONObject("read")?.let { read ->
                    read.keys().forEach { sessionId -> Notifications.clearRead(this, sessionId, read.getLong(sessionId)) }
                }
                setState(ConnectionState.LISTENING)
            }
            "message" -> {
                val item = NotificationItem.from(json)
                val seq = json.getLong("seq")
                require(seq >= 0) { "通知 seq 无效" }
                val seen = prefs.notificationCursor?.optLong(item.sessionId, -1) ?: -1
                if (seq > seen) {
                    if (!Notifications.showMessage(this, item)) {
                        notificationsEnabled = false
                        setState(ConnectionState.STOPPED)
                        if (!prefs.phoneUseEnabled) stopSelf()
                        return false
                    }
                    prefs.advanceCursor(server, item.sessionId, seq)
                }
            }
            "cursor" -> prefs.advanceCursor(server, json.getString("session_id"), json.getLong("seq"))
            "read" -> Notifications.clearRead(this, json.getString("session_id"), json.getLong("seq"))
            "heartbeat" -> Unit
            else -> error("未知通知事件：$event")
        }
        return event == "ready"
    }

    private fun fail(error: Exception) {
        Log.e("AkashicNotify", "通知停止，保留原进度", error)
        if (running && notificationsEnabled) setState(ConnectionState.FAILED)
        worker = null
    }

    private fun setState(state: ConnectionState) {
        if (state == ShellStatus.connection) return
        ShellStatus.update(state)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ONGOING_ID, ongoingNotification())
    }

    private fun ongoingNotification(): Notification {
        val notification = Notifications.ongoing(this, ShellStatus.connection)
        return if (!notificationsEnabled && prefs.phoneUseEnabled) {
            Notification.Builder.recoverBuilder(this, notification).setContentText("后台手机控制已启用").build()
        } else notification
    }

    private fun waitForNotifications(): Boolean = synchronized(wake) {
        while (running && !notificationsEnabled) wake.wait()
        running
    }

    private fun pause(millis: Long) {
        synchronized(wake) { if (running) wake.wait(millis) }
    }

    private fun wakeUp(abort: Boolean) {
        if (abort) connection?.disconnect()
        synchronized(wake) { wake.notifyAll() }
    }

    // 切换到新网络时旧连接已经失效，立即中断并重连。
    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                val changed = last != null && last != network
                last = network
                wakeUp(abort = changed)
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    companion object {
        private const val ACTION_RECONNECT = "com.akashic.shell.RECONNECT"
        private const val READ_TIMEOUT_MS = 45_000
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

        fun shouldRun(context: Context): Boolean {
            val prefs = ShellPrefs(context)
            return prefs.serverUrl != null && (prefs.phoneUseEnabled || shouldSubscribe(context))
        }

        private fun shouldSubscribe(context: Context): Boolean =
            ShellPrefs(context).notificationsWanted && Notifications.canPost(context)

        fun sync(context: Context) {
            if (shouldRun(context)) {
                context.startForegroundService(Intent(context, NotifyService::class.java))
            } else {
                context.stopService(Intent(context, NotifyService::class.java))
            }
        }

        // 页面登录成功后调用：只唤醒正在等待登录的连接，不打断正常接收。
        fun resumeAfterLogin(context: Context) {
            if (ShellStatus.connection == ConnectionState.LOGIN_REQUIRED) reconnect(context) else sync(context)
        }

        fun reconnect(context: Context) {
            if (!shouldRun(context)) {
                sync(context)
                return
            }
            context.startForegroundService(Intent(context, NotifyService::class.java).setAction(ACTION_RECONNECT))
        }
    }
}
