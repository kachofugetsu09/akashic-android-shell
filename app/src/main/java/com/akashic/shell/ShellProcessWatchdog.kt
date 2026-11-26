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
    val hasAkashicMarker: Boolean = false,
)

internal enum class WatchdogAction {
    REAP_ORPHAN,
    ALERT_ONLY,
}

internal data class OrphanFinding(
    val proc: ProcStatSnapshot,
    val cpuPct: Double,
    val action: WatchdogAction,
)

internal data class WatchdogDecision(
    val findings: List<OrphanFinding>,
    val thermalAlertTempTenths: Int?,
)

internal class ShellProcessWatchdog(
    private val clkTck: Long = 100L,
    private val reapMarkerCpuPct: Double = 25.0,
    private val alertUnmarkedCpuPct: Double = 50.0,
    private val minSampleIntervalMs: Long = 5_000L,
    private val maxSampleIntervalMs: Long = 600_000L,
    private val thermalThresholdTenths: Int = 420,
    private val thermalCooldownMs: Long = 1_800_000L,
) {
    private data class ProcKey(val pid: Long, val startTimeTicks: Long)
    private data class PrevSample(val ticks: Long, val atMs: Long)

    private val prevOrphans = HashMap<ProcKey, PrevSample>()
    private val alertedUnknown = HashSet<ProcKey>()
    private var lastHotMonoMs = Long.MIN_VALUE
    private var lastThermalAlertMonoMs = Long.MIN_VALUE

    /**
     * monotonicMs 必须使用单调时钟（如 SystemClock.elapsedRealtime()）。
     * 仅对两轮采样周期内全程满足 PPID=1 且非受保护、非活跃组的孤儿进程建立 CPU 基线，
     * 避免把“刚脱离父进程/刚结束命令”之前的正常 CPU 算作孤儿高负载。
     */
    fun evaluate(
        monotonicMs: Long,
        snapshots: List<ProcStatSnapshot>,
        activePgids: Set<Long>,
        selfPid: Long,
        selfPgid: Long,
        batteryTempTenths: Int?,
    ): WatchdogDecision {
        val nextOrphans = HashMap<ProcKey, PrevSample>()
        val nextAlerted = HashSet<ProcKey>()
        val findings = ArrayList<OrphanFinding>()

        for (proc in snapshots) {
            if (proc.uid != SHELL_UID) continue
            if (proc.pid <= 1L || proc.pid == selfPid) continue
            if (proc.state == 'Z') continue
            if (isProtectedProcess(proc)) continue
            if (proc.pgrp > 1L && (proc.pgrp in activePgids || proc.pgrp == selfPgid)) continue
            if (proc.ppid != 1L) continue

            val key = ProcKey(proc.pid, proc.startTimeTicks)
            nextOrphans[key] = PrevSample(proc.totalTicks, monotonicMs)
            if (key in alertedUnknown) nextAlerted.add(key)

            val prev = prevOrphans[key] ?: continue
            val dtMs = monotonicMs - prev.atMs
            if (dtMs < minSampleIntervalMs || dtMs > maxSampleIntervalMs) continue
            val dTicks = proc.totalTicks - prev.ticks
            if (dTicks <= 0L) continue

            val cpuPct = (dTicks.toDouble() * 1000.0) / (clkTck.toDouble() * dtMs.toDouble()) * 100.0
            if (proc.hasAkashicMarker) {
                if (cpuPct >= reapMarkerCpuPct) {
                    findings.add(OrphanFinding(proc, cpuPct, WatchdogAction.REAP_ORPHAN))
                }
            } else if (cpuPct >= alertUnmarkedCpuPct && key !in nextAlerted) {
                nextAlerted.add(key)
                findings.add(OrphanFinding(proc, cpuPct, WatchdogAction.ALERT_ONLY))
            }
        }

        prevOrphans.clear()
        prevOrphans.putAll(nextOrphans)
        alertedUnknown.clear()
        alertedUnknown.addAll(nextAlerted)

        var thermalAlert: Int? = null
        if (batteryTempTenths != null && batteryTempTenths >= thermalThresholdTenths) {
            val consecutive = lastHotMonoMs != Long.MIN_VALUE &&
                (monotonicMs - lastHotMonoMs) in minSampleIntervalMs..maxSampleIntervalMs
            val cooldownReady = lastThermalAlertMonoMs == Long.MIN_VALUE ||
                (monotonicMs - lastThermalAlertMonoMs) >= thermalCooldownMs
            if (consecutive && cooldownReady) {
                thermalAlert = batteryTempTenths
                lastThermalAlertMonoMs = monotonicMs
            }
            lastHotMonoMs = monotonicMs
        } else {
            lastHotMonoMs = Long.MIN_VALUE
        }

        return WatchdogDecision(findings = findings, thermalAlertTempTenths = thermalAlert)
    }

    companion object {
        const val SHELL_UID = 2000
        const val EXEC_ENV_KEY = "AKASHIC_SHELL_EXEC"
        const val EXEC_ENV_ENTRY = "$EXEC_ENV_KEY=1"

        private val PROTECTED_COMMS = setOf(
            "adbd",
            "abb",
            "abb_exec",
            "app_process",
            "app_process32",
            "app_process64",
            "main",
        )

        fun isProtectedProcess(proc: ProcStatSnapshot): Boolean {
            if (proc.comm in PROTECTED_COMMS) return true
            if (proc.comm.endsWith(":helper")) return true
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

        fun parseProcStat(
            pid: Long,
            statLine: String,
            uid: Int,
            hasAkashicMarker: Boolean = false,
        ): ProcStatSnapshot? {
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
                hasAkashicMarker = hasAkashicMarker,
            )
        }

        fun hasEnvironEntry(environBytes: ByteArray, entry: String = EXEC_ENV_ENTRY): Boolean {
            if (environBytes.isEmpty()) return false
            val target = entry.toByteArray(Charsets.UTF_8)
            var start = 0
            while (start < environBytes.size) {
                var end = start
                while (end < environBytes.size && environBytes[end] != 0.toByte()) {
                    end++
                }
                if (end - start == target.size) {
                    var match = true
                    for (i in target.indices) {
                        if (environBytes[start + i] != target[i]) {
                            match = false
                            break
                        }
                    }
                    if (match) return true
                }
                start = end + 1
            }
            return false
        }

        fun readProcessSnapshot(pid: Long, procRoot: File = File("/proc")): ProcStatSnapshot? {
            if (pid <= 1L) return null
            val entry = File(procRoot, pid.toString())
            val statusText = runCatching { File(entry, "status").readText() }.getOrNull() ?: return null
            val uid = parseProcUid(statusText) ?: return null
            val statLine = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return null
            val marked = runCatching { hasEnvironEntry(File(entry, "environ").readBytes()) }.getOrDefault(false)
            return parseProcStat(pid, statLine, uid, marked)
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
                if (cmdline.contains(":helper") || cmdline.startsWith("adbd") || cmdline == "abb" || cmdline == "abb_exec") {
                    continue
                }
                val marked = runCatching { hasEnvironEntry(File(entry, "environ").readBytes()) }.getOrDefault(false)
                parseProcStat(pid, statLine, uid, marked)?.let { out.add(it) }
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

        // 只清理旧的 $pkg:helper 进程：用单次 ps + awk 精确匹配 argv[0]，
        // 不再遍历全系统 proc cmdline 调 tr，也不误杀用户的其他 shell 后台任务。
        fun buildHelperCleanupCommand(pkg: String): String {
            require(pkg.matches(Regex("[A-Za-z0-9_.]+"))) { "非法包名: $pkg" }
            return "ps -Ao pid,ppid,user,args 2>/dev/null | " +
                "awk -v pkg='$pkg' -v self=\$\$ 'NR>1 && \$1!=self && \$3==\"shell\" && \$4==(pkg \":helper\") {print \$1}' | " +
                "while read -r opid; do kill -9 \"\$opid\" 2>/dev/null; done"
        }
    }
}
