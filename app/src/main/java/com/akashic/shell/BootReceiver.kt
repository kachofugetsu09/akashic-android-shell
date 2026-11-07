package com.akashic.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 开机、应用更新后恢复通知连接；或从通知栏 RemoteInput 直接接收无线调试配对码。
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (NotifyService.shouldRun(context)) NotifyService.sync(context)
            }
            Notifications.ACTION_PAIR_FROM_NOTIFICATION -> {
                val results = android.app.RemoteInput.getResultsFromIntent(intent)
                val raw = results?.getCharSequence(Notifications.KEY_PAIR_CODE)?.toString()?.trim().orEmpty()
                val parts = raw.split(':', ' ', '：').filter { it.isNotBlank() }
                val code = parts.firstOrNull().orEmpty()
                val manualPort = parts.getOrNull(1)?.toIntOrNull()
                Notifications.showPairingPrompt(context, "正在配对无线调试 ($code)…")
                ShellHelperHub.pairAndStart(context.applicationContext, code, manualPort) { ok, msg ->
                    if (ok) {
                        Notifications.cancelPairingPrompt(context)
                    } else {
                        Notifications.showPairingPrompt(context, "配对未完成：$msg（点下方重试）")
                    }
                }
            }
        }
    }
}
