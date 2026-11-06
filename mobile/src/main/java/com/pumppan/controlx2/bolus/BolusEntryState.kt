package com.pumppan.controlx2.bolus

import com.pumppan.controlx2.bolus.state.SessionPhase

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
 * 业务代码不要在各处写 `when (phase)`。
 */
fun phaseToPage(phase: SessionPhase?): BolusEntryPage = when (phase) {
    null,
    SessionPhase.DRAFT,
    SessionPhase.ABORTED,
    SessionPhase.FAILED,
    -> BolusEntryPage.DRAFT

    SessionPhase.DELIVERING,
    SessionPhase.CANCELLING,
    -> BolusEntryPage.CANCEL

    SessionPhase.EXTENDED_RUNNING -> BolusEntryPage.EITHER_OR

    SessionPhase.DONE -> BolusEntryPage.DONE

    SessionPhase.PERMITTING,
    SessionPhase.AWAITING_PHONE,
    SessionPhase.UNKNOWN,
    -> BolusEntryPage.READONLY_CANCEL
}
