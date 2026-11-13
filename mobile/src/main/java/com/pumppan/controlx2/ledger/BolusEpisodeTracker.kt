package com.pumppan.controlx2.ledger

import com.pumppan.controlx2.bolus.BolusTerminalStatus

/**
 * 大剂量「笔级状态机」。
 *
 * 设计依据 `docs/feature/通知页-流水账-设计.md` §19，全部规则来自 2026-11-12 两笔真机取证：
 * - 一笔 6 U 会产生 **57 条** `CurrentBolusStatusResponse`，直接入流水会变成 57 张卡；
 * - 输注期间泵会**持续回放上一笔的 `COMPLETE`**（陈旧终态）；
 * - 终态先以 **V1**（无 requestedVolume）到达，V2 稍后才到；
 * - 空态不是中止证据（实测空态→终态 20 s / 35 s）。
 *
 * 本类不做任何 Android / Compose 依赖，可直接用 JVM 单测覆盖上述三种陷阱。
 */
class BolusEpisodeTracker(
    private val terminalGraceMs: Long = DEFAULT_TERMINAL_GRACE_MS,
    private val maxEpisodes: Int = DEFAULT_MAX_EPISODES,
    /** 只收「最近这么久内结束」的补录笔；防止启动时回放几天前的旧终态被当成本次流水。 */
    private val adoptTerminalWithinMs: Long = DEFAULT_ADOPT_TERMINAL_MS,
    /** 已终态条目的保留时长；超过即丢弃，避免长期不重启时内存无限增长。 */
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    /** 过期清理的最小间隔，避免每条报文都做一次全表扫描。 */
    private val purgeIntervalMs: Long = DEFAULT_PURGE_INTERVAL_MS,
    /** 新笔「开始证据」回调：R22 的外来大剂量判定只订阅这里，不自行解析报文。 */
    private val onStart: ((BolusStartObservation) -> Unit)? = null,
) {
    private var lastPurgeAtMillis: Long = 0L
    private val episodes = LinkedHashMap<Int, LedgerEntry>()

    /**
     * 喂入 `CurrentBolusStatusResponse`。
     *
     * @param statusId `CurrentBolusStatus` 的 id：2=REQUESTING，1=DELIVERING，0=无活动大剂量
     * @param bolusId  协议里的 bolusId；0 表示空态
     * @param requestedMilliUnits `requestedVolume`，毫单位
     */
    fun onCurrentBolusStatus(
        statusId: Int,
        bolusId: Int,
        requestedMilliUnits: Long,
        now: Long,
    ) {
        // 空态：只是「当前没有活动大剂量」，不是中止证据（§19.2 规则 3）。
        if (bolusId == 0) {
            val ongoing = episodes.values.firstOrNull { it.isOngoing } ?: return
            if (ongoing.emptySinceMillis == null) {
                episodes[ongoing.bolusId] = ongoing.copy(emptySinceMillis = now)
            }
            return
        }

        val phase: BolusLedgerPhase? = when (statusId) {
            STATUS_REQUESTING -> BolusLedgerPhase.REQUESTING
            STATUS_DELIVERING -> BolusLedgerPhase.DELIVERING
            else -> null
        }

        // statusId = 0（ALREADY_DELIVERED_OR_INVALID）：泵在输注期间会**持续回放上一笔的
        // COMPLETE**，报文 bolusId 非 0 却没有「开始」语义。它绝不能当作新笔的开始证据——
        // 否则定时大剂量链会拿上一笔的剂量去比对首笔计划，误判 FOREIGN_BOLUS 整链作废。
        if (phase == null) {
            val replay = episodes[bolusId] ?: return
            episodes[bolusId] = replay.copy(
                lastSeenAtMillis = now,
                requestedMilliUnits = replay.requestedMilliUnits
                    ?: requestedMilliUnits.takeIf { it > 0 },
            )
            return
        }

        val existing = episodes[bolusId]
        if (existing == null) {
            episodes[bolusId] = LedgerEntry(
                key = keyOf(bolusId),
                bolusId = bolusId,
                phase = phase,
                requestedMilliUnits = requestedMilliUnits.takeIf { it > 0 },
                startedAtMillis = now,
                lastSeenAtMillis = now,
            )
            // 只在「第一次见到这笔」时通知；同 id 后续 57 条轮询不重复触发。
            onStart?.invoke(
                BolusStartObservation(
                    bolusId = bolusId,
                    statusId = statusId,
                    phase = phase,
                    observedAtMillis = now,
                    requestedMilliunits = requestedMilliUnits.takeIf { it > 0 },
                ),
            )
            evictIfNeeded()
            return
        }

        // 同 id 的重复报文只更新状态与时间，不新增条目。
        episodes[bolusId] = existing.copy(
            phase = phase,
            lastSeenAtMillis = now,
            requestedMilliUnits = existing.requestedMilliUnits
                ?: requestedMilliUnits.takeIf { it > 0 },
            emptySinceMillis = null,
        )
    }

    /**
     * 喂入 `LastBolusStatusResponse`（V1）或 `LastBolusStatusV2Response`（V2）。
     *
     * @param requestedMilliUnits 仅 V2 提供；V1 传 null
     * @param extendedDurationSec `extendedBolusDuration`，> 0 才判定延展
     * @param reportedAtMillis 泵侧记录的完成时刻（已换算本地时区）；用于补录窗口判定与展示。
     *   null 表示调用方拿不到泵时间，按 [now] 处理。
     */
    fun onLastBolusStatus(
        bolusId: Int,
        bolusStatusId: Int,
        deliveredMilliUnits: Long,
        requestedMilliUnits: Long?,
        extendedDurationSec: Long,
        now: Long,
        reportedAtMillis: Long? = null,
    ) {
        if (bolusId == 0) return

        val existing = episodes[bolusId]
        val endedAt = reportedAtMillis ?: now

        // 从未见过开笔（App 中途启动 / 抓取晚到）：只在「刚结束」时补录，
        // 否则每次启动都会被泵回放的旧终态塞进一条并不属于本次运行的流水。
        if (existing == null) {
            if (reportedAtMillis != null && now - reportedAtMillis > adoptTerminalWithinMs) {
                return
            }
            episodes[bolusId] = LedgerEntry(
                key = keyOf(bolusId),
                bolusId = bolusId,
                phase = BolusLedgerPhase.FINISHED,
                requestedMilliUnits = requestedMilliUnits?.takeIf { it > 0 },
                deliveredMilliUnits = deliveredMilliUnits.takeIf { it > 0 },
                startedAtMillis = endedAt,
                lastSeenAtMillis = now,
                terminalAtMillis = endedAt,
                terminalStatusId = bolusStatusId.takeIf { isKnownTerminal(it) },
                extendedDurationSec = extendedDurationSec,
            )
            evictIfNeeded()
            return
        }

        // 终态只落一次；已落定后的重复到达只累加计数、补齐交付量（§19.2 规则 2）。
        if (existing.terminalStatusId != null) {
            episodes[bolusId] = existing.copy(
                lastSeenAtMillis = now,
                deliveredMilliUnits = maxOfNullable(existing.deliveredMilliUnits, deliveredMilliUnits),
                requestedMilliUnits = existing.requestedMilliUnits
                    ?: requestedMilliUnits?.takeIf { it > 0 },
                repeatCount = existing.repeatCount + 1,
            )
            return
        }

        val known = isKnownTerminal(bolusStatusId)
        episodes[bolusId] = existing.copy(
            phase = if (known) BolusLedgerPhase.FINISHED else BolusLedgerPhase.UNKNOWN,
            lastSeenAtMillis = now,
            terminalAtMillis = endedAt,
            terminalStatusId = bolusStatusId.takeIf { known },
            deliveredMilliUnits = maxOfNullable(existing.deliveredMilliUnits, deliveredMilliUnits),
            requestedMilliUnits = existing.requestedMilliUnits
                ?: requestedMilliUnits?.takeIf { it > 0 },
            extendedDurationSec = if (extendedDurationSec > 0) extendedDurationSec else existing.extendedDurationSec,
            emptySinceMillis = null,
        )
    }

    /**
     * 宽限期扫描：空态后 [terminalGraceMs] 内没有终态 → 收成「未确认结束」。
     * 迟到的终态在 [onLastBolusStatus] 里回填同一笔（phase 由 UNKNOWN 升为 FINISHED）。
     */
    fun sweep(now: Long) {
        val changed = episodes.values
            .filter { entry ->
                val emptySince = entry.emptySinceMillis
                entry.isOngoing && emptySince != null && now - emptySince >= terminalGraceMs
            }
        changed.forEach { entry ->
            episodes[entry.bolusId] = entry.copy(
                phase = BolusLedgerPhase.UNKNOWN,
                terminalAtMillis = now,
                terminalStatusId = null,
                lastSeenAtMillis = maxOf(entry.lastSeenAtMillis, entry.emptySinceMillis ?: now),
                emptySinceMillis = null,
            )
        }

        // 过期清理：半小时一次即可；进行中的笔永不删除。
        if (now - lastPurgeAtMillis >= purgeIntervalMs) {
            lastPurgeAtMillis = now
            purgeExpired(now)
        }
    }

    /** 丢弃结束时间早于 `now - retentionMs` 的已终态条目。 */
    private fun purgeExpired(now: Long) {
        val cutoff = now - retentionMs
        episodes.values
            .filterNot { it.isOngoing }
            .filter { it.sortAtMillis < cutoff }
            .forEach { episodes.remove(it.bolusId) }
    }

    /** 已终态的按终态时刻倒序；进行中的由 UI 另行置顶。 */
    fun snapshot(): List<LedgerEntry> = episodes.values.sortedByDescending { it.sortAtMillis }

    fun hasEpisode(bolusId: Int): Boolean = episodes.containsKey(bolusId)

    private fun isKnownTerminal(statusId: Int): Boolean = statusId in KNOWN_TERMINALS

    private fun evictIfNeeded() {
        if (episodes.size <= maxEpisodes) return
        // 永不淘汰进行中的笔；其余按终态时刻从旧到新淘汰。
        val finished = episodes.values.filterNot { it.isOngoing }
        finished.sortedBy { it.sortAtMillis }
            .take(episodes.size - maxEpisodes)
            .forEach { episodes.remove(it.bolusId) }
    }

    private fun maxOfNullable(current: Long?, candidate: Long): Long? {
        if (candidate <= 0) return current
        return if (current == null) candidate else maxOf(current, candidate)
    }

    companion object {
        /** `CurrentBolusStatus` 的 id。 */
        const val STATUS_ALREADY_DELIVERED_OR_INVALID = 0
        const val STATUS_DELIVERING = 1
        const val STATUS_REQUESTING = 2

        /** `BolusStatus` 的全部已知终态。 */
        val KNOWN_TERMINALS = BolusTerminalStatus.USER_CANCELLED..BolusTerminalStatus.PLGS

        /** 空态→终态的实测值：20 s（6 U）与 35 s（14 U）；60 s 留两倍余量。 */
        const val DEFAULT_TERMINAL_GRACE_MS = 60_000L

        const val DEFAULT_MAX_EPISODES = 20

        /** 补录窗口：只认 10 分钟内结束、但没看到开笔的笔。 */
        const val DEFAULT_ADOPT_TERMINAL_MS = 600_000L

        /** 保留窗口：72 小时；超过即从内存丢弃。 */
        const val DEFAULT_RETENTION_MS = 72L * 60 * 60 * 1000

        /** 过期清理间隔：30 分钟。 */
        const val DEFAULT_PURGE_INTERVAL_MS = 30L * 60 * 1000

        fun keyOf(bolusId: Int) = "BOLUS:$bolusId"
    }
}
