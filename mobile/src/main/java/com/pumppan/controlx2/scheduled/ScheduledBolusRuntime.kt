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
            // 槽位口径：IDLE 时 due/anchor 都指「首笔的计划时刻」，
            // runs[0] 是第 2 笔，不能拿它当基准（否则第 2/3 行整体前移一格）。
            anchorAtMillis = plan.firstAtMillis,
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

    /**
     * 首笔 `-98` 已经交出（拿到 bolusId）后立刻把链从 IDLE 起进 COUNTING。
     *
     * 为什么不依赖 `onStartObservation`：小剂量（0.05 U）可能在两次状态轮询之间就输完，
     * 泵侧永远不出现 DELIVERING，链收不到「开始证据」就会一直 IDLE，最后被判 MISSED
     * （真机 2026-11-13 23:04 复现：首笔实际已成功，界面却「未开始 0/3」并中止）。
     *
     * 口径：锚点 = 本机交出 `-98` 的时刻（已发出），不是泵的输注开始时刻；
     * 首笔 bolusId 记入 `knownBolusIds`，泵回放这笔时不会被判外来大剂量。
     */
    fun startAfterFirstSend(
        context: Context,
        firstBolusId: Int,
        sentAtMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val config = ScheduledBolusConfig.load(context)
        var crossingMidnight = false
        synchronized(lock) {
            val snapshot = snapshotValue ?: return false
            val plan = planValue ?: return false
            if (snapshot.state != ScheduledBolusChain.State.IDLE) return false
            val resolved = plan.resolvedLinks()
            if (resolved.isEmpty()) return false
            val started = ScheduledBolusChain.start(
                chainId = snapshot.chainId,
                links = resolved,
                anchorAtMillis = sentAtMillis,
                config = config,
                now = sentAtMillis,
                firstBolusId = firstBolusId,
            )
            val nextMidnight = java.time.Instant.ofEpochMilli(sentAtMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            if (started.projectedLastDueAtMillis() >= nextMidnight) {
                crossingMidnight = true
            } else {
                snapshotValue = started
                persist(context)
                scheduleNext(context, sentAtMillis)
            }
        }
        if (crossingMidnight) {
            cancel(context, ScheduledBolusChain.AbortReason.CROSSES_MIDNIGHT)
            return false
        }
        // 用户 2026-11-13：整条链只推「一条」通知 —— 首笔开始时告诉用户
        // 「已发起，详情看 App」，后续到点不再单独弹通知（到点仍需 App 内确认）。
        ScheduledBolusNotifications.show(
            context,
            context.getString(R.string.scheduled_bolus_started_title),
            context.getString(R.string.scheduled_bolus_started_text),
            cancellable = true,
            ongoing = true,
        )
        return true
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

    /**
     * 运行中修改**尚未开始**的后续笔（剂量 / 相对间隔 / 绝对时刻）。
     *
     * 只允许在 IDLE / COUNTING / NOTIFIED：一旦进入 ALERTING（到点提醒）就锁死，
     * 避免「界面改了、链还按老值发」。改到当前这笔时重算 due 并重排闹钟。
     *
     * @return 是否真的写入了变更
     */
    fun editPending(
        context: Context,
        linkIndex: Int,
        doseMilliunits: Long? = null,
        intervalMs: Long? = null,
        absoluteAtMillis: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(lock) {
            val plan = planValue ?: return false
            val snapshot = snapshotValue ?: return false
            if (snapshot.isTerminal) return false
            if (linkIndex !in plan.links.indices) return false
            // 已开始/已执行过的笔不改。
            if (linkIndex < snapshot.index) return false

            // 锁定期按「这一笔自己的通知时刻」算，而不是整条链的状态：
            // 第 2 笔进入提醒/确认时，第 3 笔只要还没到 (due - advanceNotice) 就仍可改
            // （真机 23:27 复现：第 2 笔输注中时第 3 笔被整体灰化，用户还没到锁定期）。
            val config = ScheduledBolusConfig.load(context)
            val dueOfLink = if (linkIndex == snapshot.index) {
                snapshot.dueAtMillis
            } else {
                var due = snapshot.dueAtMillis
                for (i in (snapshot.index + 1)..linkIndex) {
                    due += snapshot.runs[i].link.delayAfterPreviousMs
                }
                due
            }
            if (now + config.advanceNoticeMs >= dueOfLink) return false

            var updatedPlan = plan
            if (doseMilliunits != null && doseMilliunits > 0) {
                updatedPlan = updatedPlan.withDose(linkIndex, doseMilliunits)
            }
            if (intervalMs != null && intervalMs >= 0) {
                updatedPlan = updatedPlan.withInterval(linkIndex, intervalMs)
            }
            if (absoluteAtMillis != null) {
                updatedPlan = updatedPlan.withAbsoluteAt(linkIndex, absoluteAtMillis)
            }
            if (updatedPlan == plan) return false

            val resolved = updatedPlan.resolvedLinks()
            val runs = snapshot.runs.mapIndexed { index, run ->
                if (index >= snapshot.index) run.copy(link = resolved[index]) else run
            }
            // 只有改到「当前这笔」的间隔/时刻才影响 due；后续笔只影响将来的倒数。
            val due = if (snapshot.state == ScheduledBolusChain.State.IDLE) {
                snapshot.dueAtMillis
            } else {
                snapshot.anchorAtMillis + resolved[snapshot.index].delayAfterPreviousMs
            }
            val next = snapshot.copy(
                runs = runs,
                dueAtMillis = due,
                // 把到点提醒往后推了 → 退回 COUNTING 重新倒数；否则保持原状态。
                state = if (snapshot.state == ScheduledBolusChain.State.NOTIFIED &&
                    due > now + config.advanceNoticeMs
                ) {
                    ScheduledBolusChain.State.COUNTING
                } else {
                    snapshot.state
                },
                lastNotifiedAtMillis = if (snapshot.state == ScheduledBolusChain.State.NOTIFIED &&
                    due > now + config.advanceNoticeMs
                ) {
                    null
                } else {
                    snapshot.lastNotifiedAtMillis
                },
            )
            planValue = updatedPlan
            snapshotValue = next
            persist(context)
            scheduleNext(context, now)
            return true
        }
    }

    /** 提前结束（权限失败 / 明确拒绝）：整链作废。 */
    fun abort(context: Context, reason: ScheduledBolusChain.AbortReason) {
        cancel(context, reason)
    }

    /**
     * 收尾已终止的链（中止 / 完成），让界面回到可新建计划的状态。
     *
     * 之前只清了持久化，内存里的终止快照还在：界面一直显示红卡，
     * 而「下一步」只在 snapshot == null 时才渲染，于是用户被锁在终止态、
     * 无法开始新的一条链（真机 23:29 复现）。这里把内存也清掉。
     */
    fun dismiss(context: Context) {
        synchronized(lock) {
            snapshotValue = null
            planValue = null
        }
        ScheduledBolusStore.clear(context)
        ScheduledBolusAlarms.cancel(context)
        ScheduledBolusNotifications.cancel(context)
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

            // 用户 2026-11-13：整条链只要「一条」通知 —— 首笔发出时推一次，
            // 后续到点/提前提醒不再重复弹（到点仍需在 App 内手动确认）。
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
                        // 首笔自己的 bolusId 也登记：泵会回放这一笔的状态，
                        // 不登记就会被后续重复报文当成「外来大剂量」。
                        firstBolusId = observation.bolusId,
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
        // 单条通知策略：链结束时只撤回那条「已发起」的常驻通知，
        // 不再追加第二条（避免同 id 原地改字 + FLAG_ONLY_ALERT_ONCE 不弹的观感问题）。
        ScheduledBolusNotifications.cancel(context)
    }

    private fun doseText(milliunits: Long): String =
        String.format(Locale.US, "%.2f", milliunits / 1000.0)

    private fun limitsFor(context: Context): BolusLimits =
        BolusLimits.forModel(PumppanPrefs.pumpModel(context).model)
}
