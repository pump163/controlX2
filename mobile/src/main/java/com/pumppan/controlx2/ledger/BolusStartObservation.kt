package com.pumppan.controlx2.ledger

/**
 * 「一笔大剂量的开始证据」只读快照。
 *
 * 设计依据 `docs/feature/定时大剂量-设计.md` R22：链运行期间若观测到**非本链**的
 * 开始证据（`45 statusId=2` / `statusId=1`），整链立即作废。链侧只订阅本类型，
 * 不再自行解析 `CurrentBolusStatusResponse`，避免两套判定逻辑各说各话。
 *
 * @property statusId 原始 `CurrentBolusStatus.id`（2=REQUESTING，1=DELIVERING）
 * @property requestedMilliunits 泵回显的请求量（毫单位）；X2 的 `CurrentBolusStatusResponse` 有该字段，
 *   链侧用它判断「这一笔是不是计划里那笔」，拿不到时为 null，判定退化为「看时刻」。
 * @property observedAtMillis 观测到的墙钟时刻（毫秒）
 */
data class BolusStartObservation(
    val bolusId: Int,
    val statusId: Int,
    val phase: BolusLedgerPhase,
    val observedAtMillis: Long,
    val requestedMilliunits: Long? = null,
)
