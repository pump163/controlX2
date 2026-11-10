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
    /** 数据域：电池 / 储药 / 胰岛素 / 复位时长。保底域，不受预算拒绝。 */
    PUMP_VITALS,
    /** 数据域：IOB（ControlIQ 与非 ControlIQ 两种请求等价互替）。 */
    IOB,
    /** 数据域：当前基础率。首页常驻，保底。 */
    BASAL,
    /** 数据域：上一次大剂量。首页常驻，保底。 */
    BOLUS_LAST,
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
        /**
         * 请求类 simpleName → topic。第 0 步只读、只用于计数。
         *
         * 归属原则：**按数据域，不按页面**。同一个请求被多个页面共用时（如
         * HomeScreenMirrorRequest 被 5 个页面共用），一律归到它的数据域 topic，
         * 页面独占的请求才归到页面 topic。这样统计能直接看出「共用成本」与
         * 「页面独占成本」，而不是把共用请求重复计入每个页面。
         */
        fun fromRequestSimpleName(simpleName: String?): PumpTopic {
            return when (simpleName) {
                null -> UNKNOWN

                // 数据域 PUMP_VITALS：电池 / 储药 / 胰岛素 / 复位时长（保底域）
                "CurrentBatteryRequest",
                "CurrentBatteryV1Request",
                "CurrentBatteryV2Request",
                "InsulinStatusRequest",
                "TimeSinceResetRequest" -> PUMP_VITALS

                "HistoryLogStatusRequest",
                // 连接握手时问一次泵的 API 版本
                "ApiVersionRequest" -> STATUS_BATCH

                // CIQ 状态（首页常驻，保底）
                "ControlIQInfoV1Request",
                "ControlIQInfoV2Request" -> CONTROL_IQ

                // 首页 / 各页面共同依赖的当前状态
                "ControlIQIOBRequest",
                "NonControlIQIOBRequest" -> IOB

                "CurrentBasalStatusRequest" -> BASAL
                "LastBolusStatusRequest",
                "LastBolusStatusV2Request" -> BOLUS_LAST

                // 历史回补
                "HistoryLogRequest" -> HISTORY

                // 首页镜像
                "HomeScreenMirrorRequest" -> HOME_MIRROR

                // CGM
                "CGMStatusRequest",
                "CurrentEGVGuiDataRequest" -> CGM

                // 泵全局设置（SoundSettings / QuickBolusSettings 共用）
                "PumpGlobalsRequest" -> PUMP_GLOBALS

                // 安全上限（首页与 SafetyLimits 共用同一条）
                "GlobalMaxBolusSettingsRequest",
                "BasalLimitSettingsRequest" -> SAFETY_LIMITS

                // CIQ 设置
                "ControlIQSleepScheduleRequest" -> CONTROL_IQ

                // 大剂量进行中的状态
                "CurrentBolusStatusRequest",
                "ExtendedBolusStatusRequest",
                "ExtendedBolusStatusV2Request" -> BOLUS_OUTCOME

                // 报警 / 故障（通知页与首页角标共用同一批）
                "AlertStatusRequest",
                "AlarmStatusRequest",
                "CGMAlertStatusRequest",
                "MalfunctionStatusRequest",
                "ReminderStatusRequest",
                "HighestAamRequest",
                "ActiveAamBitsRequest" -> NOTIFICATIONS

                // 动作页独占
                "TempRateRequest" -> ACTIONS

                // 耗材页独占
                "LoadStatusRequest" -> CARTRIDGE

                // 个人方案页独占
                "ProfileStatusRequest",
                "IDPSettingsRequest",
                "IDPSegmentRequest" -> PROFILE

                else -> UNKNOWN
            }
        }

        /** 响应 simpleName → topic 的粗映射；仅用于回复计数，未知返回 null。 */
        fun fromResponseSimpleName(simpleName: String?): PumpTopic? {
            return when (simpleName) {
                null -> null

                "CurrentBatteryAbstractResponse",
                "CurrentBatteryV1Response",
                "CurrentBatteryV2Response",
                "InsulinStatusResponse",
                "TimeSinceResetResponse" -> PUMP_VITALS

                "HistoryLogStatusResponse",
                "ApiVersionResponse" -> STATUS_BATCH

                "ControlIQInfoV1Response",
                "ControlIQInfoV2Response" -> CONTROL_IQ

                "ControlIQIOBResponse",
                "NonControlIQIOBResponse" -> IOB

                "CurrentBasalStatusResponse" -> BASAL
                "LastBolusStatusResponse",
                "LastBolusStatusV2Response" -> BOLUS_LAST

                "HistoryLogResponse",
                "HistoryLogStreamResponse" -> HISTORY

                "HomeScreenMirrorResponse" -> HOME_MIRROR

                "CGMStatusResponse",
                "CurrentEGVGuiDataResponse" -> CGM

                "PumpGlobalsResponse" -> PUMP_GLOBALS

                "GlobalMaxBolusSettingsResponse",
                "BasalLimitSettingsResponse" -> SAFETY_LIMITS

                "ControlIQSleepScheduleResponse" -> CONTROL_IQ

                "CurrentBolusStatusResponse",
                "ExtendedBolusStatusResponse",
                "ExtendedBolusStatusV2Response" -> BOLUS_OUTCOME

                "AlertStatusResponse",
                "AlarmStatusResponse",
                "CGMAlertStatusResponse",
                "MalfunctionBitmaskStatusResponse",
                "ReminderStatusResponse",
                "HighestAamResponse",
                "ActiveAamBitsResponse" -> NOTIFICATIONS

                "TempRateResponse" -> ACTIONS

                "LoadStatusResponse" -> CARTRIDGE

                "ProfileStatusResponse",
                "IDPSettingsResponse",
                "IDPSegmentResponse" -> PROFILE

                else -> null
            }
        }
    }
}
