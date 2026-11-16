package com.akashic.shell

/**
 * 动态 UI 稳定等待（借鉴 Mika ChangeWait 策略）：
 * 动作发出后不固定死睡 480ms/1100ms，而是结合「节点树指纹变化 + 无障碍事件安静窗口」
 * 在画面已切换并稳定时立即放行；若页面静止或持续高频刷新（如秒表），在 capMs 处兜底返回。
 */
data class ChangeWaitSpec(
    val minWaitMs: Long = 90L,
    val quietMs: Long = 180L,
    val pollMs: Long = 70L,
    val capMs: Long = 480L,
    val stableHits: Int = 2,
) {
    companion object {
        val DEFAULT = ChangeWaitSpec()
        val LAUNCH = ChangeWaitSpec(
            minWaitMs = 150L,
            quietMs = 220L,
            pollMs = 80L,
            capMs = 1_500L,
            stableHits = 2,
        )
    }
}

data class ScreenFingerprint(
    val hash: Long,
    val nodeCount: Int,
)

data class ChangeWaitResult(
    val waitedMs: Long,
    val changed: Boolean,
    val settled: Boolean,
    val timedOut: Boolean,
    val polls: Int,
)

object ChangeWait {
    fun awaitStableChange(
        before: ScreenFingerprint,
        spec: ChangeWaitSpec = ChangeWaitSpec.DEFAULT,
        requireNonEmpty: Boolean = false,
        nowMs: () -> Long,
        sleepMs: (Long) -> Unit,
        quietForMs: (Long) -> Long,
        readFingerprint: () -> ScreenFingerprint,
    ): ChangeWaitResult {
        val started = nowMs()
        val deadline = started + spec.capMs.coerceAtLeast(0L)
        if (spec.minWaitMs > 0L) {
            val firstSleep = minOf(spec.minWaitMs, (deadline - nowMs()).coerceAtLeast(0L))
            if (firstSleep > 0L) sleepMs(firstSleep)
        }

        var lastSeen: ScreenFingerprint? = null
        var consecutiveSame = 0
        var everChanged = false
        var polls = 0

        while (true) {
            val now = nowMs()
            val cur = readFingerprint()
            polls += 1

            val isChanged = cur != before
            if (isChanged) everChanged = true

            if (lastSeen == cur) {
                consecutiveSame += 1
            } else {
                lastSeen = cur
                consecutiveSame = 1
            }

            val quiet = quietForMs(now)
            val contentReady = !requireNonEmpty || cur.nodeCount > 0
            if (isChanged && contentReady && consecutiveSame >= spec.stableHits && quiet >= spec.quietMs) {
                return ChangeWaitResult(
                    waitedMs = (now - started).coerceAtLeast(0L),
                    changed = true,
                    settled = true,
                    timedOut = false,
                    polls = polls,
                )
            }

            val remaining = deadline - now
            if (remaining <= 0L) {
                return ChangeWaitResult(
                    waitedMs = (now - started).coerceAtLeast(0L),
                    changed = everChanged,
                    settled = false,
                    timedOut = true,
                    polls = polls,
                )
            }
            sleepMs(minOf(spec.pollMs.coerceAtLeast(1L), remaining))
        }
    }
}
