package com.akashic.shell

import android.content.Context
import org.json.JSONObject

// 壳只保存平台状态：服务器地址、通知游标和引导进度，不保存任何聊天内容。
class ShellPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shell", Context.MODE_PRIVATE)

    val serverUrl: String? get() = prefs.getString(KEY_SERVER, null)
    val notificationCursor: JSONObject? get() = prefs.getString(KEY_CURSOR, null)?.let(::JSONObject)
    val onboardingDone: Boolean get() = prefs.getBoolean(KEY_ONBOARDING, false)
    val notificationsWanted: Boolean get() = prefs.getBoolean(KEY_WANTED, false)
    val notificationAsked: Boolean get() = prefs.getBoolean(KEY_ASKED, false)

    // 切换服务器时一并丢弃旧服务器的通知游标。
    @Synchronized
    fun selectServer(url: String) {
        if (url == serverUrl) return
        prefs.edit().putString(KEY_SERVER, url).remove(KEY_CURSOR).apply()
    }

    @Synchronized
    fun saveCursor(server: String, cursor: JSONObject) {
        if (server != serverUrl) return
        check(prefs.edit().putString(KEY_CURSOR, cursor.toString()).commit()) { "通知进度保存失败" }
    }

    @Synchronized
    fun advanceCursor(server: String, session: String, seq: Long) {
        if (server != serverUrl) return
        val cursor = notificationCursor ?: error("缺少通知起始进度")
        if (seq <= cursor.optLong(session, -1)) return
        cursor.put(session, seq)
        saveCursor(server, cursor)
    }

    fun finishOnboarding(wantNotifications: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).putBoolean(KEY_WANTED, wantNotifications).apply()
    }

    fun setNotificationsWanted(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    fun markNotificationAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_CURSOR = "notification_sequences"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_WANTED = "notifications_wanted"
        const val KEY_ASKED = "notification_asked"
    }
}
