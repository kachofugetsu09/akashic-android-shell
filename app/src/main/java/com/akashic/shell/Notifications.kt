package com.akashic.shell

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.time.OffsetDateTime

enum class ConnectionState(val label: String) {
    STOPPED("通知已关闭"),
    CONNECTING("正在连接…"),
    LISTENING("正在接收新消息"),
    RETRYING("连接中断，正在重试"),
    SIGNED_OUT("需要重新登录，点按打开 Akashic"),
    UNSUPPORTED("服务器版本暂不支持通知"),
}

// 进程内共享的壳状态：前台可见性和通知连接状态。
object ShellStatus {
    @Volatile var appVisible = false
    @Volatile var connection = ConnectionState.STOPPED
        private set
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(state: ConnectionState) {
        connection = state
        main.post { listener?.invoke() }
    }
}

data class NotificationItem(
    val sessionId: String,
    val messageId: String,
    val title: String,
    val preview: String,
    val recordedAt: Long,
) {
    companion object {
        fun from(json: JSONObject): NotificationItem? {
            val sessionId = json.optString("session_id")
            val messageId = json.optString("message_id")
            if (sessionId.isEmpty() || messageId.isEmpty()) return null
            val recordedAt = runCatching {
                OffsetDateTime.parse(json.optString("recorded_at")).toInstant().toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())
            return NotificationItem(sessionId, messageId, json.optString("title", "Akashic"), json.optString("preview"), recordedAt)
        }
    }
}

object Notifications {
    const val ONGOING_ID = 1
    const val ACTION_OPEN_SESSION = "com.akashic.shell.OPEN_SESSION"
    const val ACTION_SETTINGS = "com.akashic.shell.SETTINGS"
    const val EXTRA_SESSION = "session_id"
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CONNECTION = "connection"
    private const val GROUP = "akashic.messages"
    private val ACCENT = Color.rgb(0x1B, 0x36, 0x5D)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val messages = NotificationChannel(CHANNEL_MESSAGES, "消息", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "定时任务、主动消息和完成的回复"
        }
        val connection = NotificationChannel(CHANNEL_CONNECTION, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
            description = "保持接收消息的常驻通知，可以在这里将它静音"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(messages, connection))
    }

    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    // 同一会话只保留最新一条提醒，点击后打开该会话。
    fun showMessage(context: Context, item: NotificationItem) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SESSION)
            .putExtra(EXTRA_SESSION, item.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, item.sessionId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(item.title.ifEmpty { "Akashic" })
            .setContentText(item.preview)
            .setStyle(Notification.BigTextStyle().bigText(item.preview))
            .setWhen(item.recordedAt)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.sessionId.hashCode(), notification)
    }

    // 回到前台即视为已看到，只清除消息提醒，保留常驻通知。
    fun clearMessages(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id != ONGOING_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun ongoing(context: Context, state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val settings = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).setAction(ACTION_SETTINGS), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("Akashic")
            .setContentText(state.label)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "设置", settings).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
