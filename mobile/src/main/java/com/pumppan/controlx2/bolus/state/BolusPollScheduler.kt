package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import timber.log.Timber

/**
 * 输注期查询调度器（设计 §4.9.3）。
 *
 * 规则：
 * - **同一 opCode 只允许一个请求在飞**；超时才允许重发；10 秒内问过的不重复发。
 * - 立即段：每 [immediatePollIntervalMs] 查一次 `44`（当前大剂量状态）。
 * - 延展段：连接时由 [BolusReconciler] 对账一次，运行期每 [extendedPollIntervalMs] 用 `46` 兜底。
 * - 纯方波（无立即段）不发 `44`（延展的 `45` 全程为零，没有信息量）。
 * - 确认阶梯期间（CONFIRMING / UNCONFIRMED）用立即段间隔取证据；落到 UNKNOWN 后停止。
 *
 * 所有时间都由调用方传入，便于单测注入时钟。
 */
class BolusPollScheduler(
    private val requestCurrentBolusStatus: () -> Unit,
    private val requestExtendedBolusStatus: () -> Unit,
    private val immediatePollIntervalMs: Long = 10_000L,
    private val extendedPollIntervalMs: Long = 300_000L,
    private val requestTimeoutMs: Long = 10_000L,
) {
    private var currentInFlightAt: Long? = null
    private var extendedInFlightAt: Long? = null
    private var lastCurrentAt: Long = Long.MIN_VALUE
    private var lastExtendedAt: Long = Long.MIN_VALUE

    /** 新会话开始：清空在飞与节流时间，避免沿用上一笔的窗口。 */
    fun reset(now: Long) {
        currentInFlightAt = null
        extendedInFlightAt = null
        lastCurrentAt = now
        lastExtendedAt = now
    }

    /** 对账刚跑完：下一次兜底从对账结束时刻起算，避免立刻重复发。 */
    fun markReconciled(now: Long) = reset(now)

    /** 收到响应：对应 opCode 的请求不再算「在飞」。 */
    fun onMessage(message: Message) {
        when (message) {
            is CurrentBolusStatusResponse -> currentInFlightAt = null
            is ExtendedBolusStatusResponse,
            is ExtendedBolusStatusV2Response,
            -> extendedInFlightAt = null

            else -> Unit
        }
    }

    /**
     * 由协调器 ticker 每秒调用。未连接时只清超时窗口，不发请求。
     *
     * @return 是否本次真的发出了请求（供单测/诊断）。
     */
    fun tick(delivery: BolusDelivery?, connected: Boolean, now: Long): Boolean {
        if (delivery == null || delivery.isFinished) {
            currentInFlightAt = null
            extendedInFlightAt = null
            return false
        }
        // 超时的在飞请求可以重发；仍在 timeout 窗口内的绝不重发。
        if (currentInFlightAt != null && now - currentInFlightAt!! >= requestTimeoutMs) {
            currentInFlightAt = null
        }
        if (extendedInFlightAt != null && now - extendedInFlightAt!! >= requestTimeoutMs) {
            extendedInFlightAt = null
        }
        if (!connected) return false

        var sent = false

        if (needsCurrentPoll(delivery)) {
            val interval = immediatePollIntervalMs
            if (currentInFlightAt == null && now - lastCurrentAt >= interval) {
                requestCurrentBolusStatus()
                lastCurrentAt = now
                currentInFlightAt = now
                sent = true
                Timber.d("bolus: 查询 44（%s）", delivery.state)
            }
        }

        if (needsExtendedPoll(delivery)) {
            // 确认阶段还没证明「已经开始」，用立即段间隔拿 46 证据；进入 ACTIVE 后回到 5 分钟兜底。
            val interval = if (delivery.state == BolusDeliveryState.ACTIVE) {
                extendedPollIntervalMs
            } else {
                immediatePollIntervalMs
            }
            if (extendedInFlightAt == null && now - lastExtendedAt >= interval) {
                requestExtendedBolusStatus()
                lastExtendedAt = now
                extendedInFlightAt = now
                sent = true
                Timber.d("bolus: 查询 46（%s）", delivery.state)
            }
        }

        return sent
    }

    private fun needsCurrentPoll(delivery: BolusDelivery): Boolean = when (delivery.state) {
        BolusDeliveryState.CONFIRMING,
        BolusDeliveryState.UNCONFIRMED,
        BolusDeliveryState.CANCELLING,
        -> delivery.hasImmediateLeg

        // 立即段尚未结束：10 秒一次；45 归零后立即段结束，改由 46 兜底延展段。
        BolusDeliveryState.ACTIVE ->
            delivery.hasImmediateLeg &&
                delivery.immediateLegDoneAtMillis == null &&
                delivery.endObservedAtMillis == null

        else -> false
    }

    private fun needsExtendedPoll(delivery: BolusDelivery): Boolean {
        if (delivery.expectedEndAtMillis == null) return false
        if (delivery.endObservedAtMillis != null) return false
        return when (delivery.state) {
            BolusDeliveryState.CONFIRMING,
            BolusDeliveryState.UNCONFIRMED,
            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            -> true

            else -> false
        }
    }
}
