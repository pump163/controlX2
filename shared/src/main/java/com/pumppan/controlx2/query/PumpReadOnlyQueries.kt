package com.pumppan.controlx2.query

/**
 * 查询层收敛 · 只读白名单（第 3 步：物理出口分流）。
 *
 * 为什么必须用名单而不是类型判断：pumpx2 把查询与控制都放在 `request` 包下，
 * `Message` 没有「只读」基类，只能显式列举。
 *
 * 判定原则：**白名单优先**——不在名单里的一律直发。
 * 这样漏判的后果只是「少一次单飞保护」（退化为旧行为），不会把控制类挡下来。
 *
 * 名单来源 = `PumpTopic.fromRequestSimpleName` 覆盖的全部请求（该映射本来就是
 * 按「只读数据域」划的），再人工核对一遍排除 Set/Initiate/Cancel/Dismiss。
 * 另补了三处页面独占、未进 PumpTopic 映射表的读查询（BolusCalcDataSnapshot /
 * LastBG / GetSavedG7PairingCode）——它们只是在统计里落 UNKNOWN，仍然是只读。
 *
 * 明确不进名单的（必须直发）：
 * - `InitiateBolusRequest`：X2 可以发立即大剂量（真机验证）
 * - `CancelBolusRequest`：X2 可取消泵上已存在的立即/延展大剂量，且原设计要求多发
 * - `DismissNotificationRequest`：X2 无法取消泵上通知（已由 `canControlPump` 门控）
 * - 一切 `Set*` / `Change*` / `Suspend*` / `Resume*` / `Stop*` 控制类
 * - `BolusPermissionRequest` 等签名/确认类
 */
object PumpReadOnlyQueries {

    private val READ_ONLY: Set<String> = setOf(
        // 周期状态批（CommService / 各页共用）
        "CurrentBatteryRequest",
        "CurrentBatteryV1Request",
        "CurrentBatteryV2Request",
        "InsulinStatusRequest",
        "HistoryLogStatusRequest",
        "ControlIQInfoV1Request",
        "ControlIQInfoV2Request",
        "TimeSinceResetRequest",
        "ApiVersionRequest",

        // 首页 / 各页共同依赖的当前状态
        "ControlIQIOBRequest",
        "NonControlIQIOBRequest",
        "CurrentBasalStatusRequest",
        "LastBolusStatusRequest",
        "LastBolusStatusV2Request",

        // 历史回补
        "HistoryLogRequest",

        // 首页镜像
        "HomeScreenMirrorRequest",

        // CGM（只读；未启用时由 CgmQueryGate 在调用点挡住，不在这里挡）
        "CGMStatusRequest",
        "CurrentEGVGuiDataRequest",

        // 泵全局设置 / 安全上限 / CIQ 睡眠时段（读）
        "PumpGlobalsRequest",
        "GlobalMaxBolusSettingsRequest",
        "BasalLimitSettingsRequest",
        "ControlIQSleepScheduleRequest",

        // 大剂量进行中的状态
        "CurrentBolusStatusRequest",
        "ExtendedBolusStatusRequest",
        "ExtendedBolusStatusV2Request",

        // 大剂量计算输入（向导第二步 / 旧 BolusWindow 的只读快照）
        "BolusCalcDataSnapshotRequest",
        "LastBGRequest",
        // 大剂量许可变更原因（Debug 页手动读一次；currentStatus 包，只读）
        "BolusPermissionChangeReasonRequest",

        // 报警 / 故障（读）
        "AlertStatusRequest",
        "AlarmStatusRequest",
        "CGMAlertStatusRequest",
        "MalfunctionStatusRequest",
        "ReminderStatusRequest",
        "HighestAamRequest",
        "ActiveAamBitsRequest",

        // 动作页 / 耗材页（读；SetTempRateRequest 是控制，不在名单）
        "TempRateRequest",
        "LoadStatusRequest",

        // 个人方案（读）
        "ProfileStatusRequest",
        "IDPSettingsRequest",
        "IDPSegmentRequest",

        // CGM 配对码（读：CGMActions 的「获取配对码」，不是 Set 类）
        "GetSavedG7PairingCodeRequest",
    )

    /** 按请求类 simpleName 判定；null / 未知类一律视为非只读（直发）。 */
    fun isReadOnly(simpleName: String?): Boolean =
        simpleName != null && READ_ONLY.contains(simpleName)

    /** 供单测/诊断：当前白名单条目数。 */
    fun size(): Int = READ_ONLY.size
}
