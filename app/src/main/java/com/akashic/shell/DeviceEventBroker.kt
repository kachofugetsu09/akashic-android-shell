package com.akashic.shell

import android.app.AppOpsManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class NotificationMatchResult(val matched: Boolean, val autoCancel: Boolean)

private data class SubscriptionRule(
    val id: String,
    val enabled: Boolean,
    val adapter: String,
    val packages: Set<String>?, // null => wildcard "*"
    val excludePackages: Set<String>,
    val titleRegex: Pattern?,
    val textRegex: Pattern?,
    val ignoreOngoing: Boolean,
    val opsFilter: Set<String>?,
    val minDurationS: Int,
    val autoCancel: Boolean,
    val redact: String,
    val cooldownS: Int,
    val delivery: String,
    val expiresAtMs: Long?,
)

private class EventDbHelper(context: Context) : SQLiteOpenHelper(context, "device_events.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbox (
                event_id TEXT PRIMARY KEY,
                ts_ms INTEGER NOT NULL,
                adapter TEXT NOT NULL,
                rule_id TEXT NOT NULL,
                delivery TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_created ON outbox(created_at_ms ASC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * 端侧统一事件订阅引擎与低功耗 Outbox：
 * - 接收来自 NotificationListener、UID 2000 (ProcessObserver / AppOps / MediaSession)、
 *   系统广播 (亮灭屏/充电/网络) 与 UsageStats 断点补齐的事件。
 * - 按服务端下发的声明式规则在本地过滤、脱敏、冷却去重并写入 SQLite Outbox。
 * - realtime 事件防抖直传；batch 事件攒到解锁、切 Wi-Fi 或 JobScheduler 周期窗口再统一上传。
 */
object DeviceEventBroker {
    private const val TAG = "AkashicEventBroker"
    private const val JOB_ID = 22361
    private const val MAX_OUTBOX_ROWS = 1000
    private const val KV_RULES_JSON = "rules_json"
    private const val KV_RULES_VERSION = "rules_version"
    private const val KV_USAGE_CURSOR = "usage_cursor_ms"

    @Volatile private var appContext: Context? = null
    @Volatile private var db: EventDbHelper? = null
    @Volatile private var rulesVersion: Int = 0
    private val rules = CopyOnWriteArrayList<SubscriptionRule>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AkashicEventFlush").apply { isDaemon = true }
    }
    @Volatile private var scheduledFlush: ScheduledFuture<*>? = null
    @Volatile private var contextReceiversRegistered = false
    @Volatile private var lastNetworkSummary: String = ""
    @Volatile private var lastUid2000ForegroundAtMs: Long = 0L

    private val shellSink = object : IAkashicEventSink.Stub() {
        override fun onDeviceEvent(eventJson: String?) {
            if (eventJson.isNullOrBlank()) return
            onShellEventJson(eventJson)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val helper = EventDbHelper(app)
        db = helper
        loadPersistedRules(helper)
        registerContextAdapters(app)
        schedulePeriodicFlush(app)
        ShellHelperHub.shell?.let { attachShell(it) }
        ioExecutor.execute { catchUpUsageStats(app) }
        Log.i(TAG, "Initialized DeviceEventBroker (rules_version=$rulesVersion, rules=${rules.size})")
    }

    fun attachShell(iface: IAkashicShell) {
        lastUid2000ForegroundAtMs = 0L
        runCatching {
            iface.registerEventSink(shellSink)
            Log.i(TAG, "Attached IAkashicEventSink to UID 2000 shell")
        }.onFailure {
            Log.w(TAG, "Failed to registerEventSink on shell", it)
        }
    }

    fun applyRules(version: Int, rulesArray: JSONArray) {
        val ctx = appContext ?: return
        val helper = db ?: EventDbHelper(ctx).also { db = it }
        if (version > 0 && version < rulesVersion) return
        val parsed = parseRules(rulesArray)
        rules.clear()
        rules.addAll(parsed)
        rulesVersion = version
        runCatching {
            helper.writableDatabase.use { wdb ->
                saveKv(wdb, KV_RULES_VERSION, version.toString())
                saveKv(wdb, KV_RULES_JSON, rulesArray.toString())
            }
        }
        Log.i(TAG, "Applied subscription rules v$version (${parsed.size} rules)")
    }

    private fun loadPersistedRules(helper: EventDbHelper) {
        runCatching {
            helper.readableDatabase.use { rdb ->
                val verStr = readKv(rdb, KV_RULES_VERSION)
                val jsonStr = readKv(rdb, KV_RULES_JSON)
                if (!jsonStr.isNullOrBlank()) {
                    rulesVersion = verStr?.toIntOrNull() ?: 1
                    rules.clear()
                    rules.addAll(parseRules(JSONArray(jsonStr)))
                } else {
                    seedFallbackRules()
                }
            }
        }.onFailure {
            seedFallbackRules()
        }
    }

    private fun seedFallbackRules() {
        val defaults = JSONArray(
            """
            [
              {"id":"wechat-notifications","enabled":true,"adapter":"notification","package":"com.tencent.mm","match":{"ignore_ongoing":true},"actions":{"auto_cancel":true,"redact":"full","cooldown_s":0,"delivery":"realtime"}},
              {"id":"app-sessions","enabled":true,"adapter":"foreground","package":"*","match":{"min_duration_s":15},"actions":{"redact":"full","cooldown_s":0,"delivery":"batch"}},
              {"id":"appops-sensors","enabled":true,"adapter":"appops","package":"*","exclude_packages":["com.google.android.as"],"match":{"ops":["FINE_LOCATION","CAMERA","RECORD_AUDIO"]},"actions":{"redact":"full","cooldown_s":120,"delivery":"batch"}},
              {"id":"media-playback","enabled":true,"adapter":"media","package":"*","match":{},"actions":{"redact":"full","cooldown_s":5,"delivery":"batch"}},
              {"id":"device-context","enabled":true,"adapter":"device","package":null,"match":{},"actions":{"redact":"full","cooldown_s":5,"delivery":"batch"}}
            ]
            """.trimIndent()
        )
        rules.clear()
        rules.addAll(parseRules(defaults))
    }

    private fun parseRules(arr: JSONArray): List<SubscriptionRule> {
        val out = ArrayList<SubscriptionRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val adapter = obj.optString("adapter").trim()
            if (id.isEmpty() || adapter.isEmpty()) continue
            val pkgRaw = if (obj.isNull("package")) "*" else obj.optString("package", "*").trim()
            val exclList = ArrayList<String>()
            val exclArr = obj.optJSONArray("exclude_packages")
            if (exclArr != null) {
                for (idx in 0 until exclArr.length()) {
                    val item = exclArr.optString(idx).trim()
                    if (item.isNotEmpty()) exclList.add(item)
                }
            } else if (!obj.isNull("exclude_packages")) {
                val exclStr = obj.optString("exclude_packages").trim()
                if (exclStr.isNotEmpty()) {
                    exclStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { exclList.add(it) }
                }
            }
            val pkgFilter = EventRuleMatcher.parsePackageFilter(pkgRaw, exclList)
            val match = obj.optJSONObject("match") ?: JSONObject()
            val actions = obj.optJSONObject("actions") ?: JSONObject()
            val titleRx = match.optString("title_regex").trim().takeIf { !match.isNull("title_regex") && it.isNotEmpty() }
                ?.let { runCatching { Pattern.compile(it) }.getOrNull() }
            val textRx = match.optString("text_regex").trim().takeIf { !match.isNull("text_regex") && it.isNotEmpty() }
                ?.let { runCatching { Pattern.compile(it) }.getOrNull() }
            val opsArr = match.optJSONArray("ops")
            val opsFilter = if (opsArr != null && opsArr.length() > 0) {
                (0 until opsArr.length()).mapNotNull { idx -> opsArr.optString(idx).trim().takeIf { it.isNotEmpty() } }.toSet()
            } else null
            val expSec = if (!obj.isNull("expires_at") && obj.has("expires_at")) obj.optDouble("expires_at", -1.0) else -1.0
            out.add(
                SubscriptionRule(
                    id = id,
                    enabled = obj.optBoolean("enabled", true),
                    adapter = adapter,
                    packages = pkgFilter.includes,
                    excludePackages = pkgFilter.excludes,
                    titleRegex = titleRx,
                    textRegex = textRx,
                    ignoreOngoing = match.optBoolean("ignore_ongoing", true),
                    opsFilter = opsFilter,
                    minDurationS = match.optInt("min_duration_s", 0),
                    autoCancel = actions.optBoolean("auto_cancel", false),
                    redact = actions.optString("redact", "full"),
                    cooldownS = actions.optInt("cooldown_s", 0),
                    delivery = actions.optString("delivery", "batch"),
                    expiresAtMs = if (expSec > 0) (expSec * 1000.0).toLong() else null,
                )
            )
        }
        return out
    }

    fun onNotificationPosted(
        packageName: String,
        appName: String,
        title: String,
        text: String,
        subText: String,
        category: String,
        channelId: String,
        postTimeMs: Long,
        ongoing: Boolean,
        notificationKey: String,
    ): NotificationMatchResult {
        val nowMs = if (postTimeMs > 0) postTimeMs else System.currentTimeMillis()
        var matchedAny = false
        var autoCancel = false
        for (rule in matchingRules("notification", packageName, nowMs)) {
            if (rule.ignoreOngoing && ongoing) continue
            if (rule.titleRegex != null && !rule.titleRegex.matcher(title).find()) continue
            if (rule.textRegex != null && !rule.textRegex.matcher(text).find()) continue
            val dedupeKey = "${rule.id}:$packageName:$title:$text"
            if (!passCooldown(dedupeKey, rule.cooldownS)) continue

            matchedAny = true
            if (rule.autoCancel) autoCancel = true
            val summary = redactText(rule.redact, if (text.isNotEmpty()) text else subText)
            val payload = JSONObject()
                .put("app_name", appName)
                .put("channel_id", channelId)
                .put("category", category)
                .put("ongoing", ongoing)
            if (rule.redact != "metadata_only" && subText.isNotEmpty()) {
                payload.put("sub_text", subText.take(120))
            }
            val eventTitle = if (rule.redact == "metadata_only") appName else "${appName}${if (title.isNotEmpty()) " · $title" else ""}"
            enqueueEvent(
                eventId = "notif:${UUID.randomUUID()}",
                tsMs = nowMs,
                adapter = "notification",
                eventType = "notification_posted",
                ruleId = rule.id,
                packageName = packageName,
                title = eventTitle,
                summary = summary,
                payload = payload,
                delivery = rule.delivery,
            )
        }
        return NotificationMatchResult(matchedAny, autoCancel)
    }

    private fun onShellEventJson(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val adapter = json.optString("adapter")
        val eventType = json.optString("event_type", adapter)
        val pkg = json.optString("package_name")
        val tsMs = json.optLong("ts_ms", System.currentTimeMillis())
        val payload = json.optJSONObject("payload") ?: JSONObject()

        when (adapter) {
            "foreground" -> {
                lastUid2000ForegroundAtMs = System.currentTimeMillis()
                if (eventType == "app_session") {
                    advanceUsageCursorMonotonic(tsMs)
                }
                val durationS = payload.optInt("duration_s", 0)
                val actName = payload.optString("activity_name")
                for (rule in matchingRules("foreground", pkg, tsMs)) {
                    if (eventType == "app_session") {
                        if (durationS < rule.minDurationS) continue
                    } else if (rule.minDurationS > 0) {
                        continue
                    }
                    val label = resolveAppLabel(pkg)
                    val title = if (eventType == "app_session") "$label 停留 ${durationS}s" else "切换到 $label"
                    val summary = if (actName.isNotEmpty()) "$pkg ($actName)" else pkg
                    if (!EventRuleMatcher.matchesContent(rule.titleRegex, rule.textRegex, title, summary)) continue
                    enqueueEvent(
                        eventId = "fg:${pkg}:${tsMs}:${eventType}",
                        tsMs = tsMs,
                        adapter = "foreground",
                        eventType = eventType,
                        ruleId = rule.id,
                        packageName = pkg,
                        title = title,
                        summary = redactText(rule.redact, summary),
                        payload = payload,
                        delivery = rule.delivery,
                    )
                }
            }
            "appops" -> {
                val op = payload.optString("op")
                val active = payload.optBoolean("active", false)
                for (rule in matchingRules("appops", pkg, tsMs)) {
                    if (rule.opsFilter != null && op !in rule.opsFilter) continue
                    val label = resolveAppLabel(pkg)
                    val title = "$label ${if (active) "开启" else "结束"} $op"
                    val summary = "$op active=$active"
                    if (!EventRuleMatcher.matchesContent(rule.titleRegex, rule.textRegex, title, summary)) continue
                    if (!passCooldown("${rule.id}:$pkg:$op:$active", rule.cooldownS)) continue
                    enqueueEvent(
                        eventId = "op:${pkg}:${op}:${tsMs}:${active}",
                        tsMs = tsMs,
                        adapter = "appops",
                        eventType = eventType,
                        ruleId = rule.id,
                        packageName = pkg,
                        title = title,
                        summary = "$op active=$active",
                        payload = payload,
                        delivery = rule.delivery,
                    )
                }
            }
            "media" -> {
                onMediaStateChanged(
                    packageName = pkg,
                    stateLabel = payload.optString("state"),
                    mediaTitle = payload.optString("title"),
                    artist = payload.optString("artist"),
                    album = payload.optString("album"),
                    tsMs = tsMs,
                )
            }
            "device" -> {
                val title = payload.optString("title").ifEmpty { eventType }
                val summary = payload.optString("summary").ifEmpty { eventType }
                val cooldownSuffix = payload.optString("cooldown_key")
                val wantFlush = payload.optBoolean("flush", false)
                val cleanPayload = JSONObject(payload.toString()).apply {
                    remove("flush")
                    remove("cooldown_key")
                    remove("title")
                    remove("summary")
                }
                val enqueued = onDeviceContextEvent(eventType, title, summary, cleanPayload, cooldownSuffix)
                if (enqueued && wantFlush) {
                    flushAsync(eventType)
                }
            }
        }
    }

    fun onMediaStateChanged(
        packageName: String,
        stateLabel: String,
        mediaTitle: String,
        artist: String,
        album: String,
        tsMs: Long = System.currentTimeMillis(),
    ) {
        if (packageName.isEmpty() || stateLabel.isEmpty()) return
        val payload = JSONObject()
            .put("state", stateLabel)
            .put("title", mediaTitle)
            .put("artist", artist)
            .put("album", album)
        for (rule in matchingRules("media", packageName, tsMs)) {
            val label = resolveAppLabel(packageName)
            val title = if (mediaTitle.isNotEmpty()) "$label [$stateLabel] $mediaTitle" else "$label [$stateLabel]"
            val summary = listOf(mediaTitle, artist).filter { it.isNotEmpty() }.joinToString(" · ")
            if (!EventRuleMatcher.matchesContent(rule.titleRegex, rule.textRegex, title, summary)) continue
            if (!passCooldown("${rule.id}:$packageName:$stateLabel:$mediaTitle", rule.cooldownS)) continue
            enqueueEvent(
                eventId = "media:${UUID.randomUUID()}",
                tsMs = tsMs,
                adapter = "media",
                eventType = "media_$stateLabel",
                ruleId = rule.id,
                packageName = packageName,
                title = title,
                summary = redactText(rule.redact, summary),
                payload = payload,
                delivery = rule.delivery,
            )
        }
    }

    private fun advanceUsageCursorMonotonic(tsMs: Long) {
        val helper = db ?: return
        runCatching {
            helper.writableDatabase.use { wdb ->
                val cur = readKv(wdb, KV_USAGE_CURSOR)?.toLongOrNull() ?: 0L
                if (tsMs > cur) saveKv(wdb, KV_USAGE_CURSOR, tsMs.toString())
            }
        }
    }

    private fun onDeviceContextEvent(
        eventType: String,
        title: String,
        summary: String,
        payload: JSONObject,
        cooldownKeySuffix: String = "",
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        var enqueuedAny = false
        for (rule in matchingRules("device", "", nowMs)) {
            if (!EventRuleMatcher.matchesContent(rule.titleRegex, rule.textRegex, title, summary)) continue
            val cdKey = if (cooldownKeySuffix.isNotEmpty()) "${rule.id}:$eventType:$cooldownKeySuffix" else "${rule.id}:$eventType"
            if (!passCooldown(cdKey, rule.cooldownS)) continue
            val outTitle = if (rule.redact == "metadata_only") eventType else title
            val outSummary = redactText(rule.redact, summary)
            val outPayload = if (rule.redact == "metadata_only") JSONObject() else payload
            val idSuffix = if (cooldownKeySuffix.isNotEmpty()) ":$cooldownKeySuffix" else ""
            if (enqueueEvent(
                eventId = "dev:${rule.id}:${eventType}:${nowMs}${idSuffix}",
                tsMs = nowMs,
                adapter = "device",
                eventType = eventType,
                ruleId = rule.id,
                packageName = "",
                title = outTitle,
                summary = outSummary,
                payload = outPayload,
                delivery = rule.delivery,
            )) {
                enqueuedAny = true
            }
        }
        return enqueuedAny
    }

    private fun matchingRules(adapter: String, packageName: String, nowMs: Long): List<SubscriptionRule> {
        return rules.filter { rule ->
            EventRuleMatcher.matchesTarget(
                enabled = rule.enabled,
                ruleAdapter = rule.adapter,
                eventAdapter = adapter,
                expiresAtMs = rule.expiresAtMs,
                nowMs = nowMs,
                includes = rule.packages,
                excludes = rule.excludePackages,
                packageName = packageName,
            )
        }
    }

    private fun passCooldown(key: String, cooldownS: Int): Boolean {
        if (cooldownS <= 0) return true
        val now = SystemClock.uptimeMillis()
        val prev = cooldowns[key]
        if (prev != null && now - prev < cooldownS * 1000L) return false
        cooldowns[key] = now
        if (cooldowns.size > 512) cooldowns.clear()
        return true
    }

    private fun redactText(mode: String, raw: String): String = when (mode) {
        "metadata_only" -> ""
        "summary" -> raw.take(160)
        else -> raw.take(500)
    }

    private fun resolveAppLabel(pkg: String): String {
        val ctx = appContext ?: return pkg
        if (pkg.isEmpty()) return ""
        return runCatching {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(pkg, 0)
            }
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
    }

    private fun enqueueEvent(
        eventId: String,
        tsMs: Long,
        adapter: String,
        eventType: String,
        ruleId: String,
        packageName: String,
        title: String,
        summary: String,
        payload: JSONObject,
        delivery: String,
    ): Boolean {
        val ctx = appContext ?: return false
        val helper = db ?: EventDbHelper(ctx).also { db = it }
        val full = JSONObject()
            .put("event_id", eventId)
            .put("ts_ms", tsMs)
            .put("adapter", adapter)
            .put("event_type", eventType)
            .put("rule_id", ruleId)
            .put("package_name", packageName)
            .put("title", title.take(300))
            .put("summary", summary.take(1000))
            .put("payload", payload)

        val inserted = runCatching {
            helper.writableDatabase.use { wdb ->
                val cv = ContentValues().apply {
                    put("event_id", eventId)
                    put("ts_ms", tsMs)
                    put("adapter", adapter)
                    put("rule_id", ruleId)
                    put("delivery", delivery)
                    put("payload_json", full.toString())
                    put("created_at_ms", System.currentTimeMillis())
                }
                val rowId = wdb.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (rowId >= 0L) {
                    runCatching {
                        wdb.execSQL(
                            "DELETE FROM outbox WHERE event_id IN (SELECT event_id FROM outbox ORDER BY created_at_ms DESC LIMIT -1 OFFSET ?)",
                            arrayOf(MAX_OUTBOX_ROWS)
                        )
                    }.onFailure {
                        Log.w(TAG, "trim outbox failed", it)
                    }
                }
                rowId >= 0L
            }
        }.onFailure {
            Log.w(TAG, "enqueueEvent failed", it)
        }.getOrDefault(false)

        if (inserted && delivery == "realtime") {
            scheduleDebouncedFlush("realtime", 500L)
        }
        return inserted
    }

    fun flushAsync(reason: String) {
        scheduleDebouncedFlush(reason, 150L)
    }

    private fun scheduleDebouncedFlush(reason: String, delayMs: Long) {
        synchronized(this) {
            scheduledFlush?.cancel(false)
            scheduledFlush = ioExecutor.schedule({
                runCatching { flushNow(reason) }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    internal fun flushNow(reason: String): Boolean {
        val ctx = appContext ?: return false
        val server = ShellPrefs(ctx).serverUrl ?: return false
        val helper = db ?: return false

        val nowMs = System.currentTimeMillis()
        val helperAlive = ShellHelperHub.shell?.asBinder()?.isBinderAlive == true
        val uid2000FgHealthy = helperAlive && lastUid2000ForegroundAtMs > 0L && (nowMs - lastUid2000ForegroundAtMs) < 2 * 3600_000L
        if (!uid2000FgHealthy) {
            catchUpUsageStats(ctx)
        }

        val batch = ArrayList<Pair<String, JSONObject>>()
        runCatching {
            helper.readableDatabase.use { rdb ->
                rdb.rawQuery("SELECT event_id, payload_json FROM outbox ORDER BY created_at_ms ASC LIMIT 100", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val obj = runCatching { JSONObject(c.getString(1)) }.getOrNull() ?: continue
                        batch.add(id to obj)
                    }
                }
            }
        }
        if (batch.isEmpty()) return true

        val arr = JSONArray()
        batch.forEach { arr.put(it.second) }
        val body = JSONObject()
            .put("device_id", PhoneBridgeController.deviceId(ctx))
            .put("reason", reason)
            .put("events", arr)

        return try {
            val conn = Server.open("$server/api/phone/events", readTimeoutMs = 15_000)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", server)
                CookieManager.getInstance().getCookie(server)?.let { conn.setRequestProperty("Cookie", it) }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "flushNow($reason) failed: HTTP $code")
                    return false
                }
                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                val resp = runCatching { JSONObject(respText) }.getOrNull()
                if (resp != null && resp.has("rules") && resp.optInt("rules_version", 0) > rulesVersion) {
                    applyRules(resp.optInt("rules_version", 1), resp.getJSONArray("rules"))
                }
                helper.writableDatabase.use { wdb ->
                    wdb.beginTransaction()
                    try {
                        for ((id, _) in batch) {
                            wdb.delete("outbox", "event_id = ?", arrayOf(id))
                        }
                        wdb.setTransactionSuccessful()
                    } finally {
                        wdb.endTransaction()
                    }
                }
                Log.i(TAG, "Flushed ${batch.size} events ($reason)")
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.d(TAG, "flushNow($reason) IO deferred: ${e.message}")
            false
        }
    }

    private fun catchUpUsageStats(ctx: Context) {
        val appOps = ctx.getSystemService(AppOpsManager::class.java) ?: return
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val helper = db ?: return
        val nowMs = System.currentTimeMillis()
        val lastCursor = runCatching {
            helper.readableDatabase.use { rdb -> readKv(rdb, KV_USAGE_CURSOR)?.toLongOrNull() }
        }.getOrNull() ?: (nowMs - 3600_000L)
        val beginMs = lastCursor.coerceAtLeast(nowMs - 12 * 3600_000L)
        if (nowMs - beginMs < 30_000L) return

        val events = runCatching { usm.queryEvents(beginMs, nowMs) }.getOrNull() ?: return
        val activeSince = HashMap<String, Long>()
        val ev = UsageEvents.Event()
        var latestTs = beginMs
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            if (ev.timeStamp > latestTs) latestTs = ev.timeStamp
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> activeSince[pkg] = ev.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val started = activeSince.remove(pkg) ?: continue
                    val durS = ((ev.timeStamp - started) / 1000L).toInt()
                    for (rule in matchingRules("foreground", pkg, ev.timeStamp)) {
                        if (durS < rule.minDurationS) continue
                        val label = resolveAppLabel(pkg)
                        val title = "$label 停留 ${durS}s"
                        if (!EventRuleMatcher.matchesContent(rule.titleRegex, rule.textRegex, title, pkg)) continue
                        enqueueEvent(
                            eventId = "usage:${pkg}:${started}:${ev.timeStamp}",
                            tsMs = ev.timeStamp,
                            adapter = "foreground",
                            eventType = "usage_session",
                            ruleId = rule.id,
                            packageName = pkg,
                            title = title,
                            summary = pkg,
                            payload = JSONObject().put("duration_s", durS).put("entered_at_ms", started).put("source", "usage_stats"),
                            delivery = rule.delivery,
                        )
                    }
                }
            }
        }
        runCatching {
            helper.writableDatabase.use { wdb -> saveKv(wdb, KV_USAGE_CURSOR, latestTs.toString()) }
        }
    }

    @Synchronized
    private fun registerContextAdapters(app: Context) {
        if (contextReceiversRegistered) return
        contextReceiversRegistered = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        onDeviceContextEvent("screen_unlock", "手机已解锁", "screen_unlock", JSONObject().put("screen", "unlocked"))
                        flushAsync("unlock")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        onDeviceContextEvent("screen_off", "手机息屏", "screen_off", JSONObject().put("screen", "off"))
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        onDeviceContextEvent("charging_on", "开始充电", "charging=true", JSONObject().put("charging", true))
                        flushAsync("charging")
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        onDeviceContextEvent("charging_off", "拔掉充电器", "charging=false", JSONObject().put("charging", false))
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }

        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        handleNetworkCaps(caps)
                    }
                    override fun onLost(network: Network) {
                        lastNetworkSummary = ""
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        handleNetworkCaps(caps)
                    }
                    override fun onLost(network: Network) {
                        lastNetworkSummary = ""
                    }
                }
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }
    }

    private fun handleNetworkCaps(caps: NetworkCapabilities) {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        var ssid = ""
        var bssid = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            if (info != null) {
                val rawSsid = info.ssid?.trim('"').orEmpty()
                if (rawSsid.isNotEmpty() && rawSsid != "<unknown ssid>") ssid = rawSsid
                val rawBssid = info.bssid.orEmpty()
                if (rawBssid.isNotEmpty() && rawBssid != "02:00:00:00:00:00") bssid = rawBssid
            }
        }
        val summary = listOf(transport, ssid, bssid).filter { it.isNotEmpty() }.joinToString("|")
        if (summary == lastNetworkSummary) return
        lastNetworkSummary = summary
        val payload = JSONObject().put("transport", transport)
        if (ssid.isNotEmpty()) payload.put("ssid", ssid)
        if (bssid.isNotEmpty()) payload.put("bssid", bssid)
        val title = if (ssid.isNotEmpty()) "连接 Wi-Fi：$ssid" else "网络切换：$transport"
        onDeviceContextEvent("network_changed", title, summary, payload)
        if (transport == "wifi") flushAsync("wifi")
    }

    private fun schedulePeriodicFlush(app: Context) {
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, EventFlushJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(30 * 60_000L)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    private fun readKv(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun saveKv(db: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        db.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

class EventFlushJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        DeviceEventBroker.init(applicationContext)
        worker.execute {
            runCatching { DeviceEventBroker.flushNow("job_periodic") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
