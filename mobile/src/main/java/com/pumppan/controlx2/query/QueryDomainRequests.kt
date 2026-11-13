package com.pumppan.controlx2.query

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.models.NotificationBundle
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BasalLimitSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusCalcDataSnapshotRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQInfoV2Request
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBatteryV2Request
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.GlobalMaxBolusSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.InsulinStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.LastBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpGlobalsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest
import com.jwoglom.controlx2.presentation.screens.sections.dashboardTier2Commands
import com.jwoglom.controlx2.presentation.screens.sections.dashboardTier3Commands
import com.jwoglom.controlx2.presentation.screens.sections.cgmStatusCycleCommands
import com.jwoglom.controlx2.presentation.screens.sections.controlIQSettingsCycleCommands
import com.jwoglom.controlx2.presentation.screens.sections.profileActionsCommands

/**
 * 「一个数据域包含哪些请求」的唯一登记处。
 *
 * 这是「发现单发遗漏只改一处」里的那**一处**：之后新增/删减请求只改这里的 when，
 * 调用方（协调器、一次性任务、页面订阅）都不需要跟着动。
 *
 * 只登记**只读**请求；控制 / 签名 / 取消类不进这张表（它们必须独立下发）。
 */
object QueryDomainRequests {

    /** 一次性任务：大剂量前置检查（参数 + 血糖 + IOB + 两种在跑的大剂量）。 */
    const val BOLUS_PRECHECK = "BOLUS_PRECHECK"
    const val BOLUS_OUTCOME = "BOLUS_OUTCOME"

    /** 页面域：只在对应页面可见时由协调器下发（策略里 `onlyWhenVisible=true`）。 */
    // 2026-11-12：原第一档（电量/储药/IOB）已整体并入 `PUMP_VITALS` / `IOB` 保底域，
    // 两者频率同为 60s，再留一个独立域只会让同一条 opCode 每拍发两遍。
    /** 首页第二档：底栏状态 / 最大大剂量（120s；基础率与上次大剂量已升为保底域）。 */
    const val DASHBOARD_DETAIL = "DASHBOARD_DETAIL"
    /** 首页第三档：血糖 + 闭环状态（300s，二者强相关一起问）。 */
    const val DASHBOARD_CGM = "DASHBOARD_CGM"
    const val PUMP_STATE = "PUMP_STATE"
    const val NOTIFICATIONS = "NOTIFICATIONS"
    const val CARTRIDGE = "CARTRIDGE"
    const val SAFETY_LIMITS = "SAFETY_LIMITS"
    const val PUMP_GLOBALS = "PUMP_GLOBALS"

    /** 子页域：CGM 页与闭环设置页各自一个域，可见时才按 60s 走。 */
    const val CGM_STATUS = "CGM_STATUS"
    const val CONTROL_IQ_SETTINGS = "CONTROL_IQ_SETTINGS"
    /** 基础率档案（IDP 多轮）：页面等待循环推进后续轮次，域只负责每 60s 重发第一轮。 */
    const val PROFILE = "PROFILE"

    /**
     * 历史日志状态查询（原 `HistoryLogSyncWorker` 的 5 min 定时 + 旧 `CommService` 批里的那一条）。
     * 只问状态，真正的补拉仍由 `HistoryLogFetcher` 在收到回包后触发。
     */
    const val HISTORY = "HISTORY"

    /** 保底域：基础率 / 上次大剂量（120s，原首页第二档与旧 5min 批的对应项）。 */
    const val BASAL = "BASAL"
    const val BOLUS_LAST = "BOLUS_LAST"

    /** 未登记的域返回空列表；协调器会跳过空域，不会「发了个空包」。 */
    fun forDomain(domain: String): List<Message> = when (domain) {
        // 保底域：电池 / 储药 / 胰岛素 / 复位时长
        "PUMP_VITALS" -> listOf(
            CurrentBatteryV2Request(),
            InsulinStatusRequest(),
            TimeSinceResetRequest(),
        )

        // IOB：X2 上两种请求可互替，先固定用 ControlIQ 版
        "IOB" -> listOf(ControlIQIOBRequest())

        // 当前基础率：首页常驻卡片，保底
        "BASAL" -> listOf(CurrentBasalStatusRequest())

        // 上一次大剂量：首页常驻卡片，保底
        "BOLUS_LAST" -> listOf(LastBolusStatusRequest())

        // 血糖：会话/传感器状态一条。EGV 图形值留在首页第三档（可见时才要画图），
        // 这样即使 CGM 未启用，这里的 300s 探活也不会顺带追问一条没有读数的 EGV。
        "CGM" -> listOf(
            CGMStatusRequest(),
        )

        // CIQ 状态：闭环开关与当前工作态；频率可低，但不能不查
        "CONTROL_IQ" -> listOf(ControlIQInfoV2Request())

        // 历史日志状态：300s 一条，替代旧 5min 批里的 HistoryLogStatus 与 Worker 定时
        HISTORY -> listOf(HistoryLogStatusRequest())

        // 首页三档：都在页面同一份 getter 里切分，避免两处导出一份清单后各自漂移。
        // 第一档（电量/储药/IOB）已并入保底域，这里从第二档开始。
        // 第二档：变动慢的几项，120s。
        DASHBOARD_DETAIL -> dashboardTier2Commands

        // 第三档：血糖图形值（300s，仅首页可见）；CGM 会话状态已由 `CGM` 保底域覆盖。
        DASHBOARD_CGM -> dashboardTier3Commands

        // CGM 子页：只要会话状态一条
        CGM_STATUS -> cgmStatusCycleCommands

        // 闭环设置子页：闭环参数 + 睡眠时段
        CONTROL_IQ_SETTINGS -> controlIQSettingsCycleCommands

        // 基础率档案（IDP 多轮）：页面自己的等待循环负责推进后续轮次，
        // 这里每 60s 只重发第一轮，与页面原先的 intervalOf(60) 完全等价。
        PROFILE -> profileActionsCommands

        // 运行态：暂停 / 运动 / 睡眠 / 临时基础率 / 闭环状态都在这一批里
        PUMP_STATE -> listOf(
            HomeScreenMirrorRequest(),
            ControlIQInfoV2Request(),
            TempRateRequest(),
        )

        // 通知详情：7 条通知包。只有通知页可见时才查（页面订阅），
        // 不再由首页每 60s 顺带刷一遍——泵的 ALERT 事件已能触发补查。
        NOTIFICATIONS -> NotificationBundle.allRequests()

        // 耗材/装填：HomeMirror/TimeSinceReset 供底部状态栏，CGMStatus 决定 CGM 区状态。
        // LoadStatus(0x14) 不进本域：X2 收到它会回 ErrorResponse 并被泵主动断链
        // （REMOTE_USER_TERMINATED_CONNECTION，2026-11-13 logcat 取证），且这是 Mobi 专有查询。
        // Mobi 需要时由 CartridgeActions 页面按型号自行补发（见 cartridgeActionsCommands(model)）。
        CARTRIDGE -> listOf(
            HomeScreenMirrorRequest(),
            CGMStatusRequest(),
            TimeSinceResetRequest(),
        )

        // 安全限制：最大大剂量与基础率上限，设置页可见时查
        SAFETY_LIMITS -> listOf(
            GlobalMaxBolusSettingsRequest(),
            BasalLimitSettingsRequest(),
        )

        // 泵全局设置（声音等级 / QuickBolus）：设置子页可见时查
        PUMP_GLOBALS -> listOf(PumpGlobalsRequest())

        // 一次性任务：大剂量前置检查。向导只声明「要这个任务」，
        // 具体请求清单留在这里，页面不再自己列。
        BOLUS_PRECHECK -> listOf(
            BolusCalcDataSnapshotRequest(),
            CurrentEGVGuiDataRequest(),
            ControlIQIOBRequest(),
            CurrentBolusStatusRequest(),
            ExtendedBolusStatusRequest(),
        )

        // 窗口域（1s，仅在大剂量确认窗内开）：只发 44。
        //
        // 为什么这里不含 46：46（延展）是条件查询——只有确认存在第二笔延展时才该问，
        // 判断在 `BolusPollScheduler.needsExtendedPoll`。塞进 1s 窗口会让确认期变成
        // 2 条/秒，正好违背「不因抖动叠加补查」。44 的节拍由协调器接管，46 仍归调度器。
        BOLUS_OUTCOME -> listOf(
            CurrentBolusStatusRequest(),
        )

        else -> emptyList()
    }

    fun isSupported(domain: String): Boolean = forDomain(domain).isNotEmpty()

    /**
     * 已登记请求清单的域全集。
     *
     * 给 SHADOW 观测用：影子模式不发包，但需要把「本该发什么」算全，
     * 否则只观测两个域就失去对照意义。
     */
    fun registeredDomains(): Set<String> = setOf(
        "PUMP_VITALS", "IOB", "BASAL", "BOLUS_LAST", "CGM", "CONTROL_IQ", HISTORY,
        DASHBOARD_DETAIL, DASHBOARD_CGM,
        CGM_STATUS, CONTROL_IQ_SETTINGS, PROFILE,
        PUMP_STATE, NOTIFICATIONS, CARTRIDGE, SAFETY_LIMITS, PUMP_GLOBALS,
        BOLUS_PRECHECK, BOLUS_OUTCOME,
    )

    /**
     * 周期性轮询域（排除大剂量的一次性预检/结果域）。
     *
     * 影子观测只该看轮询域：BOLUS_OUTCOME 的 intervalMs 是 1s，且真实语义是「大剂量进行中
     * 才该查」，把它算进固定节拍会让「本该发多少条」虚高，对照失去意义。
     */
    fun pollingDomains(): Set<String> = registeredDomains() - setOf(BOLUS_PRECHECK, BOLUS_OUTCOME)
}
