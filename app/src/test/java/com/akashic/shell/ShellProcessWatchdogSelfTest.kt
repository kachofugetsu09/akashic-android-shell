package com.akashic.shell

import java.io.File
import java.nio.file.Files

object ShellProcessWatchdogSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testParseProcStatWithSpacesAndParens()
        testOrphanReapRequiresTwoSamplesAndExcludesActiveAndProtected()
        testThermalAlertRequiresTwoSamplesAndCooldown()
        testHasLiveProcessInGroupAndScan()
        testBuildHelperCleanupCommandSafe()
        println("ShellProcessWatchdogSelfTest: all 5 assertions passed")
    }

    private fun testParseProcStatWithSpacesAndParens() {
        val uid = ShellProcessWatchdog.parseProcUid("Name:\ttr\nUid:\t2000\t2000\t2000\t2000\nGid:\t2000\n")
        check(uid == 2000) { "expected uid=2000, got $uid" }

        val line = "24036 (my (weird) tr) R 1 24030 24030 0 -1 4194304 423 0 0 0 1200 1500 0 0 20 0 1 0 146177839 1024 10"
        val snap = ShellProcessWatchdog.parseProcStat(24036L, line, 2000, "tr \\0")
            ?: error("expected valid stat parse")
        check(snap.comm == "my (weird) tr") { "got comm=${snap.comm}" }
        check(snap.ppid == 1L && snap.pgrp == 24030L && snap.state == 'R')
        check(snap.totalTicks == 2700L && snap.startTimeTicks == 146177839L)
    }

    private fun testOrphanReapRequiresTwoSamplesAndExcludesActiveAndProtected() {
        val wd = ShellProcessWatchdog(clkTck = 100L, minCpuPct = 15.0)
        val selfPid = 24831L
        val selfPgid = 24831L
        val activePgid = 30000L

        val t0 = 1_000_000L
        val first = listOf(
            ProcStatSnapshot(24036, 1, 24030, 2000, 'R', "tr", 10_000, 100, "tr \\0"),
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 500, 10, "adbd"),
            ProcStatSnapshot(30001, 1, activePgid, 2000, 'R', "sh", 100, 200, "sh -c long"),
            ProcStatSnapshot(24831, 1, selfPgid, 2000, 'S', "app_process64", 800, 50, "com.akashic.shell:helper"),
            ProcStatSnapshot(40001, 9999, 40001, 2000, 'S', "sh", 200, 300, "sh"),
        )
        val d1 = wd.evaluate(t0, first, setOf(activePgid), selfPid, selfPgid, batteryTempTenths = 360)
        check(d1.kills.isEmpty() && d1.thermalAlertTempTenths == null) { "first sample must not kill" }

        // 100 秒后：24036 tr 跑了 7000 ticks (70% 单核 CPU)，adbd 跑了 5000 ticks（白名单），30001 还在 activePgids，40001 ppid!=1
        val t1 = t0 + 100_000L
        val second = listOf(
            ProcStatSnapshot(24036, 1, 24030, 2000, 'R', "tr", 17_000, 100, "tr \\0"),
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 5_500, 10, "adbd"),
            ProcStatSnapshot(30001, 1, activePgid, 2000, 'R', "sh", 5_100, 200, "sh -c long"),
            ProcStatSnapshot(24831, 1, selfPgid, 2000, 'S', "app_process64", 900, 50, "com.akashic.shell:helper"),
            ProcStatSnapshot(40001, 9999, 40001, 2000, 'S', "sh", 5_200, 300, "sh"),
        )
        val d2 = wd.evaluate(t1, second, setOf(activePgid), selfPid, selfPgid, batteryTempTenths = 360)
        check(d2.kills.size == 1 && d2.kills[0].proc.pid == 24036L) { "expected only 24036 killed, got ${d2.kills}" }
        check(d2.kills[0].safeToKillGroup) { "isolated pgrp should allow group kill" }
        check(d2.kills[0].cpuPct in 69.0..71.0) { "expected ~70%, got ${d2.kills[0].cpuPct}" }

        // 若孤儿和受保护进程 adbd 共享进程组，只杀单 pid 不杀进程组。
        val wdShared = ShellProcessWatchdog(clkTck = 100L, minCpuPct = 15.0)
        val sharedGroupFirst = listOf(
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 500, 10, "adbd"),
            ProcStatSnapshot(24099, 1, 23000, 2000, 'R', "tr", 1000, 100, "tr \\0"),
        )
        val sharedGroupSecond = listOf(
            ProcStatSnapshot(23000, 1, 23000, 2000, 'S', "adbd", 550, 10, "adbd"),
            ProcStatSnapshot(24099, 1, 23000, 2000, 'R', "tr", 8000, 100, "tr \\0"),
        )
        wdShared.evaluate(t0, sharedGroupFirst, emptySet(), selfPid, selfPgid, 360)
        val dShared = wdShared.evaluate(t1, sharedGroupSecond, emptySet(), selfPid, selfPgid, 360)
        check(dShared.kills.size == 1 && !dShared.kills[0].safeToKillGroup) {
            "shared pgrp with adbd must not kill group"
        }
    }

    private fun testThermalAlertRequiresTwoSamplesAndCooldown() {
        val wd = ShellProcessWatchdog(thermalThresholdTenths = 420, thermalCooldownMs = 1_800_000L)
        val d1 = wd.evaluate(10_000L, emptyList(), emptySet(), 100L, 100L, 425)
        check(d1.thermalAlertTempTenths == null) { "single hot sample must not alert" }

        val d2 = wd.evaluate(190_000L, emptyList(), emptySet(), 100L, 100L, 431)
        check(d2.thermalAlertTempTenths == 431) { "two consecutive hot samples should alert" }

        val d3 = wd.evaluate(370_000L, emptyList(), emptySet(), 100L, 100L, 435)
        check(d3.thermalAlertTempTenths == null) { "cooldown should suppress immediate repeat" }

        val d4 = wd.evaluate(2_000_000L, emptyList(), emptySet(), 100L, 100L, 428)
        check(d4.thermalAlertTempTenths == 428) { "after cooldown should alert again" }
    }

    private fun testHasLiveProcessInGroupAndScan() {
        val root = Files.createTempDirectory("proc-test").toFile()
        try {
            val p1 = File(root, "5001").apply { mkdirs() }
            File(p1, "status").writeText("Uid:\t2000\t2000\t2000\t2000\n")
            File(p1, "stat").writeText("5001 (sleep) S 1 5000 5000 0 -1 0 0 0 0 0 1 2 0 0 20 0 1 0 999 0 0")
            File(p1, "cmdline").writeBytes("sleep\u000030\u0000".toByteArray())

            val p2 = File(root, "5002").apply { mkdirs() }
            File(p2, "status").writeText("Uid:\t10287\t10287\t10287\t10287\n")
            File(p2, "stat").writeText("5002 (other) Z 1 5002 5002 0 -1 0 0 0 0 0 1 2 0 0 20 0 1 0 999 0 0")

            check(ShellProcessWatchdog.hasLiveProcessInGroup(5000L, root))
            check(!ShellProcessWatchdog.hasLiveProcessInGroup(5002L, root))

            val scanned = ShellProcessWatchdog.scanShellProcesses(root)
            check(scanned.size == 1 && scanned[0].pid == 5001L && scanned[0].cmdline == "sleep 30")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testBuildHelperCleanupCommandSafe() {
        val cmd = ShellProcessWatchdog.buildHelperCleanupCommand("com.akashic.shell")
        check(!cmd.contains("/proc/")) { "must not iterate /proc/*/cmdline" }
        check(!cmd.contains("tr ")) { "must not invoke tr" }
        check(cmd.contains("com.akashic.shell")) { "must reference target package" }
    }
}
