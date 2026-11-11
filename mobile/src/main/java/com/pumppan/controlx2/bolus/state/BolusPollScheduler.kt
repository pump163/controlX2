package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.pumppan.controlx2.query.PumpCommandPort
import timber.log.Timber

/**
 * 输注期查询调度器（设计 §4.9.3）。
 *
 * 第 2 步改造：**单飞 / 超时 / 节流已下沉到 [PumpCommandPort]**，本类只保留
 * 「这一 tick 该不该问」的业务判定（立即段 / 延展段 / 确认阶梯 / 页面可见性）。
 * 判定规则与改造前逐条等价，由 `BolusPollSchedulerTest` 的 12 条用例守住。
 *
 * 规则：
 * - 立即段：每 [immediatePollIntervalMs] 查一次 `44`（当前大剂量状态）。
 * - 延展段：连接时由 [BolusReconciler] 对账一次，运行期每 [extendedPollIntervalMs] 用 `46` 兜底；
 *   补证只在「大剂量」页开着时进行（由调用方传入 `extendedPollAllowed`），页面不开时不发。
 * - 纯方波（无立即段）不发 `44`（延展的 `45` 全程为零，没有信息量）。
 * - 确认阶梯期间（CONFIRMING / UNCONFIRMED）用立即段间隔取证据；落到 UNKNOWN 后停止。
 *
 * 所有时间都由调用方传入，便于单测注入时钟。
 */
class BolusPollScheduler(
    private val port: PumpCommandPort,
    private val currentRequest: () -> Message,
    private val extendedRequest: () -> Message,
    private val immediatePollIntervalMs: Long = 10_000L,
    private val extendedPollIntervalMs: Long = 300_000L,
    private val requestTimeoutMs: Long = 10_000L,
    /**
     * `44` 是否已交由查询器代管（大剂量确认窗开着时为 true）。
     * 代管期间本类完全不发 44，避免「协调器 1s + 调度器 10s」同一秒撞两条。
     */
    private val currentPollDelegated: () -> Boolean = { false },
) {
    // opCode 只用于「清在飞」与「重设节流起点」，构造期算一次即可。
    private val currentOpCode: Int = currentRequest().opCode().toInt() and 0xFF
    private val extendedOpCode: Int = extendedRequest().opCode().toInt() and 0xFF

    /** 新会话开始：清空在飞并把节流起点设为 now，避免沿用上一笔的窗口。 */
    fun reset(now: Long) {
        port.markThrottle(currentOpCode, now)
        port.markThrottle(extendedOpCode, now)
    }

    /** 对账刚跑完：下一次兜底从对账结束时刻起算，避免立刻重复发。 */
    fun markReconciled(now: Long) = reset(now)

    /** 收到响应：对应 opCode 的请求不再算「在飞」（在飞记录归端口所有）。 */
    fun onMessage(message: Message) {
        when (message) {
            is CurrentBolusStatusResponse -> port.clearInFlight(currentOpCode)
            is ExtendedBolusStatusResponse,
            is ExtendedBolusStatusV2Response,
            -> port.clearInFlight(extendedOpCode)

            else -> Unit
        }
    }

    /**
     * 由协调器 ticker 每秒调用。未连接时直接不发（也不动节流窗口）。
     *
     * @param extendedPollAllowed 是否允许发延展补证（`46`）。只有「大剂量」页开着时才为 true：
     *   页面不开时不做无谓取证，避免加重 BLE 通信负担；立即段的 `44` 不受此门控。
     * @return 是否本次真的发出了请求（供单测/诊断）。
     */
    fun tick(
        delivery: BolusDelivery?,
        connected: Boolean,
        now: Long,
        extendedPollAllowed: Boolean = true,
    ): Boolean {
        if (delivery == null || delivery.isFinished) {
            port.clearInFlight(currentOpCode)
            port.clearInFlight(extendedOpCode)
            return false
        }
        if (!connected) return false

        var sent = false

        if (needsCurrentPoll(delivery) && !currentPollDelegated()) {
            val r = port.request(
                message = currentRequest(),
                minIntervalMs = immediatePollIntervalMs,
                timeoutMs = requestTimeoutMs,
                now = now,
            )
            if (r.sent) {
                sent = true
                Timber.d("bolus: 查询 44（%s）", delivery.state)
            }
        }

        if (needsExtendedPoll(delivery, extendedPollAllowed)) {
            // 确认阶段还没证明「已经开始」，用立即段间隔拿 46 证据；进入 ACTIVE 后回到 5 分钟兜底。
            val interval = if (delivery.state == BolusDeliveryState.ACTIVE) {
                extendedPollIntervalMs
            } else {
                immediatePollIntervalMs
            }
            val r = port.request(
                message = extendedRequest(),
                minIntervalMs = interval,
                timeoutMs = requestTimeoutMs,
                now = now,
            )
            if (r.sent) {
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

    private fun needsExtendedPoll(delivery: BolusDelivery, allowed: Boolean): Boolean {
        if (!allowed) return false
        if (delivery.endObservedAtMillis != null) return false
        // 只有「可能存在延展段」才需要 46 兜底：纯方波，或立即段已结束（双波的延展腿）。
        // 不能因 expectedEndAtMillis 为 null 就停轮询 —— 泵发起的延展常拿不到预计结束时刻，
        // 一旦停轮询就永远等不到 46 归零的结束证据，状态会一直挂在 ACTIVE。
        // 普通（NORMAL）大剂量：有立即段且尚未归零，没有延展腿，不发 46。
        if (delivery.hasImmediateLeg &&
            delivery.immediateLegDoneAtMillis == null &&
            delivery.expectedEndAtMillis == null
        ) {
            return false
        }
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
