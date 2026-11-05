package com.pumppan.controlx2.bolus.state

import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse
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
 * 被动消费泵侧大剂量状态报文，把证据映射到 [BolusSessionHolder] 的 [SessionPhase] 与
 * [BolusOutcome]。S1 只读：不发送任何请求、不改变上游发送路径。
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
 * | `-96` | `CancelBolusResponse` | -95 |
 *
 * 文档里的编号是**请求** opcode：正向响应 = 请求 + 1，负向响应 = 请求绝对值 − 1。
 * `-92` 是 `LastBolusStatusV2Request` 的请求 opcode，1.9.0 里没有对应响应报文，因此无需消费。
 *
 * 未知报文一律忽略；单条报文解析失败（字段缺失 / 无枚举值）只记一条日志，不抛异常、不改状态。
 */
class BolusSessionController(
    private val mode: () -> BolusControllerMode = { BolusControllerMode.LEGACY },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val holder = BolusSessionHolder(clock = clock)

    val session: BolusSession?
        get() = holder.session

    var outcome: BolusOutcome = BolusOutcome.Pending
        private set

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
                is BolusPermissionResponse -> onPermission(message)
                is CancelBolusResponse -> onCancel(message)
                else -> Unit
            }
        } catch (e: Exception) {
            Timber.w(e, "bolus: 忽略解析失败的 %s", message.javaClass.simpleName)
        }
    }

    /**
     * 44：当前大剂量状态。`ALREADY_DELIVERED_OR_INVALID` 视为空（Idle）；
     * `REQUESTING` / `DELIVERING` 且带 `bolusId` 时视为在输注（两者的区别只在泵内部，
     * 对「是否有大剂量在进行」没有影响）。
     */
    private fun onCurrentBolus(message: CurrentBolusStatusResponse) {
        when (message.status) {
            CurrentBolusStatus.ALREADY_DELIVERED_OR_INVALID -> holder.clear()

            CurrentBolusStatus.REQUESTING, CurrentBolusStatus.DELIVERING -> {
                if (message.bolusId == 0) {
                    anomaly(message.javaClass.simpleName, "进行中但缺少 bolusId")
                    return
                }
                // 延展段在跑时，44 可能仍返回 DELIVERING；此时不要把阶段降级回 DELIVERING。
                val phase = if (holder.session?.phase == SessionPhase.EXTENDED_RUNNING) null else SessionPhase.DELIVERING
                holder.onEvidence(phase = phase, bolusId = message.bolusId)
            }

            null -> anomaly(message.javaClass.simpleName, "未知 status=${message.statusId}")
        }
    }

    /**
     * 46 / -74：延展大剂量状态。`bolusStatus=0` 或 `bolusId=0` 表示当前没有延展段，
     * 不改状态（避免清掉正在进行的立即段）；否则进入 [SessionPhase.EXTENDED_RUNNING]，
     * 并由「泵时钟起点 + duration」换算剩余时间。
     */
    private fun onExtendedBolus(status: Int, bolusId: Int, pumpTimestampSeconds: Long, durationSeconds: Long) {
        if (status == EXTENDED_STATUS_NONE || bolusId == 0 || durationSeconds <= 0) return
        holder.onEvidence(
            phase = SessionPhase.EXTENDED_RUNNING,
            bolusId = bolusId,
            expectedEndAtMillis = extendedEndAtMillis(pumpTimestampSeconds, durationSeconds),
        )
    }

    /** 泵时间戳是泵本地墙上时间，需先经 [pumpTimeToLocalTz] 对齐到本机时钟。 */
    private fun extendedEndAtMillis(pumpTimestampSeconds: Long, durationSeconds: Long): Long? {
        if (pumpTimestampSeconds <= 0) return null
        val startLocal = pumpTimeToLocalTz(Dates.fromJan12008EpochSecondsToDate(pumpTimestampSeconds))
        return startLocal.toEpochMilli() + durationSeconds * 1000
    }

    /**
     * 48：最近一次大剂量。仅在 `bolusId` 与当前会话吻合时采纳，避免把几小时前的
     * 旧记录套到新会话上。
     */
    private fun onLastBolus(bolusId: Int, status: BolusStatus?) {
        if (!matchesCurrentSession(bolusId)) return
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
        when (status) {
            BolusStatus.COMPLETE -> {
                holder.onEvidence(phase = SessionPhase.DONE)
                outcome = BolusOutcome.Success
            }

            BolusStatus.STOPPED_USER_TERMINATED,
            BolusStatus.STOPPED_WIRELESS,
            -> {
                holder.onEvidence(phase = SessionPhase.DONE)
                outcome = BolusOutcome.Cancelled
            }

            BolusStatus.STOPPED_ALARM,
            BolusStatus.STOPPED_MALFUNCTION,
            BolusStatus.TERMINATED_PLGS,
            -> {
                holder.onEvidence(phase = SessionPhase.FAILED)
                outcome = BolusOutcome.Failed(BolusError.ALARM)
            }

            BolusStatus.REJECTED_WIRELESS -> {
                holder.onEvidence(phase = SessionPhase.FAILED)
                outcome = BolusOutcome.Failed(BolusError.NACK)
            }
        }
    }

    /**
     * -94：权限响应。授予且带 `bolusId` → [SessionPhase.AWAITING_PHONE]（等 -98 / 44 证据）；
     * 授予但缺 `bolusId` → 仍进 [SessionPhase.AWAITING_PHONE]，id 留空（取消行为 NEEDS_ID）；
     * NACK → [SessionPhase.FAILED] + [BolusError.NACK]；「等待中」是瞬态，不改状态。
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
            holder.begin(
                phase = SessionPhase.AWAITING_PHONE,
                bolusId = message.bolusId.takeIf { it != 0 },
            )
            outcome = BolusOutcome.Pending
            return
        }
        if (nackReason == BolusPermissionResponse.NackReason.MPP_STATE_WAITING_FOR_RESPONSE) return
        holder.onEvidence(
            phase = SessionPhase.FAILED,
            bolusId = message.bolusId.takeIf { it != 0 },
        )
        outcome = BolusOutcome.Failed(BolusError.NACK)
    }

    /**
     * -96：取消响应。`wasCancelled=true` → [SessionPhase.DONE] + [BolusOutcome.Cancelled]；
     * `false` → [SessionPhase.FAILED] + [BolusError.NACK]（按设计 §4.5 的 CANCELLING 出口）。
     */
    private fun onCancel(message: CancelBolusResponse) {
        val bolusId = message.bolusId.takeIf { it != 0 }
        if (message.wasCancelled()) {
            holder.onEvidence(phase = SessionPhase.DONE, bolusId = bolusId)
            outcome = BolusOutcome.Cancelled
        } else {
            Timber.w("bolus: 取消失败 status=%s reason=%s", message.status, message.reason)
            holder.onEvidence(phase = SessionPhase.FAILED, bolusId = bolusId)
            outcome = BolusOutcome.Failed(BolusError.NACK)
        }
    }

    private fun anomaly(messageName: String, reason: String) {
        Timber.w("bolus: %s 解析异常（%s），本次忽略", messageName, reason)
    }

    private companion object {
        const val EXTENDED_STATUS_NONE = 0
    }
}
