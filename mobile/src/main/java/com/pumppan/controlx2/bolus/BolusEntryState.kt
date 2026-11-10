package com.pumppan.controlx2.bolus

import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusOutcome

/** 大剂量入口的页面类别（§4.6.2）。 */
enum class BolusEntryPage {
    /** 无输注：动作表 + 可进四步输入。 */
    DRAFT,

    /** 立即段在打 / 正在取消：取消页（不给新输入入口）。 */
    CANCEL,

    /** 刚完成：完成页（数秒后自动收起）。 */
    DONE,

    /** 对账中 / 断连 / 申请中：只读取消页。 */
    READONLY_CANCEL,
}

/**
 * 入口四态状态机的**单点映射**：导航栏「大剂量」落到哪个页面只看这里。
 * 唯一输入是 [BolusDelivery]（状态 + 立即段是否仍在打），
 * 业务代码不要在各处写 `when (state)`。
 */
fun deliveryToPage(delivery: BolusDelivery?, outcome: BolusOutcome): BolusEntryPage {
    if (delivery == null) return BolusEntryPage.DRAFT
    if (delivery.isFinished) {
        // 失败 / 中止要留在 DRAFT 显示结局行；完成与取消进完成页。
        return if (outcome is BolusOutcome.Failed) BolusEntryPage.DRAFT else BolusEntryPage.DONE
    }
    return when (delivery.state) {
        BolusDeliveryState.IDLE -> BolusEntryPage.DRAFT

        BolusDeliveryState.CONFIRMING,
        BolusDeliveryState.UNCONFIRMED,
        BolusDeliveryState.UNKNOWN,
        -> BolusEntryPage.READONLY_CANCEL

        // 立即段在打 → 取消页，不给新输入入口；
        // 立即段已结束、只剩延展段在跑 → 泵允许再发起**立即**大剂量，回到可输入的 DRAFT。
        // 取消行由 [BolusActionMenu] 按 holder 状态独立渲染（与页面类别无关），
        // 所以延展段的取消入口不会因为这里改判 DRAFT 而消失。
        BolusDeliveryState.ACTIVE ->
            if (delivery.isImmediateLegLive) BolusEntryPage.CANCEL else BolusEntryPage.DRAFT

        BolusDeliveryState.CANCELLING -> BolusEntryPage.CANCEL

        BolusDeliveryState.CANCELLED -> BolusEntryPage.DONE
    }
}

/**
 * 入口页相位是否允许发起新的大剂量：空闲 / 已完成，或「只剩延展段在跑」的 DRAFT 才允许。
 *
 * 「大剂量」页的新增入口与「向导」页的确认按钮共用这一判定（单点），
 * 避免两处各自解释相位：DONE 表示这一笔已经结束（[BolusDelivery.isFinished]），
 * 不是「上一次大剂量尚未结束」；只有 CANCEL / READONLY_CANCEL（立即段仍在输注 /
 * 申请中 / 状态未知）才不允许发起新的一笔。
 *
 * 「只剩延展段」的情形由 [deliveryToPage] 映射成 DRAFT，因此这里不需要再判一次；
 * 延展段期间的形态限制（只允许立即大剂量）在 [BolusForm.validate] 统一把关。
 */
fun BolusEntryPage.allowsNewFlow(): Boolean =
    this == BolusEntryPage.DRAFT || this == BolusEntryPage.DONE
