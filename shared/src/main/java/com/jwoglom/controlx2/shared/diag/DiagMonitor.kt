package com.jwoglom.controlx2.shared.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DIAG-TEMP：断连阶段性监控（默认保留，可暂停）。
 *
 * 只做取证：记录连接状态变迁、写失败剪线、进程/服务存活、屏幕/Doze/省电状态、
 * 以及从 pumpx2 日志旁采的重连/退避关键字。不改 BLE 库、不改重连策略。
 *
 * 保留策略：普通 incident 24h 时间窗 + 50MB 封顶（先到先清）；
 * PAUSED / WRITE_CUT / CONNECT_FAILED 单独进 protected/ 永久保留，不参与清理。
 * 导出与清理：诊断页导出 zip 后才允许显式清理已导出的普通 incident。
 *
 * 设计文档：docs/bug/断连临时监控-设计.md
 */
object DiagMonitor {

    // DIAG-TEMP：独立 prefs，避免侵入上游 Prefs.kt
    private const val PREFS_NAME = "diag_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROTECTED_WARN_AT = "protected_over_at"
    private const val KEY_PROTECTED_WARN_BYTES = "protected_over_bytes"
    private const val KEY_PROTECTED_WARN_COUNT = "protected_over_count"

    private const val RING_MAX = 2000
    private const val LIVE_MAX_BYTES = 1L * 1024 * 1024
    private const val LOG_EVENT_MAX_PER_MIN = 100

    // 普通 incident：24h 或 50MB，两者先到先清
    private const val INCIDENT_WINDOW_MS = 24L * 60 * 60 * 1000
    private const val INCIDENT_TOTAL_MAX_BYTES = 50L * 1024 * 1024

    // protected incident 不自动删；超过此阈值只在 prefs/日志里提示（10MB ≈ 160 份 60KB 快照）
    private const val INCIDENT_PROTECTED_WARN_BYTES = 10L * 1024 * 1024
    private const val PROTECTED_DIR = "protected"
    private const val EXPORT_MARKER = "export-marker.json"

    private val LOCK = Any()

    @Volatile
    private var enabled = false
    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private var store: DiagStore? = null
    private var timer: ScheduledExecutorService? = null

    private val sessionId: String = UUID.randomUUID().toString().take(8)
    private val pid: Int = Process.myPid()
    private val processStartElapsedMs: Long =
        try { Process.getStartElapsedRealtime() } catch (_: Throwable) { SystemClock.elapsedRealtime() }
    private val processStartWallMs: Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - processStartElapsedMs)
    private var bootCount: Int = -1

    // ---- 内存态（只在 LOCK 内读写）----
    private val ring = ArrayDeque<DiagEvent>()
    private var connected = false
    private var connectedAtElapsed = -1L
    private var lastRxElapsed = -1L
    private var lastTxElapsed = -1L
    private var lastDisconnectElapsed = -1L
    private var lastDisconnectWall = -1L
    private var lastDisconnectStatus: String? = null
    private var lastDisconnectSource: String? = null
    private var lastHeartbeatElapsed = -1L
    private var lastFreezeElapsed = -1L
    private var fgsRunning = false
    private val minuteBuckets = LinkedHashMap<Long, Counters>()
    private val recentTx = ArrayDeque<Pair<Long, String>>()
    private val writeFailures = HashMap<String, Int>()
    private var logEventsThisMinute = 0
    private var logMinute = -1L
    private var lastLogMessage: String? = null
    private var lastLogElapsed = -1L

    private class Counters {
        var rx = 0
        var tx = 0
        val txBySource = HashMap<String, Int>()
    }

    data class DiagEvent(
        val wallMs: Long,
        val elapsedMs: Long,
        val kind: String,
        val source: String? = null,
        val detail: String? = null,
        val extra: Map<String, Any?> = emptyMap(),
    ) {
        fun toJson(): JSONObject {
            val j = JSONObject()
            j.put("t", formatWall(wallMs))
            j.put("wall", wallMs)
            j.put("el", elapsedMs)
            j.put("kind", kind)
            source?.let { j.put("src", it) }
            detail?.let { j.put("detail", it) }
            extra.forEach { (k, v) -> if (v != null) runCatching { j.put(k, v) } }
            return j
        }
    }

    data class DiagSnapshot(
        val enabled: Boolean,
        val sessionId: String,
        val pid: Int,
        val processStartWallMs: Long,
        val processAgeMs: Long,
        val bootCount: Int,
        val wallMs: Long,
        val elapsedMs: Long,
        val uptimeMs: Long,
        val deepSleepMs: Long,
        val connected: Boolean,
        val connectedForMs: Long?,
        val lastDisconnectAgoMs: Long?,
        val lastDisconnectStatus: String?,
        val lastDisconnectSource: String?,
        val lastRxAgoMs: Long?,
        val lastTxAgoMs: Long?,
        val linkState: String,
        val fgsRunning: Boolean,
        val screenOn: Boolean,
        val deviceIdle: Boolean,
        val powerSave: Boolean,
        val batteryWhitelisted: Boolean,
        val batteryPct: Int?,
        val rxLastMinute: Int,
        val txLastMinute: Int,
        val bolusPollLast10s: Int,
        val txBySourceLastMinute: Map<String, Int>,
        val writeFailures: Map<String, Int>,
        val incidentCount: Int,
        val liveBytes: Long,
        val dirPath: String,
        val heartbeatAgoMs: Long?,
    ) {
        fun toJson(): JSONObject {
            val j = JSONObject()
            j.put("enabled", enabled)
            j.put("session", sessionId)
            j.put("pid", pid)
            j.put("processStartWallMs", processStartWallMs)
            j.put("processAgeMs", processAgeMs)
            j.put("bootCount", bootCount)
            j.put("wallMs", wallMs)
            j.put("wall", formatWall(wallMs))
            j.put("elapsedMs", elapsedMs)
            j.put("uptimeMs", uptimeMs)
            j.put("deepSleepMs", deepSleepMs)
            j.put("connected", connected)
            connectedForMs?.let { j.put("connectedForMs", it) }
            lastDisconnectAgoMs?.let { j.put("lastDisconnectAgoMs", it) }
            lastDisconnectStatus?.let { j.put("lastDisconnectStatus", it) }
            lastDisconnectSource?.let { j.put("lastDisconnectSource", it) }
            lastRxAgoMs?.let { j.put("lastRxAgoMs", it) }
            lastTxAgoMs?.let { j.put("lastTxAgoMs", it) }
            j.put("linkState", linkState)
            j.put("fgsRunning", fgsRunning)
            j.put("screenOn", screenOn)
            j.put("deviceIdle", deviceIdle)
            j.put("powerSave", powerSave)
            j.put("batteryWhitelisted", batteryWhitelisted)
            batteryPct?.let { j.put("batteryPct", it) }
            j.put("rxLastMinute", rxLastMinute)
            j.put("txLastMinute", txLastMinute)
            j.put("bolusPollLast10s", bolusPollLast10s)
            j.put("txBySourceLastMinute", JSONObject(txBySourceLastMinute))
            j.put("writeFailures", JSONObject(writeFailures))
            j.put("incidentCount", incidentCount)
            j.put("liveBytes", liveBytes)
            j.put("dir", dirPath)
            heartbeatAgoMs?.let { j.put("heartbeatAgoMs", it) }
            return j
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        bootCount = runCatching {
            Settings.Global.getInt(appContext.contentResolver, "boot_count", -1)
        }.getOrDefault(-1)
        if (readEnabledFlag()) setEnabledInternal(true, persist = false)
    }

    fun isEnabled(): Boolean = enabled

    fun isInitialized(): Boolean = initialized

    fun setEnabled(context: Context, value: Boolean) {
        if (!initialized) initialize(context)
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).commit()
        setEnabledInternal(value, persist = false)
    }

    private fun readEnabledFlag(): Boolean =
        runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)

    private fun setEnabledInternal(value: Boolean, persist: Boolean) {
        if (value == enabled) return
        if (value) {
            enabled = true
            val dir = diagDir()
            dir.mkdirs()
            store = DiagStore(dir) { count, bytes -> noteProtectedOverflow(count, bytes) }.also { it.start() }
            startTimers()
            reportPreviousUncleanExit()
            record(
                DiagEvent(nowWall(), nowElapsed(), "SESSION_OPEN", "app",
                    "监控开启",
                    mapOf(
                        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "sdk" to Build.VERSION.SDK_INT,
                        "processStartWallMs" to processStartWallMs,
                        "processStartElapsedMs" to processStartElapsedMs,
                        "bootCount" to bootCount,
                    )
                )
            )
            writeLastState(clean = false)
        } else {
            record(DiagEvent(nowWall(), nowElapsed(), "SESSION_END", "app", "监控关闭"))
            enabled = false
            stopTimers()
            store?.let {
                it.flushSync()
                it.shutdown()
            }
            store = null
            writeLastStateSync(clean = true)
        }
    }

    private fun startTimers() {
        val t = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "diag-timer").apply { isDaemon = true }
        }
        t.scheduleWithFixedDelay({ runCatching { store?.flushAsync() } }, 15, 15, TimeUnit.SECONDS)
        t.scheduleWithFixedDelay({ runCatching { writeLastState(clean = false) } }, 30, 30, TimeUnit.SECONDS)
        t.scheduleWithFixedDelay({ runCatching { heartbeat() } }, 60, 60, TimeUnit.SECONDS)
        timer = t
    }

    private fun stopTimers() {
        timer?.shutdownNow()
        timer = null
    }

    /** 上次会话文件里写着 clean=false，说明进程被强杀/崩溃，而不是正常退出。 */
    private fun reportPreviousUncleanExit() {
        val prev = readPrevState() ?: return
        if (prev.optBoolean("clean", true)) return
        val prevSession = prev.optString("session", "?")
        if (prevSession == sessionId) return
        record(
            DiagEvent(nowWall(), nowElapsed(), "UNCLEAN_EXIT", "app",
                "上次会话未正常关闭（进程被杀/崩溃/重启）",
                mapOf(
                    "prevSession" to prevSession,
                    "prevPid" to prev.optInt("pid", -1),
                    "prevWall" to prev.optString("wall", "?"),
                    "prevElapsedMs" to prev.optLong("elapsedMs", -1),
                    "prevLastDisconnectAgoMs" to prev.optLong("lastDisconnectAgoMs", -1),
                    "prevLinkState" to prev.optString("linkState", "?"),
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // 埋点入口（全部 1 行调用，DIAG-TEMP）
    // ------------------------------------------------------------------

    fun onServiceEvent(kind: String, detail: String? = null) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), kind, "service", detail))
        if (kind == "ON_TASK_REMOVED" || kind == "SERVICE_DESTROY") {
            freezeInternal(kind, force = true)
        }
    }

    fun onFgs(started: Boolean) {
        if (!enabled) return
        synchronized(LOCK) { fgsRunning = started }
        if (!started) freezeInternal("FGS_STOP", force = false)
        record(DiagEvent(nowWall(), nowElapsed(), if (started) "FGS_START" else "FGS_STOP", "service", null))
    }

    fun onConnectStart(trigger: String) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "CONNECT_START", "pump", trigger))
    }

    fun onConnected(detail: String? = null) {
        if (!enabled) return
        synchronized(LOCK) {
            connected = true
            connectedAtElapsed = nowElapsed()
            if (lastRxElapsed < 0) lastRxElapsed = connectedAtElapsed
        }
        record(DiagEvent(nowWall(), nowElapsed(), "CONNECTED", "pump", detail))
    }

    fun onDisconnected(status: String?, source: String, detail: String? = null) {
        if (!enabled) return
        val el = nowElapsed()
        val wall = nowWall()
        synchronized(LOCK) {
            connected = false
            lastDisconnectElapsed = el
            lastDisconnectWall = wall
            lastDisconnectStatus = status
            lastDisconnectSource = source
        }
        val extra = HashMap<String, Any?>()
        extra["statusCode"] = hciCode(status)
        extra["connectedForMs"] = synchronized(LOCK) {
            if (connectedAtElapsed >= 0) el - connectedAtElapsed else -1L
        }
        record(DiagEvent(wall, el, "DISCONNECTED", source, detail ?: status, extra))
        freezeInternal("DISCONNECTED(${status ?: "null"})/$source", force = true)
    }

    fun onConnectFailed(status: String?) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "CONNECT_FAILED", "pump", status,
            mapOf("statusCode" to hciCode(status))))
        freezeInternal("CONNECT_FAILED(${status ?: "null"})", force = true)
    }

    fun onWriteCut(uuid: String, messageClass: String?) {
        if (!enabled) return
        synchronized(LOCK) {
            writeFailures[uuid] = (writeFailures[uuid] ?: 0) + 1
        }
        record(DiagEvent(nowWall(), nowElapsed(), "WRITE_CUT", "write-failed",
            "非授权特征写失败 → 主动剪线", mapOf("uuid" to uuid, "messageClass" to messageClass)))
        freezeInternal("WRITE_CUT", force = true)
    }

    fun onPaused(reason: String) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "PAUSED", "policy", reason))
        freezeInternal("PAUSED($reason)", force = true)
    }

    fun onAwaitUser(detail: String?) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "AWAIT_USER", "policy", detail))
        freezeInternal("AWAIT_USER", force = false)
    }

    fun onCriticalError(reason: String) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "CRITICAL_ERROR", "pump", reason))
        val lower = reason.lowercase()
        if (lower.contains("pause") || lower.contains("manual")) {
            onPaused(reason)
        }
    }

    fun onTx(messageClass: String) {
        if (!enabled) return
        val el = nowElapsed()
        synchronized(LOCK) {
            lastTxElapsed = el
            val b = minuteBucket(el)
            b.tx++
            val src = classifyTx(messageClass)
            b.txBySource[src] = (b.txBySource[src] ?: 0) + 1
            recentTx.addLast(Pair(el, src))
            while (recentTx.size > 64) recentTx.removeFirst()
        }
    }

    fun onRx(messageClass: String) {
        if (!enabled) return
        val el = nowElapsed()
        synchronized(LOCK) {
            lastRxElapsed = el
            val b = minuteBucket(el)
            b.rx++
        }
    }

    /** DebugTree 旁采：pumpx2 的重连/退避/停手只存在于日志文本里。 */
    fun onLogLine(tag: String?, message: String) {
        if (!enabled) return
        val lower = message.lowercase()
        if (!LOG_KEYWORDS.any { lower.contains(it) }) return
        val el = nowElapsed()
        synchronized(LOCK) {
            val minute = el / 60_000
            if (minute != logMinute) {
                logMinute = minute
                logEventsThisMinute = 0
            }
            if (logEventsThisMinute >= LOG_EVENT_MAX_PER_MIN) return
            if (message == lastLogMessage && el - lastLogElapsed < 1_000) return
            logEventsThisMinute++
            lastLogMessage = message
            lastLogElapsed = el
        }
        record(DiagEvent(nowWall(), el, "LIB", tag, message.take(300)))
    }

    /** App 即将 Runtime.exit(0)：同步落盘，避免最后几条丢在队列里。 */
    fun onAppExit(trigger: String) {
        if (!enabled) return
        record(DiagEvent(nowWall(), nowElapsed(), "APP_EXIT", "app", trigger))
        runCatching {
            store?.flushSync()
            writeLastStateSync(clean = true)
        }
    }

    fun heartbeat() {
        if (!enabled) return
        val el = nowElapsed()
        synchronized(LOCK) { lastHeartbeatElapsed = el }
        record(DiagEvent(nowWall(), el, "HEARTBEAT", "app", null,
            mapOf(
                "rxLastMinute" to currentBucket(el)?.rx,
                "txLastMinute" to currentBucket(el)?.tx,
                "fgs" to fgsRunning,
                "onBle" to connected,
            )
        ))
    }

    // ------------------------------------------------------------------
    // 查询 / 导出
    // ------------------------------------------------------------------

    fun snapshot(): DiagSnapshot {
        if (!initialized) return emptySnapshot()
        val el = nowElapsed()
        val wall = nowWall()
        val pm = runCatching {
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }.getOrNull()
        val battery = runCatching {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1) to
                (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
        }.getOrNull()
        val batteryPct = battery?.let { (level, scale) ->
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        }

        synchronized(LOCK) {
            val lastRxAgo = if (lastRxElapsed >= 0) el - lastRxElapsed else null
            val linkState = when {
                !enabled -> "OFF"
                connected && lastRxAgo != null && lastRxAgo <= 30_000 -> "LINK_ALIVE"
                connected -> "LINK_SILENT"
                lastDisconnectElapsed >= 0 -> "DISCONNECTED"
                else -> "NO_LINK"
            }
            val bucket = currentBucket(el)
            val bolus10s = recentTx.count { el - it.first <= 10_000 && it.second == "bolusPoll1s" }
            return DiagSnapshot(
                enabled = enabled,
                sessionId = sessionId,
                pid = pid,
                processStartWallMs = processStartWallMs,
                processAgeMs = el - processStartElapsedMs,
                bootCount = bootCount,
                wallMs = wall,
                elapsedMs = el,
                uptimeMs = SystemClock.uptimeMillis(),
                deepSleepMs = el - SystemClock.uptimeMillis(),
                connected = connected,
                connectedForMs = if (connected && connectedAtElapsed >= 0) el - connectedAtElapsed else null,
                lastDisconnectAgoMs = if (lastDisconnectElapsed >= 0) el - lastDisconnectElapsed else null,
                lastDisconnectStatus = lastDisconnectStatus,
                lastDisconnectSource = lastDisconnectSource,
                lastRxAgoMs = lastRxAgo,
                lastTxAgoMs = if (lastTxElapsed >= 0) el - lastTxElapsed else null,
                linkState = linkState,
                fgsRunning = fgsRunning,
                screenOn = pm?.isInteractive ?: false,
                deviceIdle = pm?.isDeviceIdleMode ?: false,
                powerSave = pm?.isPowerSaveMode ?: false,
                batteryWhitelisted = pm?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false,
                batteryPct = batteryPct,
                rxLastMinute = bucket?.rx ?: 0,
                txLastMinute = bucket?.tx ?: 0,
                bolusPollLast10s = bolus10s,
                txBySourceLastMinute = bucket?.txBySource?.toMap() ?: emptyMap(),
                writeFailures = writeFailures.toMap(),
                incidentCount = incidentFiles().size,
                liveBytes = liveFile()?.length() ?: 0,
                dirPath = diagDir().absolutePath,
                heartbeatAgoMs = if (lastHeartbeatElapsed >= 0) el - lastHeartbeatElapsed else null,
            )
        }
    }

    fun snapshotJson(): String = runCatching { snapshot().toJson().toString() }.getOrDefault("{}")

    private fun emptySnapshot(): DiagSnapshot = DiagSnapshot(
        enabled = false, sessionId = sessionId, pid = pid,
        processStartWallMs = processStartWallMs, processAgeMs = 0, bootCount = bootCount,
        wallMs = nowWall(), elapsedMs = nowElapsed(), uptimeMs = SystemClock.uptimeMillis(),
        deepSleepMs = 0, connected = false, connectedForMs = null, lastDisconnectAgoMs = null,
        lastDisconnectStatus = null, lastDisconnectSource = null, lastRxAgoMs = null,
        lastTxAgoMs = null, linkState = "OFF", fgsRunning = false, screenOn = false,
        deviceIdle = false, powerSave = false, batteryWhitelisted = false, batteryPct = null,
        rxLastMinute = 0, txLastMinute = 0, bolusPollLast10s = 0,
        txBySourceLastMinute = emptyMap(), writeFailures = emptyMap(), incidentCount = 0,
        liveBytes = 0, dirPath = "", heartbeatAgoMs = null,
    )

    fun recentEvents(limit: Int): List<DiagEvent> = synchronized(LOCK) {
        if (ring.isEmpty()) return emptyList()
        val all = ring.toList()
        return all.takeLast(limit.coerceAtLeast(1))
    }

    fun diagDir(): File = File(appContext.filesDir, "diag")

    fun liveFile(): File? = diagDir().let { d -> File(d, "live.jsonl").takeIf { f -> f.exists() && f.isFile } }

    fun incidentFiles(): List<File> =
        (listIncidents(diagDir()) + listIncidents(protectedIncidentDir())).sortedBy { it.name }

    fun protectedIncidentFiles(): List<File> = listIncidents(protectedIncidentDir())

    fun isProtectedIncident(file: File): Boolean = file.parentFile?.name == PROTECTED_DIR

    fun protectedIncidentDir(): File = File(diagDir(), PROTECTED_DIR)

    private fun listIncidents(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("incident-") && it.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun readIncident(nameOrNull: String?): String? {
        val file = if (nameOrNull.isNullOrBlank()) {
            incidentFiles().lastOrNull()
        } else {
            val safe = nameOrNull.substringAfterLast('/')
            listOf(File(diagDir(), safe), File(protectedIncidentDir(), safe))
                .firstOrNull { it.exists() && it.isFile }
        }
        return file?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun readLiveTail(maxLines: Int): String? {
        val file = liveFile() ?: return null
        return runCatching {
            if (file.length() <= LIVE_MAX_BYTES.coerceAtMost(2L * 1024 * 1024)) {
                file.readLines().takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek((file.length() - LIVE_MAX_BYTES).coerceAtLeast(0))
                    val bytes = ByteArray((file.length() - raf.filePointer).toInt())
                    raf.readFully(bytes)
                    bytes.toString(Charsets.UTF_8)
                        .lines()
                        .takeLast(maxLines.coerceAtLeast(1))
                        .joinToString("\n")
                }
            }
        }.getOrNull()
    }

    fun freezeNow(trigger: String) {
        if (!enabled) return
        freezeInternal(trigger, force = true)
        store?.flushAsync()
    }

    fun clearFiles() {
        runCatching {
            diagDir().listFiles()?.filterNot { it.name == PROTECTED_DIR }?.forEach { it.delete() }
        }
    }

    // ---- 导出标记与清理：只允许删「已导出 + 非 protected」的 incident ----

    fun exportMarkerFile(): File = File(diagDir(), EXPORT_MARKER)

    fun recordExportedIncidents(names: List<String>) {
        runCatching {
            val j = JSONObject()
            j.put("at", nowWall())
            j.put("files", JSONArray(names))
            exportMarkerFile().writeText(j.toString())
        }
    }

    fun exportedIncidentCount(): Int = runCatching {
        val f = exportMarkerFile()
        if (!f.exists()) 0 else JSONObject(f.readText()).optJSONArray("files")?.length() ?: 0
    }.getOrDefault(0)

    fun cleanupExportedIncidents(): Int {
        val marker = runCatching { JSONObject(exportMarkerFile().readText()) }.getOrNull() ?: return 0
        val arr = marker.optJSONArray("files") ?: return 0
        val keep = JSONArray()
        var deleted = 0
        for (i in 0 until arr.length()) {
            val name = arr.optString(i)
            if (!name.startsWith("incident-") || name.contains('/') || name.contains('\\')) {
                keep.put(name)
                continue
            }
            val f = File(diagDir(), name)
            if (!f.exists() || !f.isFile) continue
            if (f.delete()) deleted++ else keep.put(name)
        }
        if (deleted > 0) {
            runCatching {
                val j = JSONObject()
                j.put("at", marker.optLong("at"))
                j.put("files", keep)
                exportMarkerFile().writeText(j.toString())
            }
        }
        return deleted
    }

    fun exportSummary(): String {
        val s = snapshot()
        val events = recentEvents(40).reversed().joinToString("\n") {
            "${formatWall(it.wallMs)}  ${it.kind}${it.source?.let { src -> " [$src]" } ?: ""}" +
                (it.detail?.let { d -> "  $d" } ?: "")
        }
        return buildString {
            appendLine("DIAG session=${s.sessionId} pid=${s.pid} enabled=${s.enabled}")
            appendLine("linkState=${s.linkState} connected=${s.connected} connectedForMs=${s.connectedForMs}")
            appendLine("lastDisconnect=${s.lastDisconnectAgoMs}ms ago status=${s.lastDisconnectStatus} src=${s.lastDisconnectSource}")
            appendLine("lastRx=${s.lastRxAgoMs}ms lastTx=${s.lastTxAgoMs}ms heartbeat=${s.heartbeatAgoMs}ms")
            appendLine("fgs=${s.fgsRunning} screen=${s.screenOn} doze=${s.deviceIdle} powerSave=${s.powerSave} whitelisted=${s.batteryWhitelisted} battery=${s.batteryPct}")
            appendLine("rx/min=${s.rxLastMinute} tx/min=${s.txLastMinute} bolusPoll10s=${s.bolusPollLast10s}")
            appendLine("txBySource=${s.txBySourceLastMinute} writeFailures=${s.writeFailures}")
            appendLine("docs=${s.dirPath}")
            appendLine("--- events ---")
            append(events)
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** protected 区超阈值：只提示，不删除。24h 内最多记一次，避免刷日志。 */
    private fun noteProtectedOverflow(count: Int, bytes: Long) {
        runCatching {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = nowWall()
            val last = prefs.getLong(KEY_PROTECTED_WARN_AT, 0L)
            if (now - last < INCIDENT_WINDOW_MS) return
            prefs.edit()
                .putLong(KEY_PROTECTED_WARN_AT, now)
                .putLong(KEY_PROTECTED_WARN_BYTES, bytes)
                .putInt(KEY_PROTECTED_WARN_COUNT, count)
                .apply()
            Timber.w(
                "DiagMonitor: protected incidents %d files / %d bytes exceed %d; not deleting",
                count, bytes, INCIDENT_PROTECTED_WARN_BYTES
            )
        }
    }

    private val LOG_KEYWORDS = listOf(
        "reconnect", "backoff", "reconnectpaused", "manual_reinit", "await_user",
        "awaiting", "onconnectionfailed", "initial_auth_no_reply", "cancelconnection",
        "connection_timeout", "connection_failed", "resetinstance", "getinstance",
    )

    private fun nowWall(): Long = System.currentTimeMillis()

    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    private fun formatWall(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun minuteBucket(el: Long): Counters {
        val minute = el / 60_000
        val b = minuteBuckets.getOrPut(minute) { Counters() }
        if (minuteBuckets.size > 90) {
            val oldest = minuteBuckets.keys.first()
            minuteBuckets.remove(oldest)
        }
        return b
    }

    private fun currentBucket(el: Long): Counters? = minuteBuckets[el / 60_000]

    private fun classifyTx(messageClass: String): String = when {
        messageClass.contains("CurrentBolusStatus") -> "bolusPoll1s"
        messageClass.contains("HomeScreenMirror") || messageClass.contains("TempRate") -> "actions1s"
        messageClass.contains("HistoryLog") -> "historylog"
        messageClass.contains("Bulk") || messageClass.contains("BulkResponse") -> "bulk"
        else -> "other"
    }

    private fun hciCode(name: String?): Int? = when (name) {
        "CONNECTION_TIMEOUT" -> 0x08
        "REMOTE_USER" -> 0x13
        "UNSPECIFIED" -> 0x1F
        "CONNECTION_FAILED_TO_BE_ESTABLISHED" -> 0x3E
        else -> null
    }

    private fun record(event: DiagEvent) {
        if (!enabled) return
        synchronized(LOCK) {
            ring.addLast(event)
            while (ring.size > RING_MAX) ring.removeFirst()
        }
        store?.enqueue(event.toJson().toString(), immediate = event.kind in CRITICAL_KINDS)
    }

    private val CRITICAL_KINDS = setOf(
        "DISCONNECTED", "WRITE_CUT", "CONNECT_FAILED", "PAUSED", "AWAIT_USER",
        "APP_EXIT", "ON_TASK_REMOVED", "SERVICE_DESTROY", "UNCLEAN_EXIT", "SESSION_OPEN",
    )

    private fun freezeInternal(trigger: String, force: Boolean) {
        if (!enabled) return
        val el = nowElapsed()
        synchronized(LOCK) {
            if (!force && lastFreezeElapsed >= 0 && el - lastFreezeElapsed < 20_000) return
            lastFreezeElapsed = el
        }
        // DIAG-TEMP：序列化放到写线程做，GATT 回调线程不做任何文件 IO
        store?.freeze(incidentFileName(), isProtectedDiagTrigger(trigger)) { buildIncidentJson(trigger) }
        writeLastState(clean = false)
    }

    private fun buildIncidentJson(trigger: String): String {
        val j = JSONObject()
        j.put("trigger", trigger)
        j.put("generatedAt", formatWall(nowWall()))
        j.put("snapshot", snapshot().toJson())
        synchronized(LOCK) {
            val arr = JSONArray()
            ring.forEach { arr.put(it.toJson()) }
            j.put("events", arr)
            val buckets = JSONArray()
            minuteBuckets.forEach { (minute, c) ->
                val b = JSONObject()
                b.put("minuteElapsed", minute * 60_000)
                b.put("rx", c.rx)
                b.put("tx", c.tx)
                b.put("txBySource", JSONObject(c.txBySource))
                buckets.put(b)
            }
            j.put("minuteBuckets", buckets)
        }
        return j.toString()
    }

    private fun incidentFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowWall()))
        return "incident-$ts.json"
    }

    private fun writeLastState(clean: Boolean) {
        store?.writeState { lastStateJson(clean) }
    }

    private fun writeLastStateSync(clean: Boolean) {
        val text = lastStateJson(clean)
        store?.writeStateSync(text) ?: runCatching {
            val dir = diagDir().also { it.mkdirs() }
            val tmp = File(dir, "last_state.json.tmp")
            tmp.writeText(text)
            val dst = File(dir, "last_state.json")
            dst.delete()
            if (!tmp.renameTo(dst)) {
                dst.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun lastStateJson(clean: Boolean): String {
        val j = snapshot().toJson()
        j.put("clean", clean)
        return j.toString()
    }

    private fun readPrevState(): JSONObject? = runCatching {
        val f = File(diagDir(), "last_state.json")
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    // ------------------------------------------------------------------
    // 写盘（单线程 + 有界队列；GATT 回调线程只入队）
    // ------------------------------------------------------------------

    private class DiagStore(
        private val dir: File,
        private val onProtectedOverflow: (count: Int, bytes: Long) -> Unit,
    ) {
        private val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "diag-store").apply { isDaemon = true }
        }
        private val queue = ConcurrentLinkedQueue<String>()
        private val dirty = AtomicBoolean(false)
        private var liveSize = 0L
        private val writeLock = Any()

        fun start() {
            dir.mkdirs()
            liveSize = liveFile().length()
            // 一次性迁移：把旧版本已经落盘的 protected 类 incident 移到 protected/，避免被新策略清掉
            exec.execute {
                runCatching { migrateProtected() }
                runCatching { trimIncidents() }
            }
        }

        fun enqueue(line: String, immediate: Boolean) {
            queue.add(line)
            dirty.set(true)
            if (immediate) flushAsync()
        }

        fun flushAsync() {
            if (!dirty.get()) return
            exec.execute { drain() }
        }

        fun flushSync() {
            if (!queue.isEmpty()) drain()
        }

        fun writeState(supplier: () -> String) {
            exec.execute { runCatching { writeAtomically(File(dir, "last_state.json"), supplier()) } }
        }

        fun writeStateSync(text: String) {
            runCatching { writeAtomically(File(dir, "last_state.json"), text) }
        }

        fun freeze(name: String, isProtected: Boolean, supplier: () -> String) {
            exec.execute {
                runCatching {
                    val targetDir = if (isProtected) protectedDir() else dir
                    var file = File(targetDir, name)
                    var i = 2
                    while (file.exists() && i < 100) {
                        file = File(targetDir, name.removeSuffix(".json") + "-$i.json")
                        i++
                    }
                    FileOutputStream(file, false).use { it.write(supplier().toByteArray(Charsets.UTF_8)) }
                    if (isProtected) warnProtectedIfNeeded()
                    // 普通区策略：即使本次写的是 protected，也顺手把普通区按 24h/50MB 收敛一次
                    trimIncidents()
                }
            }
        }

        fun shutdown() {
            runCatching { exec.execute { drain() } }
            exec.shutdown()
            runCatching { exec.awaitTermination(5, TimeUnit.SECONDS) }
        }

        private fun drain() {
            dirty.set(false)
            if (queue.isEmpty()) return
            val batch = StringBuilder()
            while (true) {
                val line = queue.poll() ?: break
                batch.append(line).append('\n')
            }
            if (batch.isEmpty()) return
            synchronized(writeLock) {
                runCatching {
                    val bytes = batch.toString().toByteArray(Charsets.UTF_8)
                    rotateIfNeeded(bytes.size.toLong())
                    FileOutputStream(liveFile(), true).use { it.write(bytes) }
                    liveSize += bytes.size
                }
            }
        }

        private fun rotateIfNeeded(incoming: Long) {
            if (liveSize + incoming <= 1L * 1024 * 1024) return
            val live = liveFile()
            val prev = File(dir, "live.prev.jsonl")
            runCatching {
                live.delete()
                prev.delete()
                writeEmpty(live)
                liveSize = 0
            }
        }

        private fun writeEmpty(file: File) {
            FileOutputStream(file, false).close()
        }

        private fun liveFile(): File = File(dir, "live.jsonl")

        private fun protectedDir(): File = File(dir, PROTECTED_DIR).also { it.mkdirs() }

        private fun listIncidents(at: File): List<File> =
            at.listFiles()
                ?.filter { it.isFile && it.name.startsWith("incident-") && it.name.endsWith(".json") }
                ?: emptyList()

        private fun incidentTime(f: File): Long = runCatching {
            val m = Regex("^incident-(\\d{8}-\\d{6})").find(f.name) ?: return@runCatching 0L
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(m.groupValues[1])?.time ?: 0L
        }.getOrDefault(0L)

        /** 普通区：先按 24h 时间窗清，再按 50MB 总量从最旧开始清。 */
        private fun trimIncidents() {
            val now = System.currentTimeMillis()
            for (f in listIncidents(dir)) {
                val t = incidentTime(f)
                if (t > 0 && now - t > INCIDENT_WINDOW_MS) f.delete()
            }
            val remaining = listIncidents(dir).sortedBy { incidentTime(it) }
            var total = remaining.sumOf { it.length() }
            for (f in remaining) {
                if (total <= INCIDENT_TOTAL_MAX_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }

        /** 旧版本落盘的 protected 类快照：启动时迁移到 protected/，避免被新策略清掉。 */
        private fun migrateProtected() {
            val pdir = protectedDir()
            for (f in listIncidents(dir)) {
                val trigger = runCatching { JSONObject(f.readText()).optString("trigger") }.getOrDefault("")
                if (isProtectedDiagTrigger(trigger)) f.renameTo(File(pdir, f.name))
            }
        }

        /** protected 区只提示、不删除。 */
        private fun warnProtectedIfNeeded() {
            val files = listIncidents(protectedDir())
            val total = files.sumOf { it.length() }
            if (total > INCIDENT_PROTECTED_WARN_BYTES) onProtectedOverflow(files.size, total)
        }

        private fun writeAtomically(dst: File, text: String) {
            val tmp = File(dir, "${dst.name}.tmp")
            FileOutputStream(tmp, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            dst.delete()
            if (!tmp.renameTo(dst)) {
                FileOutputStream(dst, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                tmp.delete()
            }
        }
    }
}

/** PAUSED / WRITE_CUT / CONNECT_FAILED 三类单独永久保留，不参与时间窗/容量清理。 */
private fun isProtectedDiagTrigger(trigger: String): Boolean =
    trigger == "WRITE_CUT" ||
        trigger.startsWith("CONNECT_FAILED") ||
        trigger.startsWith("PAUSED")
