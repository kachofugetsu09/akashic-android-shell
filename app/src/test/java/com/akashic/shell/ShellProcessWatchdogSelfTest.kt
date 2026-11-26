package com.akashic.shell

import java.io.File
import java.nio.file.Files

object ShellProcessWatchdogSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testParseProcStatWithSpacesAndParens()
        testEnvironMarkerMatching()
        testOrphanBaselineRequiresBothRoundsAsOrphanAndRespectsMarker()
        testThermalAlertRequiresConsecutiveWindowAndCooldown()
        testHasLiveProcessInGroupAndScan()
        testBuildHelperCleanupCommandOnlyMatchesOldHelper()
        println("ShellProcessWatchdogSelfTest: all 6 assertions passed")
    }

    private fun testParseProcStatWithSpacesAndParens() {
        val uid = ShellProcessWatchdog.parseProcUid("Name:\ttr\nUid:\t2000\t2000\t2000\t2000\nGid:\t2000\n")
        check(uid == 2000) { "expected uid=2000, got $uid" }

        val line = "24036 (my (weird) tr) R 1 24030 24030 0 -1 4194304 423 0 0 0 1200 1500 0 0 20 0 1 0 146177839 1024 10"
        val snap = ShellProcessWatchdog.parseProcStat(24036L, line, 2000, hasAkashicMarker = true)
            ?: error("expected valid stat parse")
        check(snap.comm == "my (weird) tr") { "got comm=${snap.comm}" }
        check(snap.ppid == 1L && snap.pgrp == 24030L && snap.state == 'R')
        check(snap.totalTicks == 2700L && snap.startTimeTicks == 146177839L && snap.hasAkashicMarker)
    }

    private fun testEnvironMarkerMatching() {
        val hit = "PATH=/system/bin\u0000AKASHIC_SHELL_EXEC=1\u0000HOME=/\u0000".toByteArray()
        val prefixOnly = "AKASHIC_SHELL_EXEC=10\u0000OTHER=AKASHIC_SHELL_EXEC=1\u0000".toByteArray()
        check(ShellProcessWatchdog.hasEnvironEntry(hit))
        check(!ShellProcessWatchdog.hasEnvironEntry(prefixOnly))
        check(!ShellProcessWatchdog.hasEnvironEntry(ByteArray(0)))
    }

    private fun testOrphanBaselineRequiresBothRoundsAsOrphanAndRespectsMarker() {
        val wd = ShellProcessWatchdog(clkTck = 100L, reapMarkerCpuPct = 25.0, alertUnmarkedCpuPct = 50.0)
        val selfPid = 24831L
        val selfPgid = 24831L
        val activePgid = 30000L

        val t0 = 1_000_000L
        val r0 = listOf(
            // 24036: 带 AKASHIC_SHELL_EXEC=1 且已经是孤儿
            ProcStatSnapshot(24036, 1, 24030, 2000, 'R', "tr", 10_000, 100, hasAkashicMarker = true),
            // 24037: 第一轮仍有父进程（非孤儿），即使第二轮变孤儿也不应在第二轮立刻被杀
            ProcStatSnapshot(24037, 29000, 29000, 2000, 'R', "sh", 1_000, 101, hasAkashicMarker = true),
            // 24038: 合法外部孤儿后台任务，无 AKASHIC_SHELL_EXEC=1 标记，高 CPU 仅告警不强杀
            ProcStatSnapshot(24038, 1, 24038, 2000, 'R', "logcat", 1_000, 102, hasAkashicMarker = false),
            // 23000: adbd 保护
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 500, 10, hasAkashicMarker = false),
            // 30001: 活跃 execShell 进程组
            ProcStatSnapshot(30001, 1, activePgid, 2000, 'R', "sh", 100, 200, hasAkashicMarker = true),
        )
        val d0 = wd.evaluate(t0, r0, setOf(activePgid), selfPid, selfPgid, batteryTempTenths = 360)
        check(d0.findings.isEmpty())

        val t1 = t0 + 100_000L
        val r1 = listOf(
            ProcStatSnapshot(24036, 1, 24030, 2000, 'R', "tr", 17_000, 100, hasAkashicMarker = true),
            ProcStatSnapshot(24037, 1, 29000, 2000, 'R', "sh", 9_000, 101, hasAkashicMarker = true),
            ProcStatSnapshot(24038, 1, 24038, 2000, 'R', "logcat", 7_000, 102, hasAkashicMarker = false),
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 9_500, 10, hasAkashicMarker = false),
            ProcStatSnapshot(30001, 1, activePgid, 2000, 'R', "sh", 8_100, 200, hasAkashicMarker = true),
        )
        val d1 = wd.evaluate(t1, r1, setOf(activePgid), selfPid, selfPgid, batteryTempTenths = 360)
        check(d1.findings.size == 2) { "expected 2 findings, got ${d1.findings}" }
        val reap = d1.findings.first { it.proc.pid == 24036L }
        val alert = d1.findings.first { it.proc.pid == 24038L }
        check(reap.action == WatchdogAction.REAP_ORPHAN && reap.cpuPct in 69.0..71.0)
        check(alert.action == WatchdogAction.ALERT_ONLY && alert.cpuPct in 59.0..61.0)
        check(d1.findings.none { it.proc.pid == 24037L }) { "24037 must only start baseline in round 1 as orphan" }

        // 第三轮：24037 连续两轮以孤儿高 CPU 才清理；24038 已告警过不重复刷。
        val t2 = t1 + 100_000L
        val r2 = listOf(
            ProcStatSnapshot(24037, 1, 29000, 2000, 'R', "sh", 15_000, 101, hasAkashicMarker = true),
            ProcStatSnapshot(24038, 1, 24038, 2000, 'R', "logcat", 13_000, 102, hasAkashicMarker = false),
        )
        val d2 = wd.evaluate(t2, r2, emptySet(), selfPid, selfPgid, batteryTempTenths = 360)
        check(d2.findings.size == 1 && d2.findings[0].proc.pid == 24037L && d2.findings[0].action == WatchdogAction.REAP_ORPHAN)
    }

    private fun testThermalAlertRequiresConsecutiveWindowAndCooldown() {
        val wd = ShellProcessWatchdog(
            minSampleIntervalMs = 5_000L,
            maxSampleIntervalMs = 600_000L,
            thermalThresholdTenths = 420,
            thermalCooldownMs = 1_800_000L,
        )
        val d1 = wd.evaluate(10_000L, emptyList(), emptySet(), 100L, 100L, 425)
        check(d1.thermalAlertTempTenths == null)

        // 超过 maxSampleIntervalMs（比如设备深度休眠了 1 小时）不算“连续高温”，需重新观察一轮。
        val dGap = wd.evaluate(3_700_000L, emptyList(), emptySet(), 100L, 100L, 428)
        check(dGap.thermalAlertTempTenths == null) { "gap > maxSampleIntervalMs must reset consecutive state" }

        val d2 = wd.evaluate(3_880_000L, emptyList(), emptySet(), 100L, 100L, 431)
        check(d2.thermalAlertTempTenths == 431)

        val d3 = wd.evaluate(4_060_000L, emptyList(), emptySet(), 100L, 100L, 435)
        check(d3.thermalAlertTempTenths == null) { "cooldown should suppress immediate repeat" }

        // 冷却期过后（>= 3_880_000 + 1_800_000 = 5_680_000）连续两次正常间隔高温样本才再次告警。
        val d4 = wd.evaluate(5_600_000L, emptyList(), emptySet(), 100L, 100L, 426)
        check(d4.thermalAlertTempTenths == null)
        val d5 = wd.evaluate(5_780_000L, emptyList(), emptySet(), 100L, 100L, 428)
        check(d5.thermalAlertTempTenths == 428) { "after cooldown should alert again" }
    }

    private fun testHasLiveProcessInGroupAndScan() {
        val root = Files.createTempDirectory("proc-test").toFile()
        try {
            val p1 = File(root, "5001").apply { mkdirs() }
            File(p1, "status").writeText("Uid:\t2000\t2000\t2000\t2000\n")
            File(p1, "stat").writeText("5001 (sleep) S 1 5000 5000 0 -1 0 0 0 0 0 1 2 0 0 20 0 1 0 999 0 0")
            File(p1, "cmdline").writeBytes("sleep\u000030\u0000".toByteArray())
            File(p1, "environ").writeBytes("AKASHIC_SHELL_EXEC=1\u0000".toByteArray())

            val p2 = File(root, "5002").apply { mkdirs() }
            File(p2, "status").writeText("Uid:\t10287\t10287\t10287\t10287\n")
            File(p2, "stat").writeText("5002 (other) Z 1 5002 5002 0 -1 0 0 0 0 0 1 2 0 0 20 0 1 0 999 0 0")

            check(ShellProcessWatchdog.hasLiveProcessInGroup(5000L, root))
            check(!ShellProcessWatchdog.hasLiveProcessInGroup(5002L, root))

            val scanned = ShellProcessWatchdog.scanShellProcesses(root)
            check(scanned.size == 1 && scanned[0].pid == 5001L && scanned[0].hasAkashicMarker)
            val snap = ShellProcessWatchdog.readProcessSnapshot(5001L, root)
            check(snap != null && snap.startTimeTicks == 999L && snap.hasAkashicMarker)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testBuildHelperCleanupCommandOnlyMatchesOldHelper() {
        val cmd = ShellProcessWatchdog.buildHelperCleanupCommand("com.akashic.shell")
        check(!cmd.contains("/proc/")) { "must not iterate /proc/*/cmdline" }
        check(!cmd.contains("tr ")) { "must not invoke tr" }
        check(cmd.contains("com.akashic.shell") && cmd.contains(":helper"))
        check(cmd.contains("\$1!=self")) { "must exclude current shell pid" }
        check(!cmd.contains("logcat") && !cmd.contains("sleep")) { "must not target unrelated user shell tasks" }
    }
}
