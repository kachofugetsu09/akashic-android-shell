package com.akashic.shell

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 零轮询系统通知监听适配器：仅将原始通知交给 DeviceEventBroker 规则引擎过滤；
 * 若命中规则开启 auto_cancel，则读完立刻消掉状态栏/折叠栏卡片，实现系统级无感隐身。
 */
class AkashicNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected")
        DeviceEventBroker.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        DeviceEventBroker.init(applicationContext)
        val ongoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val bodyText = if (bigText.length > text.length) bigText else text
        val appName = runCatching {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)

        val result = DeviceEventBroker.onNotificationPosted(
            packageName = pkg,
            appName = appName,
            title = title,
            text = bodyText,
            subText = subText,
            category = notif.category ?: "",
            channelId = notif.channelId ?: "",
            postTimeMs = sbn.postTime,
            ongoing = ongoing,
            notificationKey = sbn.key ?: "",
        )
        if (result.autoCancel && !ongoing) {
            runCatching { cancelNotification(sbn.key) }
        }
    }

    companion object {
        private const val TAG = "AkashicNotifListener"
    }
}
