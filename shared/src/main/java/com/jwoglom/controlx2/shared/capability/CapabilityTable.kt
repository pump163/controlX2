package com.jwoglom.controlx2.shared.capability

import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * 控制类能力的枚举。只描述「这一类操作能不能做」，不描述具体请求或界面。
 *
 * 与 pumpx2 的 `@MessageProps.supportedDevices` 分工：
 * - 本表回答「这台泵、这个 API 版本，业务上允不允许做这类操作」；
 * - `supportsDevice()` 回答「这条请求在库里是否声明支持该型号」；
 * - 两者都通过才放行（[canControlPump] 重载负责组合）。
 */
enum class Capability {
    /** 挂起 / 恢复输注 */
    SUSPEND_RESUME,

    /** 临时基础率下发 */
    SET_TEMP_RATE,

    /** 睡眠 / 运动模式（SetModes） */
    SET_MODES,

    /** 泵声音设置 */
    SET_SOUNDS,

    /** 闭环（Control-IQ）参数设置 */
    SET_CONTROL_IQ,

    /** 安全上限（最大基础率 / 最大大剂量等） */
    SET_SAFETY_LIMITS,

    /** 大剂量快捷设置（步进、提醒阈值等） */
    QUICK_BOLUS_SETTINGS,

    /** 基础率档案（IDP）编辑与激活 */
    PROFILE_EDIT,

    /** 通知驳回 */
    NOTIFICATION_DISMISS,

    /** CGM 会话 / 发射器管理 */
    CGM_MANAGEMENT,

    /** 药筒 / 填充模式 */
    CARTRIDGE_MODE,

    /** 泵设置类动作（同步泵时钟、让泵响） */
    PUMP_SETTINGS,
}

/**
 * 能力表：`(型号, API 版本) → 允许的能力集合`。
 *
 * 设计约束（来自《现行架构与能力边界》§6）：
 * 1. **默认拒绝**：未登记的组合一律 false；
 * 2. **只收紧不放开**：调用方仍要先过 `canControlPump(model)` 总闸（`model == MOBI`）；
 * 3. **apiVersion 首版不参与判定**：参数保留，表内暂不按版本分档；等出现
 *    「同型号不同 API 版本行为不同」的实测反例再启用；
 * 4. 放开某一项只改本表，UI 与调用点零改动。
 *
 * 当前取值与 2026-11-03 真机结论一致：X2 除大剂量外全部关闭；Mobi 按现状放开。
 */
object CapabilityTable {

    /** Mobi 当前允许的能力（与迁移前 18 处 `canControlPump` 的实际行为一一对应）。 */
    private val MOBI_ALLOWED: Set<Capability> = setOf(
        Capability.SUSPEND_RESUME,
        Capability.SET_TEMP_RATE,
        Capability.SET_MODES,
        Capability.SET_SOUNDS,
        Capability.SET_CONTROL_IQ,
        Capability.SET_SAFETY_LIMITS,
        Capability.QUICK_BOLUS_SETTINGS,
        Capability.PROFILE_EDIT,
        Capability.NOTIFICATION_DISMISS,
        Capability.CGM_MANAGEMENT,
        Capability.CARTRIDGE_MODE,
        Capability.PUMP_SETTINGS,
    )

    /**
     * X2 当前允许的能力：**空集**。X2 的控制页全只读是产品决定，不是缺陷；
     * 大剂量走独立的安全流程（签名 + 确认），不经 `canControlPump` 总闸，
     * 因此也不登记在这张表里，避免出现「表说允许、总闸却拒绝」的矛盾。
     */
    private val TSLIM_X2_ALLOWED: Set<Capability> = emptySet()

    /**
     * @param model 型号未知时返回 false（宁可少显示可控项，也不要点了才发现不行）
     * @param apiVersion 连接初期可能为 null；首版不参与判定，保留给后续按版本分档
     */
    fun allows(
        model: KnownDeviceModel?,
        apiVersion: ApiVersion?,
        capability: Capability,
    ): Boolean {
        if (model == null) return false
        return when (model) {
            KnownDeviceModel.MOBI -> capability in MOBI_ALLOWED
            KnownDeviceModel.TSLIM_X2 -> capability in TSLIM_X2_ALLOWED
            else -> false
        }
    }
}
