package com.pumppan.controlx2.query

/**
 * 查询层收敛 · 第 0 步（登记与旁路）
 *
 * 本文件只做「分类」：把一次真实发送归到某个 topic 上，供计数桩记账。
 * 第 0 步不据此改变任何发送行为——映射结果只进内存计数器，不参与调度。
 *
 * 映射按 pumpx2 请求类的 simpleName 做，避免 shared 模块直接依赖 pumpx2 类，
 * 也避免混淆器改名后编译期耦合。未知类一律归到 UNKNOWN，不抛异常。
 */
enum class PumpTopic {
    DASHBOARD_BATCH,
    NOTIFICATIONS,
    ACTIONS,
    CARTRIDGE,
    PROFILE,
    CONTROL_IQ,
    SAFETY_LIMITS,
    PUMP_GLOBALS,
    CGM,
    HOME_MIRROR,
    STATUS_BATCH,
    HISTORY,
    BOLUS_OUTCOME,
    UNKNOWN;

    companion object {
        /** 请求类 simpleName → topic。第 0 步只读、只用于计数。 */
        fun fromRequestSimpleName(simpleName: String?): PumpTopic {
            return when (simpleName) {
                null -> UNKNOWN

                // 周期状态批（CommService 5 分钟）
                "CurrentBatteryRequest",
                "InsulinStatusRequest",
                "HistoryLogStatusRequest",
                "ControlIQInfoV1Request",
                "ControlIQInfoV2Request" -> STATUS_BATCH

                // 首页 / 各页面共同依赖的当前状态
                "ControlIQIOBRequest",
                "NonControlIQIOBRequest" -> DASHBOARD_BATCH

                // 历史回补
                "HistoryLogRequest" -> HISTORY

                // 首页镜像
                "HomeScreenMirrorRequest" -> HOME_MIRROR

                // CGM
                "CGMStatusRequest" -> CGM

                // 泵全局设置（SoundSettings / QuickBolusSettings 共用）
                "PumpGlobalsRequest" -> PUMP_GLOBALS

                // CIQ 设置
                "ControlIQSleepScheduleRequest" -> CONTROL_IQ

                // 大剂量进行中的状态
                "CurrentBolusStatusRequest" -> BOLUS_OUTCOME

                else -> UNKNOWN
            }
        }

        /** 响应 opCode → topic 的粗映射；仅用于回复计数，未知返回 null。 */
        fun fromResponseSimpleName(simpleName: String?): PumpTopic? {
            return when (simpleName) {
                null -> null
                "CurrentBatteryAbstractResponse",
                "InsulinStatusResponse",
                "HistoryLogStatusResponse" -> STATUS_BATCH
                "ControlIQIOBResponse",
                "NonControlIQIOBResponse" -> DASHBOARD_BATCH
                "HistoryLogResponse" -> HISTORY
                "HomeScreenMirrorResponse" -> HOME_MIRROR
                "CGMStatusResponse" -> CGM
                "PumpGlobalsResponse" -> PUMP_GLOBALS
                "ControlIQSleepScheduleResponse" -> CONTROL_IQ
                "CurrentBolusStatusResponse" -> BOLUS_OUTCOME
                else -> null
            }
        }
    }
}
