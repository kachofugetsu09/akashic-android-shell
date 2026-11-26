package com.akashic.shell

import java.io.File

internal data class ProcStatSnapshot(
    val pid: Long,
    val ppid: Long,
    val pgrp: Long,
    val uid: Int,
    val state: Char,
    val comm: String,
    val totalTicks: Long,
    val startTimeTicks: Long,
    val cmdline: String = "",
)

internal data class OrphanKillCandidate(
    val proc: ProcStatSnapshot,
    val cpuPct: Double,
    val safeToKillGroup: Boolean,
)

internal data class WatchdogDecision(
    val kills: List<OrphanKillCandidate>,
    val thermalAlertTempTenths: Int?,
)

internal class ShellProcessWatchdog(
    private val clkTck: Long = 100L,
    private val minCpuPct: Double = 15.0,
    private val minSampleIntervalMs: Long = 5_000L,
    private val maxSampleIntervalMs: Long = 600_000L,
    private val thermalThresholdTenths: Int = 420,
    private val thermalCooldownMs: Long = 1_800_000L,
) {
    private data class ProcKey(val pid: Long, val startTimeTicks: Long)
    private data class PrevSample(val ticks: Long, val atMs: Long)

    private val prevSamples = HashMap<ProcKey, PrevSample>()
    private var lastThermalAbove = false
    private var lastThermalAlertMs = Long.MIN_VALUE

    fun evaluate(
        nowMs: Long,
        snapshots: List<ProcStatSnapshot>,
        activePgids: Set<Long>,
        selfPid: Long,
        selfPgid: Long,
        batteryTempTenths: Int?,
    ): WatchdogDecision {
        val nextMap = HashMap<ProcKey, PrevSample>(snapshots.size)
        val protectedPgids = HashSet<Long>()
        protectedPgids.add(selfPgid)
        for (proc in snapshots) {
            if (proc.pgrp > 1L && (proc.pid == selfPid || isProtectedProcess(proc) || proc.pgrp in activePgids)) {
                protectedPgids.add(proc.pgrp)
            }
        }
        val kills = ArrayList<OrphanKillCandidate>()

        for (proc in snapshots) {
            if (proc.uid != SHELL_UID) continue
            if (proc.pid <= 1L || proc.pid == selfPid) continue
            if (proc.state == 'Z') continue
            if (isProtectedProcess(proc)) continue

            val key = ProcKey(proc.pid, proc.startTimeTicks)
            nextMap[key] = PrevSample(proc.totalTicks, nowMs)

            if (proc.pgrp > 1L && (proc.pgrp in activePgids || proc.pgrp == selfPgid)) continue
            if (proc.ppid != 1L) continue

            val prev = prevSamples[key] ?: continue
            val dtMs = nowMs - prev.atMs
            if (dtMs < minSampleIntervalMs || dtMs > maxSampleIntervalMs) continue
            val dTicks = proc.totalTicks - prev.ticks
            if (dTicks <= 0L) continue

            val cpuPct = (dTicks.toDouble() * 1000.0) / (clkTck.toDouble() * dtMs.toDouble()) * 100.0
            if (cpuPct >= minCpuPct) {
                val safeGroup = proc.pgrp > 1L && proc.pgrp !in protectedPgids
                kills.add(OrphanKillCandidate(proc, cpuPct, safeGroup))
            }
        }

        prevSamples.clear()
        prevSamples.putAll(nextMap)

        var thermalAlert: Int? = null
        if (batteryTempTenths != null && batteryTempTenths >= thermalThresholdTenths) {
            val cooldownReady = lastThermalAlertMs == Long.MIN_VALUE || (nowMs - lastThermalAlertMs) >= thermalCooldownMs
            if (lastThermalAbove && cooldownReady) {
                thermalAlert = batteryTempTenths
                lastThermalAlertMs = nowMs
            }
            lastThermalAbove = true
        } else {
            lastThermalAbove = false
        }

        return WatchdogDecision(kills = kills, thermalAlertTempTenths = thermalAlert)
    }

    companion object {
        const val SHELL_UID = 2000

        private val PROTECTED_COMMS = setOf(
            "adbd",
            "abb",
            "abb_exec",
            "app_process",
            "app_process32",
            "app_process64",
            "main",
        )

        private val CLEANUP_ORPHAN_COMMS = listOf(
            "tr", "grep", "egrep", "fgrep", "sed", "awk", "cat", "sh", "top", "dumpsys", "logcat", "sleep",
        )

        fun isProtectedProcess(proc: ProcStatSnapshot): Boolean {
            if (proc.comm in PROTECTED_COMMS) return true
            if (proc.cmdline.contains(":helper") || proc.comm.endsWith(":helper")) return true
            if (proc.cmdline.startsWith("adbd") || proc.cmdline == "abb" || proc.cmdline == "abb_exec") return true
            return false
        }

        fun parseProcUid(statusText: String): Int? {
            for (line in statusText.lineSequence()) {
                if (line.startsWith("Uid:")) {
                    val first = line.substring(4).trim().split(Regex("\\s+")).firstOrNull()
                    return first?.toIntOrNull()
                }
            }
            return null
        }

        fun parseProcStat(pid: Long, statLine: String, uid: Int, cmdline: String = ""): ProcStatSnapshot? {
            val lParen = statLine.indexOf('(')
            val rParen = statLine.lastIndexOf(')')
            if (lParen < 0 || rParen <= lParen || rParen + 2 >= statLine.length) return null
            val comm = statLine.substring(lParen + 1, rParen)
            val rest = statLine.substring(rParen + 1).trim().split(Regex("\\s+"))
            if (rest.size < 20) return null
            val state = rest[0].firstOrNull() ?: return null
            val ppid = rest[1].toLongOrNull() ?: return null
            val pgrp = rest[2].toLongOrNull() ?: return null
            val utime = rest[11].toLongOrNull() ?: return null
            val stime = rest[12].toLongOrNull() ?: return null
            val startTime = rest[19].toLongOrNull() ?: return null
            return ProcStatSnapshot(
                pid = pid,
                ppid = ppid,
                pgrp = pgrp,
                uid = uid,
                state = state,
                comm = comm,
                totalTicks = utime + stime,
                startTimeTicks = startTime,
                cmdline = cmdline,
            )
        }

        fun scanShellProcesses(procRoot: File = File("/proc")): List<ProcStatSnapshot> {
            val entries = procRoot.listFiles() ?: return emptyList()
            val out = ArrayList<ProcStatSnapshot>()
            for (entry in entries) {
                val pid = entry.name.toLongOrNull() ?: continue
                if (pid <= 1L) continue
                val statusText = runCatching { File(entry, "status").readText() }.getOrNull() ?: continue
                val uid = parseProcUid(statusText) ?: continue
                if (uid != SHELL_UID) continue
                val statLine = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
                val cmdline = runCatching {
                    File(entry, "cmdline").readBytes().let { raw ->
                        String(raw, Charsets.UTF_8).replace('\u0000', ' ').trim()
                    }
                }.getOrDefault("")
                parseProcStat(pid, statLine, uid, cmdline)?.let { out.add(it) }
            }
            return out
        }

        fun hasLiveProcessInGroup(pgid: Long, procRoot: File = File("/proc")): Boolean {
            if (pgid <= 1L) return false
            val entries = procRoot.listFiles() ?: return false
            for (entry in entries) {
                val pid = entry.name.toLongOrNull() ?: continue
                if (pid <= 1L) continue
                val statLine = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
                val snap = parseProcStat(pid, statLine, SHELL_UID) ?: continue
                if (snap.pgrp == pgid && snap.state != 'Z') return true
            }
            return false
        }

        fun readBatteryTempTenths(tempFile: File = File("/sys/class/power_supply/battery/temp")): Int? =
            runCatching { tempFile.readText().trim().toIntOrNull() }.getOrNull()

        fun buildHelperCleanupCommand(pkg: String): String {
            require(pkg.matches(Regex("[A-Za-z0-9_.]+"))) { "非法包名: $pkg" }
            val commCond = CLEANUP_ORPHAN_COMMS.joinToString("||") { "cmd==\"$it\"" }
            // 单次 ps + awk 精确清理旧 :helper 及 PPID=1 孤儿工具进程，不再逐个打开 /proc/*/cmdline 调 tr。
            return "ps -Ao pid,ppid,user,args 2>/dev/null | " +
                "awk -v pkg='$pkg' '\$3==\"shell\" {cmd=\$4; sub(/^.*\\//, \"\", cmd); " +
                "if (\$4==(pkg \":helper\") || (\$2==1 && ($commCond))) print \$1}' | " +
                "while read -r opid; do kill -9 \"\$opid\" 2>/dev/null; done"
        }
    }
}
