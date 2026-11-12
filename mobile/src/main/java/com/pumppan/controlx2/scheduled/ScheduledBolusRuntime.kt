package com.pumppan.controlx2.scheduled

import android.content.Context
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.ledger.BolusStartObservation
import java.util.Locale
import java.util.UUID

/**
 * 定时大剂量的运行时外壳：把纯状态机 [ScheduledBolusChain] 接到
 * 持久化（[ScheduledBolusStore]）、闹钟（[ScheduledBolusAlarms]）与通知。
 *
 * **本对象不发送胰岛素**：真正的 `-98` 由 UI 确认后走既有的
 * `BolusSender`/`sendServiceBolusRequest`。这里只负责「该提醒了」「超时了」「作废了」。
 *
 * 链的两次确认（R8/R13）、免确认（R23）都在 UI 侧调用 [confirm]、[secondConfirm]。
 */
object ScheduledBolusRuntime {

    private val lock = Any()
    private var planValue: ScheduledPlan? = null
    private var snapshotValue: ScheduledBolusChain.Snapshot? = null

    fun currentPlan(): ScheduledPlan? = synchronized(lock) { planValue }

    fun currentSnapshot(): ScheduledBolusChain.Snapshot? = synchronized(lock) { snapshotValue }

    /** 进程启动/回到前台时对账：过期即作废，并把「已错过」只提醒一次（R10）。 */
    fun restore(context: Context, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val persisted = ScheduledBolusStore.load(context) ?: return
            planValue = persisted.plan
            snapshotValue = persisted.snapshot
        }
        tick(context, now)
    }

    /**
     * 建立预约（首笔仍由用户手动打）：只落盘 + 排第一条提醒闹钟，不进入倒数的链状态。
     * 返回校验问题；非空即未建立。
     */
    fun arm(
        context: Context,
        plan: ScheduledPlan,
        now: Long = System.currentTimeMillis(),
    ): List<PlanIssue> {
        val config = ScheduledBolusConfig.load(context)
        val limits = limitsFor(context)
        val issues = plan.validate(config, limits, now)
        if (issues.isNotEmpty()) return issues

        val resolved = plan.resolvedLinks()
        val idle = ScheduledBolusChain.Snapshot(
            chainId = UUID.randomUUID().toString(),
            state = ScheduledBolusChain.State.IDLE,
            runs = resolved.map { ScheduledBolusChain.LinkRun(it) },
            dueAtMillis = plan.firstAtMillis,
        )
        synchronized(lock) {
            planValue = plan
            snapshotValue = idle
        }
        persist(context)
        scheduleNext(context, now)
        return emptyList()
    }

    /** 用户主动取消（通知上的「取消」也走这里）：整链作废并说清剩余笔数。 */
    fun cancel(
        context: Context,
        reason: ScheduledBolusChain.AbortReason = ScheduledBolusChain.AbortReason.USER_CANCELLED,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val snapshot = snapshotValue ?: return
            if (snapshot.isTerminal) return
            val aborted = ScheduledBolusChain.abort(snapshot, reason)
            snapshotValue = aborted
            persist(context)
            ScheduledBolusAlarms.cancel(context)
            notifyAbort(context, aborted, reason)
        }
        ScheduledBolusStore.clear(context)
        ScheduledBolusNotifications.cancel(context)
    }

    /** 第一次确认；只在 ALERTING 有效。 */
    fun confirm(context: Context, now: Long = System.currentTimeMillis()) {
        val config = ScheduledBolusConfig.load(context)
        mutate(context) { snapshot ->
            ScheduledBolusChain.confirm(snapshot, now, config).also { after ->
                if (after.state == ScheduledBolusChain.State.WARNING) scheduleNext(context, now)
            }
        }
    }

    /** 第二次确认：确认后进入 SENDING，由 UI 取权限并发 `-98`。 */
    fun secondConfirm(context: Context, now: Long = System.currentTimeMillis()) {
        val config = ScheduledBolusConfig.load(context)
        mutate(context) { snapshot ->
            ScheduledBolusChain.secondConfirm(snapshot, now, config).also { after ->
                if (after.state == ScheduledBolusChain.State.SENDING) scheduleNext(context, now)
            }
        }
    }

    /** `-94` 已拿到 bolusId：登记白名单，供流水观测认领。 */
    fun registerPermission(context: Context, bolusId: Int) {
        mutate(context) { snapshot -> ScheduledBolusChain.registerPermission(snapshot, bolusId) }
    }

    /** 提前结束（权限失败 / 明确拒绝）：整链作废。 */
    fun abort(context: Context, reason: ScheduledBolusChain.AbortReason) {
        cancel(context, reason)
    }

    private inline fun mutate(
        context: Context,
        block: (ScheduledBolusChain.Snapshot) -> ScheduledBolusChain.Snapshot,
    ) {
        synchronized(lock) {
            val snapshot = snapshotValue ?: return
            if (snapshot.isTerminal) return
            val after = block(snapshot)
            if (after == snapshot) return
            snapshotValue = after
            persist(context)
            if (after.isTerminal) {
                ScheduledBolusStore.clear(context)
                ScheduledBolusAlarms.cancel(context)
                notifyAbort(context, after, after.abortReason)
            }
        }
    }

    /** 闹钟/界面的统一推进入口；只有状态发生变化才发通知。 */
    fun tick(context: Context, now: Long = System.currentTimeMillis()) {
        val config = ScheduledBolusConfig.load(context)
        synchronized(lock) {
            val snapshot = snapshotValue ?: return
            if (snapshot.isTerminal) return
            val plan = planValue ?: return
            val before = snapshot

            val after = when (snapshot.state) {
                // 首笔尚未开始：只负责「该打了」提醒与过期作废。
                ScheduledBolusChain.State.IDLE -> {
                    when {
                        now >= plan.firstAtMillis + config.confirmTimeoutMs ->
                            ScheduledBolusChain.abort(snapshot, ScheduledBolusChain.AbortReason.MISSED)

                        now >= plan.firstAtMillis - config.advanceNoticeMs &&
                            snapshot.lastNotifiedAtMillis == null ->
                            snapshot.copy(lastNotifiedAtMillis = now)

                        else -> snapshot
                    }
                }

                else -> ScheduledBolusChain.reconcileMissed(
                    ScheduledBolusChain.tick(snapshot, now, config),
                    now,
                    config,
                )
            }

            if (after == before) {
                // 状态没变也要保证下一条闹钟在（防厂商清理后丢失）。
                scheduleNext(context, now)
                return
            }
            snapshotValue = after
            persist(context)

            if (after.isTerminal) {
                ScheduledBolusStore.clear(context)
                ScheduledBolusAlarms.cancel(context)
                notifyAbort(context, after, after.abortReason)
                return
            }

            // 只在「刚进入某个提醒态」时发通知，避免每拍重复弹。
            when {
                before.state == ScheduledBolusChain.State.IDLE && after.lastNotifiedAtMillis != null ->
                    ScheduledBolusNotifications.show(
                        context,
                        context.getString(R.string.scheduled_bolus_first_reminder, doseText(plan.firstDoseMilliunits)),
                        context.getString(R.string.scheduled_bolus_first_reminder, doseText(plan.firstDoseMilliunits)),
                    )

                after.state == ScheduledBolusChain.State.NOTIFIED &&
                    before.state != ScheduledBolusChain.State.NOTIFIED -> {
                    val minutes = (config.advanceNoticeMs / 60_000L).coerceAtLeast(0)
                    val text = context.getString(
                        R.string.scheduled_bolus_advance,
                        minutes,
                        doseText(after.currentDoseMilliunits),
                    )
                    ScheduledBolusNotifications.show(context, text, text)
                }

                after.state == ScheduledBolusChain.State.ALERTING &&
                    before.state != ScheduledBolusChain.State.ALERTING -> {
                    val text = context.getString(
                        R.string.scheduled_bolus_due,
                        doseText(after.currentDoseMilliunits),
                    )
                    ScheduledBolusNotifications.show(context, text, text)
                }
            }
            scheduleNext(context, now)
        }
    }

    /** 流水观测到的「任何一笔开始证据」。链内白名单 → ACTIVATE；白名单外 → 整链作废。 */
    fun onStartObservation(context: Context, observation: BolusStartObservation) {
        val config = ScheduledBolusConfig.load(context)
        var terminal: Pair<ScheduledBolusChain.Snapshot, ScheduledBolusChain.AbortReason?>? = null
        synchronized(lock) {
            val snapshot = snapshotValue ?: return
            if (snapshot.isTerminal) return
            val plan = planValue ?: return

            if (snapshot.state == ScheduledBolusChain.State.IDLE) {
                val expected = plan.firstDoseMilliunits
                val observed = observation.requestedMilliunits
                // 剂量对不上（且泵确实回显了剂量）→ 用户打的是另一笔，预约按 R22 作废。
                if (observed != null && observed != expected) {
                    val aborted = ScheduledBolusChain.abort(
                        snapshot,
                        ScheduledBolusChain.AbortReason.FOREIGN_BOLUS,
                    )
                    snapshotValue = aborted
                    persist(context)
                    terminal = aborted to ScheduledBolusChain.AbortReason.FOREIGN_BOLUS
                } else {
                    // 单笔预约在校验层就被拒绝（R21），运行时不再保留「首笔无后续就清状态」的守卫。
                    val resolved = plan.resolvedLinks()
                    val started = ScheduledBolusChain.start(
                        chainId = snapshot.chainId,
                        links = resolved,
                        anchorAtMillis = observation.observedAtMillis,
                        config = config,
                        now = observation.observedAtMillis,
                    )
                    snapshotValue = started
                    persist(context)
                    // R19：首笔晚开始可能把后续笔推过 24:00，运行时立刻终止（界面只显示 HH:mm）。
                    val nextMidnight = java.time.Instant.ofEpochMilli(observation.observedAtMillis)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                    if (started.projectedLastDueAtMillis() >= nextMidnight) {
                        val aborted = ScheduledBolusChain.abort(
                            started,
                            ScheduledBolusChain.AbortReason.CROSSES_MIDNIGHT,
                        )
                        snapshotValue = aborted
                        persist(context)
                        terminal = aborted to ScheduledBolusChain.AbortReason.CROSSES_MIDNIGHT
                    } else {
                        scheduleNext(context, observation.observedAtMillis)
                    }
                }
                return@synchronized
            }

            val after = ScheduledBolusChain.onStartObservation(snapshot, observation)
            if (after == snapshot) return
            snapshotValue = after
            persist(context)
            if (after.isTerminal) {
                terminal = after to after.abortReason
            } else {
                scheduleNext(context, observation.observedAtMillis)
            }
        }
        terminal?.let { (snapshot, reason) ->
            ScheduledBolusStore.clear(context)
            ScheduledBolusAlarms.cancel(context)
            notifyAbort(context, snapshot, reason)
        }
    }

    /** 本链某笔的终态（COMPLETE 推进下一笔，STOPPED_* 作废）。 */
    fun onTerminal(context: Context, bolusId: Int, terminalStatusId: Int, now: Long = System.currentTimeMillis()) {
        val config = ScheduledBolusConfig.load(context)
        synchronized(lock) {
            val snapshot = snapshotValue ?: return
            if (snapshot.isTerminal) return
            val after = ScheduledBolusChain.onTerminal(snapshot, bolusId, terminalStatusId)
            if (after == snapshot) return
            snapshotValue = after
            persist(context)
            if (after.isTerminal) {
                ScheduledBolusStore.clear(context)
                ScheduledBolusAlarms.cancel(context)
                notifyAbort(context, after, after.abortReason)
            } else {
                scheduleNext(context, now)
            }
        }
    }

    // ---- 内部 ----

    private fun persist(context: Context) {
        val plan = planValue ?: return
        val snapshot = snapshotValue ?: return
        ScheduledBolusStore.save(context, plan, snapshot)
    }

    /** 下一条闹钟：IDLE→首笔提醒；COUNTING→提前量；NOTIFIED→到点；ALERTING→确认截止。 */
    private fun scheduleNext(context: Context, now: Long) {
        val config = ScheduledBolusConfig.load(context)
        val plan = planValue ?: return
        val snapshot = snapshotValue ?: return
        val at = when (snapshot.state) {
            ScheduledBolusChain.State.IDLE -> plan.firstAtMillis - config.advanceNoticeMs
            ScheduledBolusChain.State.COUNTING -> snapshot.dueAtMillis - config.advanceNoticeMs
            ScheduledBolusChain.State.NOTIFIED -> snapshot.dueAtMillis
            ScheduledBolusChain.State.ALERTING,
            ScheduledBolusChain.State.WARNING,
            ScheduledBolusChain.State.SENDING -> snapshot.confirmDeadlineAtMillis
            else -> null
        } ?: return
        ScheduledBolusAlarms.schedule(context, at, now)
    }

    private fun notifyAbort(
        context: Context,
        snapshot: ScheduledBolusChain.Snapshot,
        reason: ScheduledBolusChain.AbortReason?,
    ) {
        val reasonText = when (reason) {
            ScheduledBolusChain.AbortReason.FOREIGN_BOLUS -> context.getString(R.string.scheduled_bolus_abort_foreign)
            ScheduledBolusChain.AbortReason.CONFIRM_TIMEOUT -> context.getString(R.string.scheduled_bolus_abort_timeout)
            ScheduledBolusChain.AbortReason.MISSED -> context.getString(R.string.scheduled_bolus_abort_missed)
            ScheduledBolusChain.AbortReason.USER_CANCELLED -> context.getString(R.string.scheduled_bolus_abort_user)
            ScheduledBolusChain.AbortReason.FEATURE_DISABLED -> context.getString(R.string.scheduled_abort_feature_disabled)
            ScheduledBolusChain.AbortReason.CROSSES_MIDNIGHT -> context.getString(R.string.scheduled_abort_crosses_midnight)
            ScheduledBolusChain.AbortReason.PREFLIGHT_FAILED -> context.getString(R.string.scheduled_abort_preflight_failed)
            else -> context.getString(R.string.scheduled_bolus_abort_timeout)
        }
        ScheduledBolusNotifications.show(
            context,
            context.getString(R.string.scheduled_bolus_aborted, reasonText),
            context.getString(R.string.scheduled_bolus_aborted, reasonText),
            cancellable = false,
            ongoing = false,
        )
    }

    private fun doseText(milliunits: Long): String =
        String.format(Locale.US, "%.2f", milliunits / 1000.0)

    private fun limitsFor(context: Context): BolusLimits =
        BolusLimits.forModel(PumppanPrefs.pumpModel(context).model)
}
