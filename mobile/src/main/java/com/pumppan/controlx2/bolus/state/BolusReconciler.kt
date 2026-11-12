package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV3Response
import timber.log.Timber

/** 四步对账的步骤名（顺序即执行顺序）。 */
enum class ReconcileStep {
    CURRENT,
    EXTENDED,
    EXTENDED_V2,
    LAST,
}

/**
 * 对账所需的只读请求出口。生产实现是一个薄适配器，直接复用现有的
 * `MainActivity.sendPumpCommands(SendType.STANDARD, ...)`，不新增服务。
 */
interface BolusStatusRequestSender {
    fun requestCurrentBolusStatus()
    fun requestExtendedBolusStatus()
    fun requestExtendedBolusStatusV2()
    fun requestLastBolusStatus()
}

/**
 * S2 的冷启动 / 重连四步对账状态机：
 *
 * `CurrentBolusStatus(44)` → `ExtendedBolusStatus(46)` → `ExtendedBolusStatusV2(-74)` → `LastBolusStatusV2/V3(48)`
 * （可选的只读历史不在本状态机内）。任一步无响应不阻断后续；单步超时（默认 2s）跳过并记日志；
 * 整体超时（默认 5s）落 [BolusActivity.UNKNOWN]，不落 `Idle`。
 *
 * 对账期间 [BolusSessionHolder.reconciling] 恒为 true：`cancelRowState` 只读（`RECONCILING`）、
 * `isAnyBolusActive` 取 `Unknown`；本类**不发任何写命令**。
 *
 * 由 `onMessage`（收到对应响应）与 `onTick`（定时器）共同驱动，便于单测注入时钟。
 * LEGACY 模式下 [start] 直接返回，不发出任何请求。
 */
class BolusReconciler(
    private val sender: BolusStatusRequestSender,
    private val holder: BolusSessionHolder,
    private val mode: () -> BolusControllerMode = { BolusControllerMode.V2 },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val stepTimeoutMillis: Long = STEP_TIMEOUT_MILLIS,
    private val overallTimeoutMillis: Long = OVERALL_TIMEOUT_MILLIS,
) {
    var running: Boolean = false
        private set

    /** 对账落定的三态；对账未完成或未启动时为 null。 */
    var result: BolusActivity? = null
        private set

    var currentStep: ReconcileStep? = null
        private set

    private val responded = mutableSetOf<ReconcileStep>()
    private var stepIndex = 0
    private var stepStartedAt = 0L
    private var overallDeadline = 0L
    private var sawActiveEvidence = false

    /** 一次没人回应的对账排下的重试时刻；0 表示没有待重试。 */
    private var retryAtMillis = 0L

    /** 连续「一条都没回应」的重试次数，用来给重试封顶（不做无限查询）。 */
    private var emptyAttempts = 0

    fun start(now: Long = clock(), isRetry: Boolean = false) {
        if (running) return
        if (mode() != BolusControllerMode.V2) return
        running = true
        result = null
        currentStep = null
        retryAtMillis = 0L
        if (!isRetry) emptyAttempts = 0
        responded.clear()
        stepIndex = 0
        stepStartedAt = now
        overallDeadline = now + overallTimeoutMillis
        sawActiveEvidence = false
        holder.markReconciling(true)
        advance(now)
    }

    /** 是否还欠一次「空转重试」。ticker 用它决定要不要继续活下去。 */
    fun hasPendingRetry(): Boolean = !running && retryAtMillis > 0L

    /**
     * 由 ticker 每拍调用：到了重试时刻就重新发起。
     *
     * 为什么需要：`pump-connected` 广播只代表 ACL 建链，泵侧会话初始化要几秒；
     * 这期间发出去的三条查询全部超时，等于白跑一次对账（2026-11-12 真机三次复现）。
     * 空转不是结论，所以隔一会儿再问，最多 [MAX_EMPTY_ATTEMPTS] 次。
     */
    fun retryIfDue(now: Long = clock()): Boolean {
        if (running || retryAtMillis <= 0L) return false
        if (now < retryAtMillis) return false
        retryAtMillis = 0L
        Timber.i("bolus: 对账重试（上一次没有任何回应，第 %d 次）", emptyAttempts + 1)
        start(now, isRetry = true)
        return true
    }

    /** 收到一条泵侧报文：记录对应步骤已完成，并立刻推进到下一步。 */
    fun onMessage(message: Message, now: Long = clock()) {
        if (!running) return
        val step = stepOf(message) ?: return
        responded += step
        noteEvidence(message)
        if (step == currentStep) advance(now)
    }

    /** 由生产侧定时器驱动（例如每 500ms 一次）；单步超时跳过，整体超时落 Unknown。 */
    fun onTick(now: Long = clock()) {
        if (!running) return
        if (now >= overallDeadline) {
            Timber.w("bolus: 对账整体超时（%dms），落 Unknown", overallTimeoutMillis)
            finish(now)
            return
        }
        if (now - stepStartedAt >= stepTimeoutMillis) {
            Timber.w("bolus: 对账步骤 %s 超时（%dms），跳过", currentStep, stepTimeoutMillis)
            advance(now)
        }
    }

    private fun advance(now: Long) {
        if (stepIndex >= STEPS.size) {
            finish(now)
            return
        }
        val step = STEPS[stepIndex]
        stepIndex++
        stepStartedAt = now
        currentStep = step
        Timber.d("bolus: 对账发起 %s", step)
        when (step) {
            ReconcileStep.CURRENT -> sender.requestCurrentBolusStatus()
            ReconcileStep.EXTENDED -> sender.requestExtendedBolusStatus()
            ReconcileStep.EXTENDED_V2 -> sender.requestExtendedBolusStatusV2()
            ReconcileStep.LAST -> sender.requestLastBolusStatus()
        }
    }

    private fun finish(now: Long) {
        running = false
        currentStep = null
        result = when {
            sawActiveEvidence -> BolusActivity.ACTIVE

            hasCompleteIdleEvidence() -> {
                holder.clear()
                BolusActivity.IDLE
            }

            // 三条查询一条都没回来（典型：链路刚建、泵侧会话还没初始化）：这不是
            // 「不知道泵上有没有」，而是「根本没问到」。以前这里会造一个 UNCONFIRMED 占位，
            // 页面随即显示「正在向泵申请」，而零结果的 44 又清不掉占位，只能等 60s 阶梯落
            // UNKNOWN 才松手（2026-11-12 12:25 真机复现）。
            // 现在保持现状并排一次重试：空转不是结论，等链路真就绪后再问（最多几次）。
            responded.isEmpty() && holder.session == null -> {
                emptyAttempts++
                if (emptyAttempts < MAX_EMPTY_ATTEMPTS) {
                    retryAtMillis = now + RETRY_DELAY_MILLIS
                } else {
                    Timber.w("bolus: 对账连续 %d 次没有任何回应，放弃本次连接对账", emptyAttempts)
                }
                BolusActivity.IDLE
            }

            else -> {
                // 状态闩锁修复：对账整体超时未带齐结论时，不把交付态硬锁成不可自愈的 UNKNOWN，
                // 转入 UNCONFIRMED（等待证据），由轮询层继续补证，后续证据到位即自愈。
                holder.begin(BolusDeliveryState.UNCONFIRMED, now = now)
                BolusActivity.UNKNOWN
            }
        }
        holder.markReconciling(false)
        Timber.i("bolus: 对账落定 %s", result)
    }

    /** 三条「当前状态」都拿到了空结果，才算确定 Idle；48（历史）可选。 */
    private fun hasCompleteIdleEvidence(): Boolean =
        responded.contains(ReconcileStep.CURRENT) &&
            responded.contains(ReconcileStep.EXTENDED) &&
            responded.contains(ReconcileStep.EXTENDED_V2)

    private fun noteEvidence(message: Message) {
        when (message) {
            is CurrentBolusStatusResponse -> {
                val inFlight = message.status == CurrentBolusStatus.REQUESTING ||
                    message.status == CurrentBolusStatus.DELIVERING
                if (inFlight && message.bolusId != 0) sawActiveEvidence = true
            }

            is ExtendedBolusStatusResponse -> {
                if (message.bolusStatus != 0 && message.bolusId != 0 && message.duration > 0) {
                    sawActiveEvidence = true
                }
            }

            is ExtendedBolusStatusV2Response -> {
                if (message.bolusStatus != 0 && message.bolusId != 0 && message.duration > 0) {
                    sawActiveEvidence = true
                }
            }
        }
    }

    private fun stepOf(message: Message): ReconcileStep? = when {
        message is CurrentBolusStatusResponse -> ReconcileStep.CURRENT
        message is ExtendedBolusStatusResponse -> ReconcileStep.EXTENDED
        message is ExtendedBolusStatusV2Response -> ReconcileStep.EXTENDED_V2
        message is LastBolusStatusResponse ||
            message is LastBolusStatusV2Response ||
            message is LastBolusStatusV3Response -> ReconcileStep.LAST
        else -> null
    }

    internal companion object {
        val STEPS = listOf(
            ReconcileStep.CURRENT,
            ReconcileStep.EXTENDED,
            ReconcileStep.EXTENDED_V2,
            ReconcileStep.LAST,
        )
        const val STEP_TIMEOUT_MILLIS = 2_000L
        const val OVERALL_TIMEOUT_MILLIS = 5_000L

        /** 一条都没回应时的重试间隔：比泵侧会话初始化（实测 ~10s）宽一点。 */
        const val RETRY_DELAY_MILLIS = 10_000L

        /** 连续空转的重试上限，避免对着一根死链路反复发查询。 */
        const val MAX_EMPTY_ATTEMPTS = 3
    }
}
