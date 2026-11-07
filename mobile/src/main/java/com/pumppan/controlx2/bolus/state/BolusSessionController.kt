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
) {
    val holder = BolusSessionHolder(clock = clock)

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
     * 由协调器的 ticker 驱动：确认阶梯（10s 警示 / 60s UNKNOWN）与延展超时兜底。
     * 没有时钟推进时，只有报文到达才会改变状态。
     */
    fun onTick(now: Long = clock()) {
        if (mode() != BolusControllerMode.V2) return
        val current = holder.session ?: return
        if (current.isFinished) return

        when (current.state) {
            BolusDeliveryState.CONFIRMING -> {
                val startedAt = current.startedAtMillis ?: return
                if (now - startedAt >= CONFIRM_UNCONFIRMED_MS) {
                    holder.update(now) { it.copy(state = BolusDeliveryState.UNCONFIRMED) }
                }
            }

            BolusDeliveryState.UNCONFIRMED -> {
                val startedAt = current.startedAtMillis ?: return
                if (now - startedAt >= CONFIRM_UNKNOWN_MS) {
                    Timber.w("bolus: 发出 -98 后 %dms 仍无开始证据，落 UNKNOWN", CONFIRM_UNKNOWN_MS)
                    holder.update(now) { it.copy(state = BolusDeliveryState.UNKNOWN) }
                }
            }

            BolusDeliveryState.ACTIVE -> {
                val expectedEnd = current.expectedEndAtMillis ?: return
                if (now > expectedEnd + EXTENDED_UNKNOWN_GRACE_MS) {
                    Timber.w("bolus: 延展预计结束 %dms 后仍无 47/-91，落 UNKNOWN", EXTENDED_UNKNOWN_GRACE_MS)
                    holder.update(now) { it.copy(state = BolusDeliveryState.UNKNOWN) }
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

            // CONFIRMING / UNCONFIRMED / UNKNOWN：零结果不足以证明「没开始」，继续等 / 保持未知。
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
                holder.update(now) {
                    it.copy(
                        state = BolusDeliveryState.ACTIVE,
                        bolusId = bolusId,
                        lastEvidenceAtMillis = now,
                    )
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
        val current = holder.session
        if (status == EXTENDED_STATUS_NONE || bolusId == 0 || durationSeconds <= 0) {
            if (current == null || current.isFinished) return
            if (!current.isExtendedRunning) return
            holder.update(now) {
                if (it.endObservedAtMillis == null) it.copy(endObservedAtMillis = now) else it
            }
            return
        }

        val expectedEnd = extendedEndAtMillis(pumpTimestampSeconds, durationSeconds)
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
            holder.begin(
                BolusDeliveryState.ACTIVE,
                bolusId,
                hasImmediateLeg = false,
                expectedEndAtMillis = expectedEnd,
                now = now,
            )
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
                        bolusId = bolusId,
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
            it.copy(
                state = BolusDeliveryState.ACTIVE,
                bolusId = bolusId,
            )
        }
    }

    /**
     * 48：最近一次大剂量。仅在 `bolusId` 与当前会话吻合时采纳，避免把几小时前的
     * 旧记录套到新会话上。`bolusStatusId`：3 = 完成，0 = 取消（§4.9 结束判据）。
     */
    private fun onLastBolus(bolusId: Int, status: BolusStatus?) {
        if (!matchesCurrentSession(bolusId)) return
        val current = holder.session ?: return
        if (current.isFinished) return
        if (status == null) {
            anomaly("LastBolusStatus", "未知 bolusStatusId")
            return
        }
        applyTerminalStatus(status)
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
            outcome = BolusOutcome.Pending
            holder.set(
                BolusDelivery(
                    state = BolusDeliveryState.CONFIRMING,
                    bolusId = message.bolusId.takeIf { it != 0 },
                    // 阶梯从 -98 发出时起算；-94 只表示「可以发」。
                    startedAtMillis = null,
                    lastEvidenceAtMillis = clock(),
                )
            )
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
        if (message.wasCancelled()) {
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

        /** 0–10s 未确认 → UNCONFIRMED（提示检查泵，继续等）。 */
        const val CONFIRM_UNCONFIRMED_MS = 10_000L

        /** 60s 仍无开始证据 → UNKNOWN（不给重试）。 */
        const val CONFIRM_UNKNOWN_MS = 60_000L

        /** 延展预计结束后的兜底：5 分钟仍无 47/-91 → UNKNOWN（§4.1）。 */
        const val EXTENDED_UNKNOWN_GRACE_MS = 5 * 60_000L
    }
}
