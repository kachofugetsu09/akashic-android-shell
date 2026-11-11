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
    FAILED("通知数据异常，请查看日志并重新连接"),
    UNSUPPORTED("服务器版本暂不支持通知"),
    LOGIN_REQUIRED("需要登录：打开 Akashic 登录后自动恢复"),
    BACKGROUND_BLOCKED("系统限制后台启动，请打开应用后重试"),
}

// 进程内共享的壳状态：前台可见性和通知连接状态。
object ShellStatus {
    @Volatile var connection = ConnectionState.STOPPED
        private set
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(state: ConnectionState) {
        connection = state
        main.post { listener?.invoke() }
    }

    fun notifyChanged() {
        main.post { listener?.invoke() }
    }
}

data class NotificationItem(
    val sessionId: String,
    val messageId: String,
    val seq: Long,
    val title: String,
    val preview: String,
    val recordedAt: Long,
) {
    companion object {
        fun from(json: JSONObject): NotificationItem {
            val sessionId = json.getString("session_id")
            val messageId = json.getString("message_id")
            require(sessionId.isNotEmpty() && messageId.isNotEmpty()) { "通知消息身份不能为空" }
            val recordedAt = OffsetDateTime.parse(json.getString("recorded_at")).toInstant().toEpochMilli()
            return NotificationItem(
                sessionId, messageId, json.getLong("seq"), json.getString("title"), json.getString("preview"), recordedAt,
            )
        }
    }
}

object Notifications {
    const val ONGOING_ID = 1
    const val ACTION_OPEN_SESSION = "com.akashic.shell.OPEN_SESSION"
    const val ACTION_SETTINGS = "com.akashic.shell.SETTINGS"
    const val EXTRA_SESSION = "session_id"
    const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CONNECTION = "connection"
    private const val GROUP = "akashic.messages"
    private const val MESSAGE_ID = 2
    private const val EXTRA_SEQ = "seq"
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
        val manager = context.getSystemService(NotificationManager::class.java)
        return granted && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_MESSAGES).importance != NotificationManager.IMPORTANCE_NONE
    }

    // 同一会话只保留最新一条提醒，点击后打开该会话。
    fun showMessage(context: Context, item: NotificationItem): Boolean {
        if (!canPost(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val previous = manager.activeNotifications.firstOrNull { it.tag == item.sessionId }
        if (previous?.notification?.extras?.getString("message_id") == item.messageId) return true
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SESSION)
            .setData(android.net.Uri.Builder().scheme("akashic").authority("session").appendPath(item.sessionId).build())
            .putExtra(EXTRA_SESSION, item.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
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
            .addExtras(android.os.Bundle().apply {
                putString("message_id", item.messageId)
                putLong(EXTRA_SEQ, item.seq)
            })
            .setContentIntent(pending)
            .build()
        manager.notify(item.sessionId, MESSAGE_ID, notification)
        return true
    }

    const val PAIRING_ID = 4
    const val ACTION_PAIR_FROM_NOTIFICATION = "com.akashic.shell.PAIR_FROM_NOTIFICATION"
    const val KEY_PAIR_CODE = "pair_code"

    fun showPairingPrompt(context: Context, status: String = "在开发者选项中打开「使用配对码配对设备」后，点下方直接输入 6 位配对码") {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val replyIntent = Intent(context, BootReceiver::class.java).setAction(ACTION_PAIR_FROM_NOTIFICATION)
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(context, 4, replyIntent, replyFlags)
        val remoteInput = android.app.RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel("输入 6 位无线调试配对码（或 配对码:端口）")
            .build()
        val action = Notification.Action.Builder(null, "输入配对码", replyPending)
            .addRemoteInput(remoteInput)
            .build()
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("Akashic 无线调试配对")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(action)
            .build()
        manager.notify("adb-pairing", PAIRING_ID, notification)
    }

    fun cancelPairingPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel("adb-pairing", PAIRING_ID)
    }

    // 会话已在任一设备读到 seq：撤掉不晚于该位置的提醒；升级前发出的提醒没有 seq，一并撤掉。
    fun clearRead(context: Context, sessionId: String, seq: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.firstOrNull { it.tag == sessionId && it.id == MESSAGE_ID } ?: return
        if (active.notification.extras.getLong(EXTRA_SEQ, -1) <= seq) manager.cancel(sessionId, MESSAGE_ID)
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
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    fun showTakeover(context: Context, reason: String) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("Akashic 需要您接管手机屏幕")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify("phone-takeover", 3, notification)
    }
}
