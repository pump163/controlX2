package com.pumppan.controlx2.ledger

/** 流水条目类型。第一刀只有大剂量，报警消除条目留待下一轮。 */
enum class LedgerKind {
    BOLUS,
}

/**
 * 一笔大剂量的相位。
 *
 * 与 `com.pumppan.controlx2.bolus.state.BolusStatusKind` 不合并：那个枚举描述的是
 * 「本机发起的一笔」的会话状态（含取消在途、待确认），这里描述的是「泵上观测到的一笔」
 * 的客观生命周期，来源只有 [CurrentBolusStatusResponse] 与 [LastBolusStatusAbstractResponse]。
 */
enum class BolusLedgerPhase {
    /** 泵已受理、尚未开始输注（`CurrentBolusStatus.REQUESTING`）。 */
    REQUESTING,

    /** 正在输注（`CurrentBolusStatus.DELIVERING`）。 */
    DELIVERING,

    /** 已收到终态（`BolusStatus` 0..6）。 */
    FINISHED,

    /** 空态宽限期满仍无终态，只能记「未确认结束」；迟到的终态可回填为 [FINISHED]。 */
    UNKNOWN,
}

/**
 * 流水里的一条记录。**只在内存**，不写库、不写 DataStore、不写文件（设计 §4.1 硬规则）。
 *
 * 量的单位统一为「毫单位」（1 U = 1000），与 PumpX2 的 `requestedVolume` / `deliveredVolume` 一致，
 * 避免在数据层做浮点舍入；只在渲染时转成 U。
 */
data class LedgerEntry(
    val key: String,
    val kind: LedgerKind = LedgerKind.BOLUS,
    /** 笔主键：协议里的 bolusId。实测同一笔的开始与完成一致（6939 / 6941）。 */
    val bolusId: Int,
    val phase: BolusLedgerPhase,
    /** 泵受理时声明的总量；V1 终态没有这个字段，可能为 null。 */
    val requestedMilliUnits: Long? = null,
    /** 已交付量；只来自终态。 */
    val deliveredMilliUnits: Long? = null,
    /** 首次观测到这笔的时刻（本机毫秒）。 */
    val startedAtMillis: Long,
    /** 最后一次收到这笔报文的时刻。 */
    val lastSeenAtMillis: Long,
    /** 终态时刻；[phase] 为 UNKNOWN 时为宽限期满的时刻。 */
    val terminalAtMillis: Long? = null,
    /** `BolusStatus` 的 id（0..6）；null = 未确认结束。 */
    val terminalStatusId: Int? = null,
    /** 延展段的持续秒数；> 0 才判定为延展大剂量。 */
    val extendedDurationSec: Long = 0L,
    /** 重复到达的终态/状态报文条数（轮询噪声，用于诊断，不展示为主信息）。 */
    val repeatCount: Int = 0,
    /** 首次观测到空态的时刻；用于宽限期判定。 */
    val emptySinceMillis: Long? = null,
) {
    val isOngoing: Boolean
        get() = phase == BolusLedgerPhase.REQUESTING || phase == BolusLedgerPhase.DELIVERING

    val isExtended: Boolean
        get() = extendedDurationSec > 0L

    /** 排序用时刻：已终态按终态时刻，进行中按最近一次上报。 */
    val sortAtMillis: Long
        get() = terminalAtMillis ?: lastSeenAtMillis
}
