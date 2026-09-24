package com.akashic.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 开机或应用更新后恢复通知连接，用户无需先打开应用。
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (NotifyService.shouldRun(context)) NotifyService.sync(context)
        }
    }
}
