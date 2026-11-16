package com.akashic.shell

object ChangeWaitSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testImmediateTransitionSettlesEarly()
        testNoisyEventsWaitUntilQuiet()
        testContinuousTickerHitsCapSafely()
        testLaunchRequiresNonEmptyNodes()
        println("ChangeWaitSelfTest: all 4 assertions passed")
    }

    private fun testImmediateTransitionSettlesEarly() {
        var now = 1000L
        var lastEvent = 1040L
        val before = ScreenFingerprint(hash = 111L, nodeCount = 12)
        val after = ScreenFingerprint(hash = 222L, nodeCount = 14)

        val res = ChangeWait.awaitStableChange(
            before = before,
            spec = ChangeWaitSpec.DEFAULT,
            nowMs = { now },
            sleepMs = { dt -> now += dt },
            quietForMs = { cur -> cur - lastEvent },
            readFingerprint = { if (now >= 1040L) after else before },
        )
        check(res.changed) { "expected changed=true" }
        check(res.settled) { "expected settled=true" }
        check(!res.timedOut) { "expected timedOut=false" }
        check(res.waitedMs == 230L) { "expected waitedMs=230ms (vs fixed 480ms), got ${res.waitedMs}" }
    }

    private fun testNoisyEventsWaitUntilQuiet() {
        var now = 0L
        var lastEvent = 0L
        val before = ScreenFingerprint(hash = 1L, nodeCount = 5)
        val after = ScreenFingerprint(hash = 2L, nodeCount = 8)

        val res = ChangeWait.awaitStableChange(
            before = before,
            spec = ChangeWaitSpec.DEFAULT,
            nowMs = { now },
            sleepMs = { dt ->
                now += dt
                if (now <= 160L) lastEvent = now
            },
            quietForMs = { cur -> cur - lastEvent },
            readFingerprint = { if (now >= 100L) after else before },
        )
        check(res.changed && res.settled && !res.timedOut)
        check(res.waitedMs == 370L) { "expected waitedMs=370ms after animation ends at 160ms, got ${res.waitedMs}" }
    }

    private fun testContinuousTickerHitsCapSafely() {
        var now = 0L
        var tick = 0L
        val before = ScreenFingerprint(hash = 1L, nodeCount = 10)

        val res = ChangeWait.awaitStableChange(
            before = before,
            spec = ChangeWaitSpec.DEFAULT,
            nowMs = { now },
            sleepMs = { dt -> now += dt },
            quietForMs = { _ -> 10L },
            readFingerprint = {
                tick += 1
                ScreenFingerprint(hash = 100L + tick, nodeCount = 10)
            },
        )
        check(res.changed)
        check(!res.settled)
        check(res.timedOut)
        check(res.waitedMs == ChangeWaitSpec.DEFAULT.capMs) {
            "expected cap ${ChangeWaitSpec.DEFAULT.capMs}, got ${res.waitedMs}"
        }
    }

    private fun testLaunchRequiresNonEmptyNodes() {
        var now = 0L
        var lastEvent = 0L
        val before = ScreenFingerprint(hash = 10L, nodeCount = 5)
        val emptyNew = ScreenFingerprint(hash = 20L, nodeCount = 0)
        val loaded = ScreenFingerprint(hash = 30L, nodeCount = 15)

        val res = ChangeWait.awaitStableChange(
            before = before,
            spec = ChangeWaitSpec.LAUNCH,
            requireNonEmpty = true,
            nowMs = { now },
            sleepMs = { dt ->
                now += dt
                if (now == 310L) lastEvent = 310L
            },
            quietForMs = { cur -> cur - lastEvent },
            readFingerprint = { if (now < 310L) emptyNew else loaded },
        )
        check(res.changed && res.settled && !res.timedOut)
        check(res.waitedMs < 700L) { "expected launch wait < 700ms (vs old 1100-1900ms), got ${res.waitedMs}" }
    }
}
