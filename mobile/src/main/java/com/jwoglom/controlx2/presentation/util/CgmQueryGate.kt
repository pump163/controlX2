package com.jwoglom.controlx2.presentation.util

import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.shared.enums.CGMSessionState
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest
import com.pumppan.controlx2.query.QueryPolicyLoader

/**
 * CGM 查询门控 · 第 2 步（策略进配置，判定仍留在代码）。
 *
 * 背景：本机 G6 / G7 都处于「未启动」，但首页与储药器页仍按 60 s 节奏追问
 * `CGMStatusRequest`（opCode 80）与 `CurrentEGVGuiDataRequest`。泵侧既无会话也无读数，
 * 这些请求是纯无效流量——一小时窗口内 CGM 类请求 82 条，其中 10 条没有应答。
 *
 * 真值来源（§4.6.6）：**开关在配置，语义在代码**。
 *  - 开关：`states.skipCgmTopicWhenSessionStopped`（`pump-query-policy.json`）。
 *    为 false 时一律放行，等于回到旧的全量发送；
 *  - 语义：三态判定不外置——配置只能决定「STOPPED 时要不要省」，不能重新定义
 *    「什么叫没启用」。
 *
 * 三态判定（宁可多发一条，也不把「未知」误判成「永久未启用」）：
 *  - `null`（尚未收到过任何 CGM 状态）→ 允许：否则永远学不到「用户已启用」；
 *  - `STOPPED`（用户明确未启用）→ 禁止：普通页面不再每条 tick 追问；
 *  - `ACTIVE` / `STARTING` / `STOPPING` → 允许：正在使用，读数必须保持新鲜。
 *
 * 「STOPPED 之后如何发现用户又启用了」由 `CommService` 的 5 分钟任务补一条探活，
 * 这样不新增定时器（遵守设计 §3.9 的硬约束），代价是启用后最多 5 分钟才被发现。
 *
 * 注意：`CGMActions`（CGM 管理页）不受本门控影响——用户主动进入 CGM 页时仍直发，
 * 否则页面连「当前是未启用」都显示不出来。
 */
fun cgmQueryAllowed(): Boolean {
    // 配置未加载时 QueryPolicyLoader.current() 返回内建 FALLBACK，该键与 asset 同值（true），
    // 所以这里不需要额外的 null 判断。
    if (!QueryPolicyLoader.current().states.skipCgmTopicWhenSessionStopped) return true
    return dataStore.cgmSessionState.value != CGMSessionState.STOPPED
}

/** 该消息是否属于 CGM 数据域。用于从既有命令表里剔除，而不是重排命令表。 */
fun Message.isCgmQuery(): Boolean =
    this is CGMStatusRequest || this is CurrentEGVGuiDataRequest
