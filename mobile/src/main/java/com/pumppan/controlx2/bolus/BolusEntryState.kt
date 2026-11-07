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

    /** 延后段在跑：二选一页（取消 / 追加普通大剂量）。 */
    EITHER_OR,

    /** 刚完成：完成页（数秒后自动收起）。 */
    DONE,

    /** 对账中 / 断连 / 申请中：只读取消页。 */
    READONLY_CANCEL,
}

/**
 * 入口四态状态机的**单点映射**：导航栏「大剂量」落到哪个页面只看这里。
 * 唯一输入是 [BolusDelivery]（状态 + `expectedEndAtMillis` + `isExtendedRunning`），
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

        BolusDeliveryState.ACTIVE ->
            if (delivery.isExtendedRunning) BolusEntryPage.EITHER_OR else BolusEntryPage.CANCEL

        BolusDeliveryState.CANCELLING -> BolusEntryPage.CANCEL

        BolusDeliveryState.CANCELLED -> BolusEntryPage.DONE
    }
}
