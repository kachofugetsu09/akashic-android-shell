package com.akashic.shell

/**
 * 专门承接系统 Contextual Search（按住底部横条）的非导出入口。
 * 按 AOSP ContextualSearchManager 合同，目标 Activity 必须 exported=false，
 * 只能由系统特权进程启动，第三方应用无法直接拉起。
 */
class ContextualSearchActivity : AssistActivity() {
    override fun isTrustedInvokeAction(action: String?): Boolean =
        action == ACTION_LAUNCH_CONTEXTUAL_SEARCH
}
