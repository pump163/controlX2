package com.pumppan.controlx2.bolus

/**
 * 泵侧 `BolusStatus` 的 id（`LastBolusStatusAbstractResponse.BolusStatus`）。
 *
 * **全项目唯一常量表**：定时链、流水卡、普通大剂量界面都读这里，
 * 禁止各调用点再写 `3` / `0..6` 之类的字面量。
 */
object BolusTerminalStatus {
    const val USER_CANCELLED = 0
    const val ALARM = 1
    const val MALFUNCTION = 2
    const val COMPLETE = 3
    const val WIRELESS = 4
    const val REJECTED = 5
    const val PLGS = 6

    /** 是否属于已知终态（0..6）。null 表示「还没有终态」。 */
    fun isKnown(statusId: Int?): Boolean =
        statusId != null && statusId in USER_CANCELLED..PLGS
}

/** `CurrentBolusStatus.id`：泵正在输注。 */
const val CURRENT_BOLUS_DELIVERING = 1

/** `CurrentBolusStatus.id`：泵已受理、尚未开始。 */
const val CURRENT_BOLUS_REQUESTING = 2

/**
 * 一笔大剂量的**交付证据等级**——全项目唯一口径（用户 2026-11-13 定）：
 * 「已发出」只证明**本机把请求发出去了**；只有泵回执 `BolusStatus.complete`
 * 才允许说「已输注」。两者不可互换，任何界面都不得拿「有 bolusId」冒充已输注。
 */
enum class BolusDeliveryEvidence {
    /** 计划中/待发送：没有任何发出记录。 */
    NONE,

    /** 本机已发出（或已登记 bolusId），但泵还没有终态回执 → 只能说「已发出」。 */
    SENT,

    /** 泵侧正在输注（`CurrentBolusStatus` = REQUESTING / DELIVERING）→ 「输注中」。 */
    DELIVERING,

    /** 泵回执 COMPLETE → **唯一**可以说「已输注」的证据。 */
    DELIVERED,

    /** 泵回执为取消/拒绝/报警/故障等非正常结束 → 「已中止」。 */
    FAILED,
}

/**
 * 唯一的证据判定函数。优先级：终态 > 泵侧活动态 > 本机发出记录。
 *
 * @param activeStatusId `CurrentBolusStatus.id`（1=DELIVERING、2=REQUESTING），无活动笔传 null
 * @param terminalStatusId `BolusStatus.id`（0..6），未收到终态传 null
 * @param hasSendRecord 本机是否已发出请求或已登记该笔 bolusId
 */
fun bolusDeliveryEvidence(
    activeStatusId: Int?,
    terminalStatusId: Int?,
    hasSendRecord: Boolean,
): BolusDeliveryEvidence = when {
    terminalStatusId == BolusTerminalStatus.COMPLETE -> BolusDeliveryEvidence.DELIVERED
    BolusTerminalStatus.isKnown(terminalStatusId) -> BolusDeliveryEvidence.FAILED
    activeStatusId == CURRENT_BOLUS_DELIVERING || activeStatusId == CURRENT_BOLUS_REQUESTING ->
        BolusDeliveryEvidence.DELIVERING
    hasSendRecord -> BolusDeliveryEvidence.SENT
    else -> BolusDeliveryEvidence.NONE
}
