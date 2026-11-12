package com.pumppan.controlx2.scheduled

/**
 * 定时大剂量「链」的纯状态机（设计 `定时大剂量-设计.md` §4 / R1–R23）。
 *
 * 本类**不发送胰岛素、不持有 Context、不碰通知**：所有输出都是新的不可变快照，
 * 由调用方决定是否真的去发 `-98`。这样链的全部安全规则可以先被 JVM 单测钉死，
 * 再接 UI 与发送链路。
 *
 * 取值口径（与设计一致，不得改写）：
 * - 倒数锚点是「泵确认本笔已开始输注」的时刻，不是点按时刻（R2）；
 * - 到点后 [ScheduledBolusConfig.confirmTimeoutMs] 内没有完成二次确认 → 整链作废（R13/R20）；
 * - 任一环节终止即整链作废，剩余预约全部取消，已发出的那笔不撤回（R20）；
 * - 流水观测到非本链 bolusId 的开始证据 → 立即整链作废（R22）。
 */
object ScheduledBolusChain {

    /** 链状态。`DONE` / `CHAIN_ABORTED` 为终态，之后不再接受任何推进。 */
    enum class State { IDLE, COUNTING, NOTIFIED, ALERTING, WARNING, SENDING, ACTIVE, DONE, CHAIN_ABORTED }

    /** 终止原因；文案在 UI 层映射，本层只留机器可判定的枚举。 */
    enum class AbortReason {
        USER_CANCELLED,
        CONFIRM_TIMEOUT,
        MISSED,           // 进程/闹钟失效，发现时已过期（R10：只提醒，不补发）
        LINK_LOSS,        // 连续断连超过阈值（R22）
        FOREIGN_BOLUS,    // 泵上开始了另一笔（R17/R22）
        PUMP_ABORTED,     // 本链这笔被用户/报警/故障中止（STOPPED_*）
        SEND_FAILED,      // 权限失败 / 明确拒绝
        SEND_UNKNOWN,     // 发出后无开始证据（不重试，防加倍给药）
        FEATURE_DISABLED, // 控制页入口开关关闭（强制关链，不是用户主动取消）
        CROSSES_MIDNIGHT, // 首笔晚开始导致后续笔落到次日（R19：不许跨天）
        PREFLIGHT_FAILED, // 执行前置检查不通过（链路不新鲜 / 有计划外大剂量）
    }

    /**
     * 预约池里取出的单笔：剂量 + 相对上一笔开始证据的延迟。
     * [absoluteAtMillis] 非空表示用户是按绝对时刻填的，仅用于计划期展示与换算；
     * 进入链之前必须先经 `ScheduledPlan.resolvedLinks()` 折算成延迟。
     */
    data class Link(
        val doseMilliunits: Long,
        val delayAfterPreviousMs: Long,
        val absoluteAtMillis: Long? = null,
    )

    /** 每笔的运行记录（只在内存；设计 §8.0 明确不落库）。 */
    data class LinkRun(
        val link: Link,
        val startedAtMillis: Long? = null,
        val bolusId: Int? = null,
        val deliveredMilliunits: Long? = null,
        val terminalStatusId: Int? = null,
    )

    /**
     * 单条链快照。字段与 `§8.0` 的落盘口径一致：
     * `chainId / state / nextDueAt / dose / chainCount / bolusIds / lastNotifiedAt`。
     */
    data class Snapshot(
        val chainId: String,
        val state: State,
        val runs: List<LinkRun>,
        val index: Int = 0,
        val anchorAtMillis: Long = 0,
        val dueAtMillis: Long = 0,
        /** 到点后的确认截止时刻；仅 ALERTING/WARNING/SENDING 有意义。 */
        val confirmDeadlineAtMillis: Long? = null,
        /** 已登记为 pending 的 bolusId，用于 R17/R22 的白名单判定。 */
        val pendingBolusIds: Set<Int> = emptySet(),
        val lastNotifiedAtMillis: Long? = null,
        val abortReason: AbortReason? = null,
        /** 倒数期间是否改过剂量；R23 免确认仅对「未改」生效。 */
        val doseEdited: Boolean = false,
    ) {
        val currentRun: LinkRun? get() = runs.getOrNull(index)

    /** 从锚点起推算的「最后一笔应付时刻」，用于运行时跨天拦截。 */
    fun projectedLastDueAtMillis(): Long {
        var at = anchorAtMillis
        runs.forEach { run ->
            at = run.link.absoluteAtMillis ?: (at + run.link.delayAfterPreviousMs)
        }
        return at
    }
        val isTerminal: Boolean get() = state == State.DONE || state == State.CHAIN_ABORTED
        val remainingCount: Int get() = (runs.size - index - 1).coerceAtLeast(0)
        val currentDoseMilliunits: Long get() = currentRun?.link?.doseMilliunits ?: 0L
    }

    // ---- 生命周期 ----

    /**
     * 首笔已确认开始输注后启动链。
     *
     * @param links 后续要自动发起的笔（不含已完成的第一笔），条数须在 `1..config.maxChainCount`
     * @param anchorAtMillis 首笔的**开始证据**时刻（R2）
     */
    fun start(
        chainId: String,
        links: List<Link>,
        anchorAtMillis: Long,
        config: ScheduledBolusConfig,
        now: Long = anchorAtMillis,
    ): Snapshot {
        require(links.isNotEmpty()) { "链至少需要 1 笔后续预约" }
        // 链长上限含**首笔**（R3：整条链最多 3 笔），因此后续笔最多 maxChainCount - 1。
        require(links.size + 1 <= config.maxChainCount) {
            "链总笔数 ${links.size + 1} 超过上限 ${config.maxChainCount}"
        }
        require(links.all { it.doseMilliunits > 0 }) { "剂量必须为正" }
        val first = links.first()
        return Snapshot(
            chainId = chainId,
            state = State.COUNTING,
            runs = links.map { LinkRun(it) },
            anchorAtMillis = anchorAtMillis,
            dueAtMillis = anchorAtMillis + first.delayAfterPreviousMs,
            lastNotifiedAtMillis = null,
        )
    }

    /**
     * 时间推进：COUNTING → NOTIFIED（T−N）→ ALERTING（到点）→ 超时作废。
     *
     * 只做状态推进，**不发送**。到点后由 UI 弹框，用户确认才继续。
     */
    fun tick(snapshot: Snapshot, now: Long, config: ScheduledBolusConfig): Snapshot {
        if (snapshot.isTerminal) return snapshot
        return when (snapshot.state) {
            State.COUNTING -> when {
                now >= snapshot.dueAtMillis -> alerting(snapshot, now, config)
                now >= snapshot.dueAtMillis - config.advanceNoticeMs ->
                    snapshot.copy(state = State.NOTIFIED, lastNotifiedAtMillis = now)
                else -> snapshot
            }

            State.NOTIFIED -> if (now >= snapshot.dueAtMillis) {
                alerting(snapshot, now, config)
            } else {
                snapshot
            }

            State.ALERTING, State.WARNING, State.SENDING -> {
                val deadline = snapshot.confirmDeadlineAtMillis
                if (deadline != null && now > deadline) abort(snapshot, AbortReason.CONFIRM_TIMEOUT)
                else snapshot
            }

            else -> snapshot
        }
    }

    private fun alerting(snapshot: Snapshot, now: Long, config: ScheduledBolusConfig): Snapshot =
        snapshot.copy(
            state = State.ALERTING,
            confirmDeadlineAtMillis = now + config.confirmTimeoutMs,
        )

    /**
     * 用户在确认框里改剂量（R7）：同一字段、单副本；改过即视为新的确认（§6）。
     * 只允许在 ALERTING / WARNING 阶段改；改完退回 ALERTING 重新确认。
     */
    fun editDose(snapshot: Snapshot, doseMilliunits: Long): Snapshot {
        if (snapshot.state != State.ALERTING && snapshot.state != State.WARNING) return snapshot
        val run = snapshot.currentRun ?: return snapshot
        if (doseMilliunits <= 0 || doseMilliunits == run.link.doseMilliunits) return snapshot
        val runs = snapshot.runs.toMutableList()
        runs[snapshot.index] = run.copy(link = run.link.copy(doseMilliunits = doseMilliunits))
        return snapshot.copy(
            runs = runs,
            state = State.ALERTING,
            doseEdited = true,
        )
    }

    /** 第一次确认。只在 ALERTING 且未超时、未改动后的重新确认路径有效。 */
    fun confirm(snapshot: Snapshot, now: Long, config: ScheduledBolusConfig): Snapshot {
        if (snapshot.state != State.ALERTING) return snapshot
        val deadline = snapshot.confirmDeadlineAtMillis
        if (deadline != null && now > deadline) return abort(snapshot, AbortReason.CONFIRM_TIMEOUT)
        return snapshot.copy(state = State.WARNING)
    }

    /** 第二次确认（R8）；R23 免确认模式由调用方决定是否跳过这两步。 */
    fun secondConfirm(snapshot: Snapshot, now: Long, config: ScheduledBolusConfig): Snapshot {
        if (snapshot.state != State.WARNING) return snapshot
        val deadline = snapshot.confirmDeadlineAtMillis
        if (deadline != null && now > deadline) return abort(snapshot, AbortReason.CONFIRM_TIMEOUT)
        return snapshot.copy(state = State.SENDING)
    }

    /**
     * `-94` 已拿到 bolusId：**发送前**登记白名单（R22 的时序要求），再发 `-98`。
     */
    fun registerPermission(snapshot: Snapshot, bolusId: Int): Snapshot {
        if (snapshot.state != State.SENDING || bolusId <= 0) return snapshot
        val run = snapshot.currentRun ?: return snapshot
        val runs = snapshot.runs.toMutableList()
        runs[snapshot.index] = run.copy(bolusId = bolusId)
        return snapshot.copy(runs = runs, pendingBolusIds = snapshot.pendingBolusIds + bolusId)
    }

    /** `-98` 已发出（不等于已开始）。 */
    fun onSent(snapshot: Snapshot): Snapshot =
        if (snapshot.state == State.SENDING) snapshot else snapshot

    /** 发送前检查失败 / 明确拒绝：整链作废，不重试。 */
    fun onSendFailure(snapshot: Snapshot, reason: AbortReason = AbortReason.SEND_FAILED): Snapshot =
        abort(snapshot, reason)

    /**
     * 流水观测到一笔的开始证据（R22）。
     *
     * - 属于本链 pending 白名单 → 进入 ACTIVE，记录开始时刻（下一笔的倒数锚点）；
     * - 不属于白名单 → 立即整链作废（含延展、泵发起；判据是「开始」不是「请求」）。
     */
    fun onStartObservation(
        snapshot: Snapshot,
        observation: com.pumppan.controlx2.ledger.BolusStartObservation,
    ): Snapshot {
        if (snapshot.isTerminal) return snapshot
        val bolusId = observation.bolusId
        if (bolusId <= 0) return snapshot

        if (bolusId in snapshot.pendingBolusIds) {
            val run = snapshot.currentRun ?: return snapshot
            if (run.startedAtMillis != null) return snapshot
            val runs = snapshot.runs.toMutableList()
            runs[snapshot.index] = run.copy(
                bolusId = bolusId,
                startedAtMillis = observation.observedAtMillis,
            )
            return snapshot.copy(
                state = State.ACTIVE,
                runs = runs,
                anchorAtMillis = observation.observedAtMillis,
            )
        }

        // 白名单外的任何开始证据都判死；但同链已开始的那笔重复上报不算外来。
        if (snapshot.runs.any { it.bolusId == bolusId }) return snapshot
        return abort(snapshot, AbortReason.FOREIGN_BOLUS)
    }

    /**
     * 本链某笔的终态。
     *
     * - `COMPLETE(3)` 且还有剩余 → 取下一笔，倒数为「上一笔开始时刻 + 下一笔延迟」；
     * - `STOPPED_*` → 整链作废（PUMP_ABORTED）；
     * - 最后一笔完成 → DONE。
     */
    fun onTerminal(snapshot: Snapshot, bolusId: Int, terminalStatusId: Int): Snapshot {
        if (snapshot.isTerminal) return snapshot
        val index = snapshot.runs.indexOfFirst { it.bolusId == bolusId }
        if (index < 0) return snapshot
        if (snapshot.runs[index].terminalStatusId != null) return snapshot

        val runs = snapshot.runs.toMutableList()
        runs[index] = runs[index].copy(terminalStatusId = terminalStatusId)

        if (terminalStatusId != STATUS_COMPLETE) {
            return abort(snapshot.copy(runs = runs), AbortReason.PUMP_ABORTED)
        }
        if (index != snapshot.index) return snapshot.copy(runs = runs)

        val nextIndex = index + 1
        if (nextIndex >= runs.size) {
            return snapshot.copy(state = State.DONE, runs = runs, index = nextIndex)
        }
        val startedAt = runs[index].startedAtMillis ?: snapshot.anchorAtMillis
        return snapshot.copy(
            state = State.COUNTING,
            runs = runs,
            index = nextIndex,
            anchorAtMillis = startedAt,
            dueAtMillis = startedAt + runs[nextIndex].link.delayAfterPreviousMs,
            confirmDeadlineAtMillis = null,
            lastNotifiedAtMillis = null,
        )
    }

    /** 用户主动取消 / 外部断连 / 进程错过等统一入口（R20）。 */
    fun abort(snapshot: Snapshot, reason: AbortReason): Snapshot {
        if (snapshot.isTerminal) return snapshot
        return snapshot.copy(
            state = State.CHAIN_ABORTED,
            abortReason = reason,
            confirmDeadlineAtMillis = null,
        )
    }

    /** 发现链已过期（闹钟失效 / 重启对账）→ 标记 MISSED，只提醒不发送（R10）。 */
    fun reconcileMissed(snapshot: Snapshot, now: Long, config: ScheduledBolusConfig): Snapshot {
        if (snapshot.isTerminal) return snapshot
        if (snapshot.state == State.COUNTING && now > snapshot.dueAtMillis + config.confirmTimeoutMs) {
            return abort(snapshot, AbortReason.MISSED)
        }
        return snapshot
    }

    const val STATUS_COMPLETE = 3
}
