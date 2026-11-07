package com.pumppan.controlx2.bolus.state

/**
 * 输注期唯一状态（设计 §4.9）。横幅、通知、页面都只读这一个字段，不再各自维护相位。
 *
 * **不区分立即与延展**：是否还有延展段只看 [BolusDelivery.expectedEndAtMillis] 是否存在。
 * 结束后的「完成 / 停止 / 失败」不占用状态位，由同一份快照里的 [BolusDelivery.outcome] 区分：
 * - 正常打完 → `IDLE` + `Success`（没有进行中的大剂量）；
 * - 被取消 → `CANCELLED` + `Cancelled`；
 * - 被泵拒绝 / 报警 → `IDLE` + `Failed`；
 * - 无法确认 → `UNKNOWN`（不给重试入口，但保留取消入口）。
 */
enum class BolusDeliveryState {
    /** 没有进行中的大剂量。 */
    IDLE,

    /** 已发出 `-98`，等待 `-97 status=0` 或 `45 bolusId≠0` 确认开始（0–10s）。 */
    CONFIRMING,

    /** 超过 10s 仍未确认；继续等，到 60s 才落 UNKNOWN。 */
    UNCONFIRMED,

    /** 泵已确认在输注；延展段是否还在跑看 [BolusDelivery.expectedEndAtMillis]。 */
    ACTIVE,

    /** 无法确认是否仍在输注（对账失败 / 60s 阶梯耗尽 / 延展超时无证据）。 */
    UNKNOWN,

    /** 已发出 `-96`，等待泵确认停止。 */
    CANCELLING,

    /** 泵已确认停止。 */
    CANCELLED,
}

/**
 * 一次大剂量会话的完整快照（单一真相）。
 *
 * @param hasImmediateLeg 本次是否含立即段（双波 / 普通为 true，纯方波为 false）。
 * @param startedAtMillis 发起时刻（`-98` 发出时写入）；确认阶梯从这里起算。
 * @param expectedEndAtMillis 延展段预计结束时刻；**只有非 null 才表示还有延展段**。
 * @param immediateLegDoneAtMillis 立即段结束证据（`45 bolusId=0`）到达时刻。
 * @param endObservedAtMillis 整次输注的结束证据（立即段的 45 归零 / 延展段的 47 归零）到达时刻；
 *        只代表「已经结束」，不代表「全部输完」，判决仍等 `-91`。
 * @param finishedAtMillis 已落定结局的时刻（完成 / 取消 / 失败）；null 表示仍在等待判决。
 */
data class BolusDelivery(
    val state: BolusDeliveryState = BolusDeliveryState.IDLE,
    val bolusId: Int? = null,
    val hasImmediateLeg: Boolean = true,
    val startedAtMillis: Long? = null,
    val expectedEndAtMillis: Long? = null,
    val lastEvidenceAtMillis: Long = 0L,
    val immediateLegDoneAtMillis: Long? = null,
    val endObservedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /** 结局（与状态同一快照，页面/通知都从这里读）。 */
    val outcome: BolusOutcome = BolusOutcome.Pending,
) {
    val isFinished: Boolean get() = finishedAtMillis != null

    /** 延展段是否真的在跑（纯方波或立即段已结束）。 */
    val isExtendedRunning: Boolean
        get() = state == BolusDeliveryState.ACTIVE &&
            expectedEndAtMillis != null &&
            (!hasImmediateLeg || immediateLegDoneAtMillis != null)

    /** 是否处于「可能还在输注」的活跃态。 */
    val isLive: Boolean
        get() = !isFinished && when (state) {
            BolusDeliveryState.CONFIRMING,
            BolusDeliveryState.UNCONFIRMED,
            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            -> true

            BolusDeliveryState.IDLE,
            BolusDeliveryState.UNKNOWN,
            BolusDeliveryState.CANCELLED,
            -> false
        }
}
