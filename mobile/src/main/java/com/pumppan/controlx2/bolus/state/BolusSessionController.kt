package com.pumppan.controlx2.bolus.state

import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusAbstractResponse.BolusStatus
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV3Response
import timber.log.Timber

/**
 * S1 的 legacy / v2 判断位。S1 恒为 [LEGACY]（controller 完全不消费消息），
 * S2 接入设置开关后只改调用方传入的 lambda。
 */
enum class BolusControllerMode {
    LEGACY,
    V2,
}

/**
 * 输注期状态机（设计 §4.9）：把泵侧证据翻译成单一 [BolusDeliveryState]，
 * 并维护确认阶梯、结束判据与最终结局。
 *
 * 报文按**响应类**分派（不是按 opcode 数字），pumpx2 1.9.0 中对应关系为：
 *
 * | 设计文档中的编号 | 报文类 | 实际响应 opcode |
 * |---|---|---|
 * | `44` | `CurrentBolusStatusResponse` | 45 |
 * | `46` | `ExtendedBolusStatusResponse` | 47 |
 * | `-74` | `ExtendedBolusStatusV2Response` | -73 |
 * | `48` | `LastBolusStatusResponse` / `LastBolusStatusV2Response` / `LastBolusStatusV3Response` | 49 / -91 / -69 |
 * | `-94` | `BolusPermissionResponse` | -93 |
 * | `-97` | `InitiateBolusResponse` | -97（带签名） |
 * | `-96` | `CancelBolusResponse` | -95 |
 *
 * 未知报文一律忽略；单条报文解析失败（字段缺失 / 无枚举值）只记一条日志，不抛异常、不改状态。
 */
class BolusSessionController(
    private val mode: () -> BolusControllerMode = { BolusControllerMode.LEGACY },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val limits: BolusLimits = BolusLimits.Default,
) {
    val holder = BolusSessionHolder(limits = limits, clock = clock)

    val session: BolusDelivery?
        get() = holder.session

    var outcome: BolusOutcome = BolusOutcome.Pending
        private set

    /**
     * 本 App 发出 `-98` 时调用：进入 [BolusDeliveryState.CONFIRMING]，从此刻起算确认阶梯。
     *
     * @param hasImmediateLeg 本次是否含立即段；false = 纯方波，`45` 全程为零不可用。
     * @param expectedEndAtMillis 纯方波 / 双波的延展预计结束时刻（可空）。
     */
    fun markInitiateSent(
        bolusId: Int,
        hasImmediateLeg: Boolean,
        expectedEndAtMillis: Long? = null,
        now: Long = clock(),
    ) {
        if (mode() != BolusControllerMode.V2) return
        holder.resetCancelTracking()
        outcome = BolusOutcome.Pending
        holder.set(
            BolusDelivery(
                state = BolusDeliveryState.CONFIRMING,
                bolusId = bolusId.takeIf { it != 0 },
                hasImmediateLeg = hasImmediateLeg,
                startedAtMillis = now,
                expectedEndAtMillis = expectedEndAtMillis,
                lastEvidenceAtMillis = now,
            )
        )
    }

    /**
     * 进程重启后从落盘信封恢复单笔会话：只还原快照与结局，**不发送、不取消**。
     * 是否仍有效（是否过期）由调用方判断。
     */
    fun restoreSession(delivery: BolusDelivery) {
        if (mode() != BolusControllerMode.V2) return
        outcome = delivery.outcome
        holder.set(delivery)
    }

    /**
     * 结局页「关闭」：只清**已落定**的会话并把结局复位；进行中的会话一律不动，
     * 避免把一笔还在输注的会话误清掉。
     */
    fun dismissFinished() {
        if (mode() != BolusControllerMode.V2) return
        val current = holder.session
        if (current != null && !current.isFinished) return
        // 主笔已落定，但泵上另一笔还在跑：把它抬成主笔，保留展示与取消，
        // 不能连它一起清掉（否则用户再也看不到、也取消不了还在输注的那笔）。
        val extended = holder.extendedSession?.takeIf { !it.isFinished }
        if (extended != null) {
            outcome = extended.outcome
            holder.setExtended(null)
            holder.set(extended)
            return
        }
        outcome = BolusOutcome.Pending
        holder.clear()
    }

    /**
     * 由协调器的 ticker 驱动：确认阶梯（10s 警示 / 60s UNKNOWN）、取消终态兜底与延展超时兜底。
     * 没有时钟推进时，只有报文到达才会改变状态。
     */
    fun onTick(now: Long = clock()) {
        if (mode() != BolusControllerMode.V2) return
        tickExtended(now)
        val current = holder.session ?: return
        if (current.isFinished) return

        when (current.state) {
            BolusDeliveryState.CONFIRMING -> {
                // startedAtMillis 只在 -98 真正发出后才有值；只有 -94 授权时为 null，
                // 用最近证据时间兜底，避免「等确认」这一相位没有出口。
                val anchor = current.startedAtMillis ?: current.lastEvidenceAtMillis
                if (anchor > 0L && now - anchor >= limits.confirmUnconfirmedMillis) {
                    Timber.w("bolus: 超过 %dms 未收到开始确认，转 UNCONFIRMED", limits.confirmUnconfirmedMillis)
                    holder.update(now) {
                        it.copy(
                            state = BolusDeliveryState.UNCONFIRMED,
                            startedAtMillis = it.startedAtMillis ?: anchor,
                        )
                    }
                }
            }

            BolusDeliveryState.UNCONFIRMED -> {
                val anchor = current.startedAtMillis ?: current.lastEvidenceAtMillis
                if (anchor > 0L && now - anchor >= limits.confirmUnknownMillis) {
                    Timber.w("bolus: 发出 -98 后 %dms 仍无开始证据，落 UNKNOWN", limits.confirmUnknownMillis)
                    holder.update(now) { it.copy(state = BolusDeliveryState.UNKNOWN) }
                }
            }

            BolusDeliveryState.ACTIVE -> {
                val expectedEnd = current.expectedEndAtMillis ?: return
                if (now > expectedEnd + limits.staleExtendedGraceMillis) {
                    Timber.w("bolus: 延展预计结束 %dms 后仍无 47/-91，落 UNKNOWN", limits.staleExtendedGraceMillis)
                    holder.update(now) { it.copy(state = BolusDeliveryState.UNKNOWN) }
                }
            }

            BolusDeliveryState.CANCELLING -> {
                // 取消分支的终态兜底：-95 没回来时不能无限停在 CANCELLING。
                // 超过窗口仍无停止证据 → UNKNOWN + fromCancel，交给结局页给出断管指引。
                val cancelAt = holder.cancelSentAtMillis()
                if (cancelAt > 0L && now - cancelAt >= limits.cancelConfirmMillis) {
                    Timber.w(
                        "bolus: 发出 -96 后 %dms 未收到 -95/-91，落 UNKNOWN（取消失败）",
                        limits.cancelConfirmMillis,
                    )
                    finish(
                        BolusDeliveryState.UNKNOWN,
                        BolusOutcome.Failed(
                            error = BolusError.TIMEOUT,
                            fromCancel = true,
                        ),
                        now,
                    )
                }
            }

            else -> Unit
        }
    }

    /**
     * 第二笔（泵上并行的扩展）的兜底：取消超时、预计结束超时。
     * 只动第二槽，不影响主笔的三态与文案。
     */
    private fun tickExtended(now: Long) {
        val extended = holder.extendedSession?.takeIf { !it.isFinished } ?: return
        when (extended.state) {
            BolusDeliveryState.CANCELLING -> {
                val cancelAt = holder.cancelSentAtMillis(BolusTarget.EXTENDED)
                if (cancelAt > 0L && now - cancelAt >= limits.cancelConfirmMillis) {
                    Timber.w(
                        "bolus: 扩展笔发出 -96 后 %dms 未收到停止证据，落 UNKNOWN（取消失败）",
                        limits.cancelConfirmMillis,
                    )
                    holder.finishExtended(
                        BolusDeliveryState.UNKNOWN,
                        BolusOutcome.Failed(
                            error = BolusError.TIMEOUT,
                            fromCancel = true,
                        ),
                        now,
                    )
                }
            }

            BolusDeliveryState.ACTIVE -> {
                val expectedEnd = extended.expectedEndAtMillis ?: return
                if (now > expectedEnd + limits.staleExtendedGraceMillis) {
                    Timber.w(
                        "bolus: 扩展笔预计结束 %dms 后仍无 47/-91，落 UNKNOWN",
                        limits.staleExtendedGraceMillis,
                    )
                    holder.finishExtended(
                        BolusDeliveryState.UNKNOWN,
                        BolusOutcome.Failed(BolusError.TIMEOUT),
                        now,
                    )
                }
            }

            else -> Unit
        }
    }

    fun applyMessage(message: Message) {
        if (mode() != BolusControllerMode.V2) return
        try {
            when (message) {
                is CurrentBolusStatusResponse -> onCurrentBolus(message)
                is ExtendedBolusStatusResponse ->
                    onExtendedBolus(message.bolusStatus, message.bolusId, message.timestamp, message.duration)
                is ExtendedBolusStatusV2Response ->
                    onExtendedBolus(message.bolusStatus, message.bolusId, message.timestamp, message.duration)
                is LastBolusStatusV3Response -> onLastBolusV3(message)
                is LastBolusStatusResponse -> onLastBolus(message.bolusId, message.bolusStatus)
                is LastBolusStatusV2Response -> onLastBolus(message.bolusId, message.bolusStatus)
                is InitiateBolusResponse -> onInitiateBolus(message)
                is BolusPermissionResponse -> onPermission(message)
                is CancelBolusResponse -> onCancel(message)
                else -> Unit
            }
        } catch (e: Exception) {
            Timber.w(e, "bolus: 忽略解析失败的 %s", message.javaClass.simpleName)
        }
    }

    /**
     * 44：当前大剂量状态。
     *
     * - `ALREADY_DELIVERED_OR_INVALID`：只在**立即段仍在跑**时作为结束证据（`45 bolusId=0`）；
     *   纯方波 / 已进入延展段的会话忽略它（延展的 45 全程为零）；CONFIRMING / UNCONFIRMED / UNKNOWN
     *   也忽略它，避免把「可能没开始」误判成 Idle 而放行重试。
     * - `REQUESTING` / `DELIVERING` 且带 `bolusId`：确认开始（10–60s 的阶梯在此切回 ACTIVE）。
     */
    private fun onCurrentBolus(message: CurrentBolusStatusResponse) {
        val now = clock()
        val current = holder.session
        when (message.status) {
            CurrentBolusStatus.ALREADY_DELIVERED_OR_INVALID -> onCurrentBolusEmpty(current, now)

            CurrentBolusStatus.REQUESTING, CurrentBolusStatus.DELIVERING -> {
                if (message.bolusId == 0) {
                    anomaly(message.javaClass.simpleName, "进行中但缺少 bolusId")
                    return
                }
                onCurrentBolusActive(message.bolusId, current, now)
            }

            null -> anomaly(message.javaClass.simpleName, "未知 status=${message.statusId}")
        }
    }

    private fun onCurrentBolusEmpty(current: BolusDelivery?, now: Long) {
        if (current == null) {
            // 主笔为空但第二槽还在跑：44 只描述立即段，不能因此清掉另一笔扩展。
            if (holder.extendedSession?.takeIf { !it.isFinished } != null) return
            if (holder.reconciling) holder.clearSession() else holder.clear()
            return
        }
        if (current.isFinished) return

        val immediateStillRunning =
            current.hasImmediateLeg && current.immediateLegDoneAtMillis == null
        when (current.state) {
            BolusDeliveryState.ACTIVE -> {
                if (immediateStillRunning) {
                    holder.update(now) {
                        it.copy(
                            immediateLegDoneAtMillis = now,
                            endObservedAtMillis = if (it.expectedEndAtMillis == null) {
                                it.endObservedAtMillis ?: now
                            } else {
                                it.endObservedAtMillis
                            },
                        )
                    }
                }
                // 已进入延展段 / 纯方波：45 全程为零，不作任何结束证据。
            }

            BolusDeliveryState.CANCELLING ->
                holder.update(now) {
                    if (it.endObservedAtMillis == null) it.copy(endObservedAtMillis = now) else it
                }

            // CONFIRMING / UNCONFIRMED：零结果不足以证明「没开始」，继续等。
            BolusDeliveryState.CONFIRMING,
            BolusDeliveryState.UNCONFIRMED -> Unit

            // 状态闩锁修复：UNKNOWN（对账超时等路径）下收到「无大剂量在跑」的空证据后，自愈为 IDLE。
            BolusDeliveryState.UNKNOWN -> finish(BolusDeliveryState.IDLE, BolusOutcome.Success, now)

            else -> Unit
        }
    }

    private fun onCurrentBolusActive(bolusId: Int, current: BolusDelivery?, now: Long) {
        if (current == null) {
            holder.begin(BolusDeliveryState.ACTIVE, bolusId, hasImmediateLeg = true, now = now)
            return
        }
        if (current.isFinished) {
            // 上一笔已落定后又出现带 id 的 45：是另一笔（或泵侧重新报告），按新会话处理。
            holder.begin(BolusDeliveryState.ACTIVE, bolusId, hasImmediateLeg = true, now = now)
            return
        }
        when (current.state) {
            BolusDeliveryState.CANCELLING ->
                // 取消中不能因为 44 仍在跑就退回 ACTIVE，等 -95 / -91 落定。
                holder.update(now) { it.copy(lastEvidenceAtMillis = now) }

            BolusDeliveryState.UNKNOWN ->
                if (current.bolusId != null && current.bolusId == bolusId) {
                    // 迟到但明确的开始证据：允许纠正 UNKNOWN（不给用户重试，但不拒绝证据）。
                    holder.update(now) {
                        it.copy(state = BolusDeliveryState.ACTIVE, lastEvidenceAtMillis = now)
                    }
                } else {
                    holder.begin(BolusDeliveryState.ACTIVE, bolusId, hasImmediateLeg = true, now = now)
                }

            else ->
                if (!current.hasImmediateLeg && current.isExtendedRunning &&
                    current.bolusId != bolusId
                ) {
                    // 主笔本身是一笔扩展，44 报告的是另一笔立即：把旧扩展抬进第二槽，
                    // 再以新 id 建立主笔，避免扩展 id 被覆盖后既显示不出也取消不了。
                    holder.begin(BolusDeliveryState.ACTIVE, bolusId, hasImmediateLeg = true, now = now)
                } else {
                    holder.update(now) {
                        it.copy(
                            state = BolusDeliveryState.ACTIVE,
                            bolusId = bolusId,
                            lastEvidenceAtMillis = now,
                        )
                    }
                }
        }
    }

    /**
     * 46 / -74：延展大剂量状态。
     *
     * - `bolusStatus=0` / `bolusId=0`：只有**延展段真的在跑**（纯方波或立即段已结束）才作为结束证据；
     *   双波立即段尚未结束时忽略。
     * - 活跃：更新 `expectedEndAtMillis`，并作为「已在输注」的补强证据。
     */
    private fun onExtendedBolus(status: Int, bolusId: Int, pumpTimestampSeconds: Long, durationSeconds: Long) {
        val now = clock()

        // 1) 结束证据：先认领第二槽（泵上并行的那笔），再回落到主笔的扩展段。
        if (status == EXTENDED_STATUS_NONE || bolusId == 0 || durationSeconds <= 0) {
            val extended = holder.extendedSession
            if (extended != null && !extended.isFinished) {
                holder.updateExtended(now) {
                    if (it.endObservedAtMillis == null) it.copy(endObservedAtMillis = now) else it
                }
                return
            }
            val current = holder.session
            if (current == null || current.isFinished) return
            if (!current.isExtendedRunning) return
            // 46 与 -74 是两个独立请求：其中一个刚报「在跑」，另一个的空结果只说明
            // 「该请求无记录」，不能当作结束证据。留一个短防抖，真正的结束交给 -91 / 后续空结果。
            if (now - current.lastEvidenceAtMillis < EXTENDED_END_DEBOUNCE_MS) return
            holder.update(now) {
                if (it.endObservedAtMillis == null) it.copy(endObservedAtMillis = now) else it
            }
            return
        }

        val expectedEnd = extendedEndAtMillis(pumpTimestampSeconds, durationSeconds)
        val current = holder.session

        // 2) 双槽：主笔是「仍在跑的普通大剂量」，而这条扩展证据带的是另一个 id
        //    → 它是泵上并行的另一笔，写第二槽，绝不覆盖主笔的 bolusId。
        if (current != null && !current.isFinished &&
            current.hasImmediateLeg && current.isImmediateLegLive &&
            current.bolusId != null && current.bolusId != bolusId
        ) {
            val extended = holder.extendedSession
            if (extended == null || extended.isFinished) {
                holder.setExtended(
                    BolusDelivery(
                        state = BolusDeliveryState.ACTIVE,
                        bolusId = bolusId,
                        hasImmediateLeg = false,
                        startedAtMillis = now,
                        expectedEndAtMillis = expectedEnd,
                        lastEvidenceAtMillis = now,
                    )
                )
            } else {
                holder.updateExtended(now) {
                    it.copy(
                        state = BolusDeliveryState.ACTIVE,
                        bolusId = bolusId,
                        expectedEndAtMillis = expectedEnd ?: it.expectedEndAtMillis,
                    )
                }
            }
            return
        }

        // 3) 其余维持单槽语义（无主笔 / 主笔就是这笔 / 主笔为纯方波）。
        //
        // 但「主笔已落定」是特例：此时 46 报的是另一笔（泵上仍在跑的扩展），
        // 绝不能 `begin` 复活主笔 —— 那会把扩展的 id 写进主槽，与第二槽指向同一笔，
        // 细条就变成两行都「扩展正在输注」、两个按钮都「取消扩展」，并保留上一笔的
        // 取消失败账本，出现假的「Bolus Not Stopped」（2026-11-11 真机 01:32 抓到的现场）。
        if (current == null) {
            holder.begin(
                BolusDeliveryState.ACTIVE,
                bolusId,
                hasImmediateLeg = false,
                expectedEndAtMillis = expectedEnd,
                now = now,
            )
            return
        }
        if (current.isFinished) {
            val extended = holder.extendedSession
            if (extended == null || extended.isFinished) {
                holder.setExtended(
                    BolusDelivery(
                        state = BolusDeliveryState.ACTIVE,
                        bolusId = bolusId,
                        hasImmediateLeg = false,
                        startedAtMillis = now,
                        expectedEndAtMillis = expectedEnd,
                        lastEvidenceAtMillis = now,
                    )
                )
            } else {
                holder.updateExtended(now) {
                    it.copy(
                        state = BolusDeliveryState.ACTIVE,
                        bolusId = bolusId,
                        expectedEndAtMillis = expectedEnd ?: it.expectedEndAtMillis,
                    )
                }
            }
            return
        }
        when (current.state) {
            BolusDeliveryState.CANCELLING ->
                holder.update(now) { it.copy(lastEvidenceAtMillis = now) }

            BolusDeliveryState.UNKNOWN ->
                if (current.bolusId != null && current.bolusId == bolusId) {
                    holder.update(now) {
                        it.copy(
                            state = BolusDeliveryState.ACTIVE,
                            expectedEndAtMillis = expectedEnd ?: it.expectedEndAtMillis,
                            lastEvidenceAtMillis = now,
                        )
                    }
                } else {
                    holder.begin(
                        BolusDeliveryState.ACTIVE,
                        bolusId,
                        hasImmediateLeg = false,
                        expectedEndAtMillis = expectedEnd,
                        now = now,
                    )
                }

            else ->
                holder.update(now) {
                    it.copy(
                        state = BolusDeliveryState.ACTIVE,
                        // 46 的 id 只在主笔还没有 id 时用于认领；绝不改写已有的 id，
                        // 否则主笔会被写成第二槽那笔的 id，两槽重合。
                        bolusId = it.bolusId ?: bolusId,
                        expectedEndAtMillis = expectedEnd ?: it.expectedEndAtMillis,
                        lastEvidenceAtMillis = now,
                    )
                }
        }
    }

    /** 泵时间戳是泵本地墙上时间，需先经 [pumpTimeToLocalTz] 对齐到本机时钟。 */
    private fun extendedEndAtMillis(pumpTimestampSeconds: Long, durationSeconds: Long): Long? {
        if (pumpTimestampSeconds <= 0) return null
        val startLocal = pumpTimeToLocalTz(Dates.fromJan12008EpochSecondsToDate(pumpTimestampSeconds))
        return startLocal.toEpochMilli() + durationSeconds * 1000
    }

    /**
     * -97：发起大剂量响应。`status=0 && statusTypeId=0` → 已开始（ACTIVE）；
     * 其余（含库枚举里不存在的 `statusTypeId=1`）→ **立即失败**，不与 60s 超时混同。
     */
    private fun onInitiateBolus(message: InitiateBolusResponse) {
        val current = holder.session ?: return
        if (current.isFinished) return

        val initiated = message.wasBolusInitiated()
        if (!initiated) {
            Timber.w(
                "bolus: -97 拒绝开始 status=%s statusTypeId=%s",
                message.status,
                message.statusTypeId,
            )
            finish(
                BolusDeliveryState.IDLE,
                BolusOutcome.Failed(BolusError.NACK),
                clock(),
            )
            return
        }

        val bolusId = message.bolusId.takeIf { it != 0 } ?: current.bolusId
        holder.update(clock()) {
            // 取消中收到（迟到的）-97 成功：不能把状态退回 ACTIVE，
            // 否则取消的终态兜底会从这一刻重新起算，甚至覆盖正在发出的 -96。
            if (it.state == BolusDeliveryState.CANCELLING) {
                it.copy(lastEvidenceAtMillis = clock())
            } else {
                it.copy(
                    state = BolusDeliveryState.ACTIVE,
                    bolusId = bolusId,
                )
            }
        }
    }

    /**
     * 48：最近一次大剂量。仅在 `bolusId` 与当前会话吻合时采纳，避免把几小时前的
     * 旧记录套到新会话上。`bolusStatusId`：3 = 完成，0 = 取消（§4.9 结束判据）。
     */
    private fun onLastBolus(bolusId: Int, status: BolusStatus?) {
        if (status == null) {
            anomaly("LastBolusStatus", "未知 bolusStatusId")
            return
        }
        val now = clock()

        // 先认领第二槽：旧扩展被取消时，它的 id 已经不在主笔上。
        // 若仍按「只认当前会话」过滤，这条终止证据会被丢掉，5 秒兜底随即误报
        // Bolus Not Stopped（真机 6919 的假警即由此产生）。
        val extended = holder.extendedSession
        if (bolusId != 0 && extended != null && !extended.isFinished && extended.bolusId == bolusId) {
            applyTerminalStatusToExtended(status, now)
            return
        }

        if (!matchesCurrentSession(bolusId)) return
        val current = holder.session ?: return
        if (current.isFinished) return
        applyTerminalStatus(status)
    }

    /** 第二笔（扩展）的落定：只改第二槽，主笔的结局与取消三态不受影响。 */
    private fun applyTerminalStatusToExtended(status: BolusStatus, now: Long) {
        when (status) {
            BolusStatus.COMPLETE ->
                holder.finishExtended(BolusDeliveryState.IDLE, BolusOutcome.Success, now)

            BolusStatus.STOPPED_USER_TERMINATED,
            BolusStatus.STOPPED_WIRELESS,
            -> holder.finishExtended(BolusDeliveryState.CANCELLED, BolusOutcome.Cancelled, now)

            BolusStatus.STOPPED_ALARM,
            BolusStatus.STOPPED_MALFUNCTION,
            BolusStatus.TERMINATED_PLGS,
            -> holder.finishExtended(BolusDeliveryState.IDLE, BolusOutcome.Failed(BolusError.ALARM), now)

            BolusStatus.REJECTED_WIRELESS ->
                holder.finishExtended(BolusDeliveryState.IDLE, BolusOutcome.Failed(BolusError.NACK), now)
        }
    }

    private fun onLastBolusV3(message: LastBolusStatusV3Response) {
        when {
            message.isStandardBolusPresent ->
                onLastBolus(message.standardBolusId, BolusStatus.fromId(message.standardBolusStatusId))
            message.isExtendedBolusPresent ->
                onLastBolus(message.extendedBolusId, BolusStatus.fromId(message.extendedBolusStatusId))
        }
    }

    private fun matchesCurrentSession(bolusId: Int): Boolean =
        bolusId != 0 && holder.session?.bolusId == bolusId

    private fun applyTerminalStatus(status: BolusStatus) {
        val now = clock()
        when (status) {
            BolusStatus.COMPLETE ->
                finish(BolusDeliveryState.IDLE, BolusOutcome.Success, now)

            BolusStatus.STOPPED_USER_TERMINATED,
            BolusStatus.STOPPED_WIRELESS,
            -> finish(BolusDeliveryState.CANCELLED, BolusOutcome.Cancelled, now)

            BolusStatus.STOPPED_ALARM,
            BolusStatus.STOPPED_MALFUNCTION,
            BolusStatus.TERMINATED_PLGS,
            -> finish(BolusDeliveryState.IDLE, BolusOutcome.Failed(BolusError.ALARM), now)

            BolusStatus.REJECTED_WIRELESS ->
                finish(BolusDeliveryState.IDLE, BolusOutcome.Failed(BolusError.NACK), now)
        }
    }

    /**
     * -94：权限响应。授予 → [BolusDeliveryState.CONFIRMING]（等 `-98` 发出后才起算阶梯）；
     * NACK → `IDLE` + [BolusError.NACK]；「等待中」是瞬态，不改状态。
     */
    private fun onPermission(message: BolusPermissionResponse) {
        val nackReason = message.nackReason
        if (nackReason == null) {
            anomaly(message.javaClass.simpleName, "未知 nackReason=${message.nackReasonId}")
            return
        }
        if (message.isPermissionGranted) {
            if (message.bolusId == 0) {
                Timber.w("bolus: 权限已授予但缺少 bolusId，等待 44 补全")
            }
            // -94 只表示「可以发」，**不创建会话**：真正的会话由 markInitiateSent（-98 发出）建立。
            // 之前在这里无条件 set(CONFIRMING, startedAt=null)，一旦用户中途退出流程，
            // 迟到的授权会留下一个没有 -98、也没有出口的 CONFIRMING，并且会被落盘还原。
            // 已有会话（已发出 / 正在取消 / 已 ACTIVE）时同样不能被迟到的 -94 回退状态。
            val current = holder.session
            if (current != null && !current.isFinished) {
                holder.update(clock()) { it }
            }
            outcome = BolusOutcome.Pending
            return
        }
        if (nackReason == BolusPermissionResponse.NackReason.MPP_STATE_WAITING_FOR_RESPONSE) return
        finish(BolusDeliveryState.IDLE, BolusOutcome.Failed(BolusError.NACK), clock())
    }

    /**
     * -96：取消响应。`wasCancelled=true` → `CANCELLED` + [BolusOutcome.Cancelled]；
     * `false` → `UNKNOWN` + `Failed(fromCancel)`（泵没确认停止，不能声称已停）。
     */
    private fun onCancel(message: CancelBolusResponse) {
        val bolusId = message.bolusId.takeIf { it != 0 }
        val now = clock()
        val cancelled = message.wasCancelled()

        // 按 id 认领：取消扩展时 -95 带回的是扩展的 id，不能一律落到主笔上，
        // 否则会把还在正常输注的普通那笔误判成「已取消 / 失败」。
        val extended = holder.extendedSession?.takeIf { !it.isFinished }
        if (bolusId != null && extended != null && extended.bolusId == bolusId) {
            if (cancelled) {
                holder.finishExtended(BolusDeliveryState.CANCELLED, BolusOutcome.Cancelled, now)
            } else {
                Timber.w("bolus: 扩展取消失败 status=%s reason=%s", message.status, message.reason)
                holder.finishExtended(
                    BolusDeliveryState.UNKNOWN,
                    BolusOutcome.Failed(
                        error = BolusError.NACK,
                        statusId = message.statusId,
                        reasonId = message.reasonId,
                        fromCancel = true,
                    ),
                    now,
                )
            }
            return
        }

        if (cancelled) {
            finish(BolusDeliveryState.CANCELLED, BolusOutcome.Cancelled, now, bolusId)
        } else {
            Timber.w("bolus: 取消失败 status=%s reason=%s", message.status, message.reason)
            // 泵没有确认停止：不能声称已停。用 UNKNOWN 保留「可能仍在输注」的不确定性，
            // 结局仍标 fromCancel，供结局页给出断管指引。
            finish(
                BolusDeliveryState.UNKNOWN,
                BolusOutcome.Failed(
                    error = BolusError.NACK,
                    statusId = message.statusId,
                    reasonId = message.reasonId,
                    fromCancel = true,
                ),
                now,
                bolusId,
            )
        }
    }

    /**
     * 落定结局：状态 + `finishedAtMillis` + outcome 一次写入，避免页面/通知看到半截状态。
     */
    private fun finish(
        state: BolusDeliveryState,
        result: BolusOutcome,
        now: Long,
        bolusId: Int? = null,
    ) {
        outcome = result
        holder.update(now) {
            it.copy(
                state = state,
                bolusId = bolusId ?: it.bolusId,
                finishedAtMillis = now,
                endObservedAtMillis = it.endObservedAtMillis ?: now,
                outcome = result,
            )
        }
    }

    private fun anomaly(messageName: String, reason: String) {
        Timber.w("bolus: %s 解析异常（%s），本次忽略", messageName, reason)
    }

    private companion object {
        const val EXTENDED_STATUS_NONE = 0

        /** 活跃证据后 3 秒内的空结果不作结束证据（46 / -74 语义不同，见 onExtendedBolus）。 */
        const val EXTENDED_END_DEBOUNCE_MS = 3_000L
    }
}
