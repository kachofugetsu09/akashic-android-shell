package com.google.android.accessibility.selecttospeak

import com.akashic.shell.AkashicAccessibilityService

/**
 * 微信 8.0.52+ 对常规第三方无障碍服务隐藏或混淆控件树，但对系统「随选朗读」服务类名放行。
 * 通过将该类名声明为 Manifest 入口并继承 AkashicAccessibilityService，在不侵入或篡改目标应用的前提下恢复控件树读取。
 */
class SelectToSpeakService : AkashicAccessibilityService()
