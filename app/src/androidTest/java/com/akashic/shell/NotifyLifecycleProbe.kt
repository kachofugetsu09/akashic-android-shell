package com.akashic.shell

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 直接创建真实服务，确认控制恢复不依赖 Activity 或 companion 调用。 */
class NotifyLifecycleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        if (targetContext.packageName != "com.akashic.shell.lifecycletest") {
            result.putString("ok", "false")
            result.putString("error", "Isolated lifecycletest package required")
            finish(Activity.RESULT_CANCELED, result)
            return
        }
        try {
            val prefs = ShellPrefs(targetContext)
            val service = Intent(targetContext, NotifyService::class.java)
            val report = JSONObject()
            prefs.setAdbPaired(false)
            prefs.setPhoneUseEnabled(true)
            prefs.setNotificationsWanted(false)
            Fixture().use { first ->
                prefs.selectServer(first.url, false)
                // 直接进入系统 Service 生命周期，不调用 NotifyService.sync。
                targetContext.startForegroundService(service)
                check(first.phone.await(10, TimeUnit.SECONDS)) { "cold service did not restore phone bridge" }
                await("foreground host") { isForeground() && PhoneBridgeController.connected }
                check(first.notificationRequests == 0)
                check(notifyThreads().isEmpty()) { "phone-only cold start created notification worker" }
                report.put("phone_only_foreground_without_notification_subscription", true)
                check(targetContext.stopService(service))
                awaitStopped()
            }
            Fixture().use { second ->
                prefs.selectServer(second.url, false)
                targetContext.startForegroundService(service)
                check(second.phone.await(10, TimeUnit.SECONDS)) { "recreated service did not restore phone bridge" }
                await("recreated foreground host") { isForeground() && PhoneBridgeController.connected }
                report.put("new_service_instance_restored_phone", true)
                val canPost = Notifications.canPost(targetContext)
                report.put("notifications_permission", canPost)
                if (canPost) {
                    prefs.setNotificationsWanted(true)
                    NotifyService.sync(targetContext)
                    check(second.notifications.await(10, TimeUnit.SECONDS)) { "notification subscription did not start" }
                    prefs.setNotificationsWanted(false)
                    NotifyService.sync(targetContext)
                    check(second.notificationsClosed.await(10, TimeUnit.SECONDS)) { "disabled subscription stayed connected" }
                    await("paused notification worker") {
                        notifyThreads().singleOrNull()?.state == Thread.State.WAITING
                    }
                    check(isForeground() && PhoneBridgeController.connected)
                    report.put("notification_toggle_kept_phone_host", true)
                }
                prefs.setPhoneUseEnabled(false)
                NotifyService.sync(targetContext)
                awaitStopped()
                report.put("all_features_disabled_stopped_host", true)
            }
            report.put("system_sticky_process_restart_tested", false)
            report.put("force_stop_used", false)
            result.putString("ok", "true")
            result.putString("report", report.toString())
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            ShellPrefs(targetContext).apply {
                setPhoneUseEnabled(false)
                setNotificationsWanted(false)
            }
            targetContext.stopService(Intent(targetContext, NotifyService::class.java))
            result.putString("ok", "false")
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    @Suppress("DEPRECATION")
    private fun isForeground(): Boolean = targetContext.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == NotifyService::class.java.name && it.foreground }

    private fun notifyThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "akashic-notify" }

    private fun awaitStopped() = await("service and worker shutdown") {
        !isForeground() && Thread.getAllStackTraces().keys.none {
            it.isAlive && (it.name == "AkashicPhoneBridge" || it.name == "akashic-notify")
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            SystemClock.sleep(10)
        }
    }

    /** 本地真实 HTTP/SSE 对端；不发送 RPC，不创建显示器或输入事件。 */
    private class Fixture : Closeable {
        private val server = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${server.localPort}"
        val phone = CountDownLatch(1)
        val notifications = CountDownLatch(1)
        val notificationsClosed = CountDownLatch(1)
        @Volatile var notificationRequests = 0
            private set

        init {
            Thread({
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        sockets.add(socket)
                        Thread({ serve(socket) }, "lifecycle-fixture-client").apply { isDaemon = true; start() }
                    }
                } catch (error: IOException) {
                    check(server.isClosed) { error.toString() }
                }
            }, "lifecycle-fixture").apply { isDaemon = true; start() }
        }

        private fun serve(socket: Socket) {
            var isNotification = false
            try {
                socket.soTimeout = 1_000
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = ArrayList<Byte>()
                    while (true) {
                        val byte = input.read()
                        check(byte >= 0)
                        if (byte == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                        bytes.add(byte.toByte())
                    }
                }
                val path = line().split(' ')[1]
                var length = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(input.read() >= 0) }
                check(path == "/api/phone/stream" || path == "/api/chat/notifications/stream") { "Unexpected fixture path: $path" }
                isNotification = path == "/api/chat/notifications/stream"
                val event = if (isNotification) "event: ready\ndata: {\"cursor\":{}}\n\n" else "event: welcome\ndata: {}\n\n"
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + event).toByteArray())
                    flush()
                }
                if (isNotification) { notificationRequests++; notifications.countDown() } else phone.countDown()
                while (!closed) {
                    try { if (input.read() < 0) break } catch (_: SocketTimeoutException) { }
                }
            } catch (_: IOException) {
                // 客户端关闭订阅和 fixture 退出都会关闭 socket。
            } finally {
                if (isNotification) notificationsClosed.countDown()
                socket.close()
            }
        }

        override fun close() {
            closed = true
            server.close()
            sockets.forEach { it.close() }
        }
    }
}
