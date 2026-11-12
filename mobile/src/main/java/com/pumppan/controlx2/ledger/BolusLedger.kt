package com.pumppan.controlx2.ledger

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内唯一的大剂量流水。**只在内存**：不写 Room、不写 DataStore、不写文件；
 * 进程重启即清空（与设置页文案「重启后清空」一致）。
 *
 * 数据来源（只读，全部是既有轮询已经在收的报文，不新增任何 BLE 请求）：
 * - [com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse]
 * - [com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusAbstractResponse]（V1 / V2）
 *
 * Compose 侧读 [version] 作为重组信号，再调 [entries] 取快照。
 */
object BolusLedger {

    private val lock = Any()
    private var tracker = track()

    /** R22：链侧订阅「开始证据」；只能读，不得在这里反写流水。 */
    private val startObservers = CopyOnWriteArrayList<(BolusStartObservation) -> Unit>()

    private val _version = mutableStateOf(0)
    val version: State<Int> get() = _version

    private fun track(
        terminalGraceMs: Long = BolusEpisodeTracker.DEFAULT_TERMINAL_GRACE_MS,
        maxEpisodes: Int = BolusEpisodeTracker.DEFAULT_MAX_EPISODES,
        adoptTerminalWithinMs: Long = BolusEpisodeTracker.DEFAULT_ADOPT_TERMINAL_MS,
        retentionMs: Long = BolusEpisodeTracker.DEFAULT_RETENTION_MS,
        purgeIntervalMs: Long = BolusEpisodeTracker.DEFAULT_PURGE_INTERVAL_MS,
    ) = BolusEpisodeTracker(
        terminalGraceMs = terminalGraceMs,
        maxEpisodes = maxEpisodes,
        adoptTerminalWithinMs = adoptTerminalWithinMs,
        retentionMs = retentionMs,
        purgeIntervalMs = purgeIntervalMs,
        onStart = { observation -> startObservers.forEach { it(observation) } },
    )

    /**
     * 注册新笔开始回调；返回移除句柄。回调在 tracker 的锁内触发，
     * 订阅方只做轻量判据（比对 bolusId 白名单），不要在里面阻塞。
     */
    fun addStartObserver(observer: (BolusStartObservation) -> Unit): () -> Unit {
        startObservers.add(observer)
        return { startObservers.remove(observer) }
    }

    /**
     * 由持有 Context 的一侧（MainActivity）在启动时注入配置。
     * 只有在流水为空时才重建，避免改设置把正在进行的笔清掉。
     */
    fun configure(
        terminalGraceMs: Long,
        maxEpisodes: Int,
        adoptTerminalWithinMs: Long,
        retentionMs: Long,
        purgeIntervalMs: Long,
    ) {
        synchronized(lock) {
            if (tracker.snapshot().isEmpty()) {
                tracker = track(
                    terminalGraceMs = terminalGraceMs,
                    maxEpisodes = maxEpisodes,
                    adoptTerminalWithinMs = adoptTerminalWithinMs,
                    retentionMs = retentionMs,
                    purgeIntervalMs = purgeIntervalMs,
                )
            }
        }
    }

    fun onCurrentBolusStatus(statusId: Int, bolusId: Int, requestedMilliUnits: Long, now: Long) {
        synchronized(lock) {
            tracker.onCurrentBolusStatus(statusId, bolusId, requestedMilliUnits, now)
            tracker.sweep(now)
            _version.value++
        }
    }

    fun onLastBolusStatus(
        bolusId: Int,
        bolusStatusId: Int,
        deliveredMilliUnits: Long,
        requestedMilliUnits: Long?,
        extendedDurationSec: Long,
        now: Long,
        reportedAtMillis: Long? = null,
    ) {
        synchronized(lock) {
            tracker.onLastBolusStatus(
                bolusId = bolusId,
                bolusStatusId = bolusStatusId,
                deliveredMilliUnits = deliveredMilliUnits,
                requestedMilliUnits = requestedMilliUnits,
                extendedDurationSec = extendedDurationSec,
                now = now,
                reportedAtMillis = reportedAtMillis,
            )
            _version.value++
        }
    }

    /** 宽限期扫描；卡片在有进行中的笔时每秒调一次。 */
    fun sweep(now: Long) {
        synchronized(lock) {
            tracker.sweep(now)
            _version.value++
        }
    }

    fun entries(): List<LedgerEntry> = synchronized(lock) { tracker.snapshot() }

    fun hasEpisode(bolusId: Int): Boolean = synchronized(lock) { tracker.hasEpisode(bolusId) }

    /** 仅供测试/诊断：清空内存。 */
    fun clear() {
        synchronized(lock) {
            tracker = track()
            _version.value++
        }
    }
}
