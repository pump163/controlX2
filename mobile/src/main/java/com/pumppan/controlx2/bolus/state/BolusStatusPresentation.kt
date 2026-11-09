package com.pumppan.controlx2.bolus.state

/**
 * 输注状态的语义分类。三个渲染位置（首页卡片 / 底栏细条 / 大剂量页段二）共用，
 * 只描述「是哪一种状态」，不携带任何可显示文案。
 */
enum class BolusStatusKind {
    /** 没有进行中的大剂量，也没有待展示的结局。 */
    IDLE,

    /** 已发出 `-98`，等待泵确认开始。 */
    PENDING,

    /** 立即段正在输注。 */
    DELIVERING,

    /** 立即段已结束，延展段正在输注。 */
    EXTENDED,

    /** 已发出 `-96`，等待泵确认停止。 */
    CANCELLING,

    /** 无法确认是否仍在输注（对账失败 / 证据过期）。 */
    UNKNOWN,

    /** 已落定：成功 / 已取消 / 失败（具体文案看 [BolusStatusPresentation.outcome]）。 */
    FINISHED_SUCCESS,
    FINISHED_CANCELLED,
    FINISHED_FAILED,
}

/**
 * 一次大剂量的「可展示快照」——**三个位置唯一的数据来源**。
 *
 * 各位置只负责自己的布局与容量（卡片 = 多行、细条 = 一行 + 按钮、段二 = 一句话），
 * 相位判断、取消可用性、失败着色全部来自这里，避免三处各写一套 `when`。
 */
data class BolusStatusPresentation(
    val kind: BolusStatusKind,
    val outcome: BolusOutcome,
    /** 当前会话状态；无会话时为 null（供取消阻塞原因区分「未知 / 未连接」）。 */
    val deliveryState: BolusDeliveryState?,
    /** 取消行五态。 */
    val cancelRow: CancelRowState,
    /** 取消三态（10 秒确认窗口）。 */
    val cancelStatus: CancelStatus,
    /** 取消按钮是否可点：仅 READY 且不在确认窗口内。 */
    val cancelEnabled: Boolean,
    /** 是否处于「进行中」：首页卡片与底栏细条只在 true 时出现。 */
    val isOngoing: Boolean,
    /** 结局是否失败（决定文案着色；与 [BolusOutcome.Failed] 同源）。 */
    val failed: Boolean,
)

private val ONGOING_STATES = setOf(
    BolusDeliveryState.CONFIRMING,
    BolusDeliveryState.UNCONFIRMED,
    BolusDeliveryState.ACTIVE,
    BolusDeliveryState.CANCELLING,
    BolusDeliveryState.UNKNOWN,
)

/**
 * 唯一派生函数：输入同一份会话快照 + 结局 + 连通性，输出三个位置共用的展示语义。
 *
 * @param now 取时间基准（取消三态需要推进），调用方在同一次重组内传入同一个值。
 * @param holder 为空（预览 / 未登记）时取消相关字段按「不可用」处理。
 */
fun presentBolusStatus(
    delivery: BolusDelivery?,
    outcome: BolusOutcome,
    connected: Boolean,
    now: Long,
    holder: BolusSessionHolder? = null,
): BolusStatusPresentation {
    val kind = when {
        delivery == null ->
            if (outcome is BolusOutcome.Failed) BolusStatusKind.FINISHED_FAILED
            else BolusStatusKind.IDLE

        delivery.isFinished -> when (outcome) {
            BolusOutcome.Success -> BolusStatusKind.FINISHED_SUCCESS
            BolusOutcome.Cancelled -> BolusStatusKind.FINISHED_CANCELLED
            is BolusOutcome.Failed -> BolusStatusKind.FINISHED_FAILED
            // 落定但结局仍 Pending 理论上不出现；沿用旧的「按成功」渲染。
            BolusOutcome.Pending -> BolusStatusKind.FINISHED_SUCCESS
        }

        delivery.state == BolusDeliveryState.CONFIRMING ||
            delivery.state == BolusDeliveryState.UNCONFIRMED ->
            BolusStatusKind.PENDING

        delivery.state == BolusDeliveryState.ACTIVE ->
            if (delivery.isExtendedRunning) BolusStatusKind.EXTENDED
            else BolusStatusKind.DELIVERING

        delivery.state == BolusDeliveryState.CANCELLING -> BolusStatusKind.CANCELLING

        delivery.state == BolusDeliveryState.UNKNOWN -> BolusStatusKind.UNKNOWN

        else ->
            if (outcome is BolusOutcome.Failed) BolusStatusKind.FINISHED_FAILED
            else BolusStatusKind.IDLE
    }

    val cancelRow = holder?.cancelRowState(connected, now) ?: CancelRowState.HIDDEN
    val cancelStatus = holder?.cancelStatus(now) ?: CancelStatus.IDLE

    return BolusStatusPresentation(
        kind = kind,
        outcome = outcome,
        deliveryState = delivery?.state,
        cancelRow = cancelRow,
        cancelStatus = cancelStatus,
        cancelEnabled = cancelRow == CancelRowState.READY && cancelStatus != CancelStatus.SENDING,
        isOngoing = delivery != null && !delivery.isFinished && delivery.state in ONGOING_STATES,
        failed = outcome is BolusOutcome.Failed,
    )
}
