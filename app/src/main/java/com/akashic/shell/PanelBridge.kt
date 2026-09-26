package com.akashic.shell

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

// 本地面板能做的全部原生动作；远程页面拿不到这个接口。
interface PanelHost {
    fun panelState(): String
    fun connect(address: String)
    fun finishSetup()
    fun closePanel()
    fun changeServer()
    fun retry()
    fun openSettings()
    fun requestNotificationPermission()
    fun openNotificationSettings()
    fun requestBatteryExemption()
    fun finishOnboarding(enable: Boolean)
    fun setNotifications(enable: Boolean)
}

// JS 回调运行在 WebView 的桥线程上，这里统一切回主线程。
class PanelBridge(private val host: PanelHost) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun state(): String = host.panelState()
    @JavascriptInterface fun connect(address: String) = post { host.connect(address) }
    @JavascriptInterface fun finishSetup() = post { host.finishSetup() }
    @JavascriptInterface fun close() = post { host.closePanel() }
    @JavascriptInterface fun changeServer() = post { host.changeServer() }
    @JavascriptInterface fun retry() = post { host.retry() }
    @JavascriptInterface fun openSettings() = post { host.openSettings() }
    @JavascriptInterface fun requestNotificationPermission() = post { host.requestNotificationPermission() }
    @JavascriptInterface fun openNotificationSettings() = post { host.openNotificationSettings() }
    @JavascriptInterface fun requestBatteryExemption() = post { host.requestBatteryExemption() }
    @JavascriptInterface fun finishOnboarding(enable: Boolean) = post { host.finishOnboarding(enable) }
    @JavascriptInterface fun setNotifications(enable: Boolean) = post { host.setNotifications(enable) }

    private fun post(action: () -> Unit) {
        main.post(action)
    }
}
