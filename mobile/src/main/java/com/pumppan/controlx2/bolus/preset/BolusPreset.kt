package com.pumppan.controlx2.bolus.preset

import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource

/** 预设只保存这些字段：剂量 + 是否扩展 + 立即占比 + 时长；不存碳水/血糖/CIQ。 */
data class BolusPresetSpec(
    val totalMilliunits: Int,
    val extendedEnabled: Boolean,
    val nowPercent: Int,
    val extendedSeconds: Int,
) {
    companion object {
        /** 从草稿取值：普通形状立即段恒为 100%。 */
        fun from(state: BolusFlowState): BolusPresetSpec =
            if (state.shape == BolusShape.NORMAL) {
                BolusPresetSpec(state.totalMilliunits, false, 100, 0)
            } else {
                BolusPresetSpec(state.totalMilliunits, true, state.percent, state.extendedSeconds)
            }
    }
}

/** 一条预设（纯数据，不含 Room 类型，便于单测与 UI 使用）。 */
data class BolusPreset(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val totalMilliunits: Int,
    val extendedEnabled: Boolean,
    val nowPercent: Int,
    val extendedSeconds: Int,
) {
    fun spec(): BolusPresetSpec =
        BolusPresetSpec(totalMilliunits, extendedEnabled, nowPercent, extendedSeconds)

    /**
     * 预填成第 2 步的草稿：只填值，进入后可任意改，不回写预设。
     * 拆分仍走 [BolusFlowState] 的同一套量化逻辑，预设侧不做任何裁剪。
     */
    fun prefilledState(limits: BolusLimits = BolusLimits.Default): BolusFlowState {
        val base = BolusFlowState().selectSource(BolusSource.PRESET)
        if (!extendedEnabled) {
            return base.copy(nowMilliunits = totalMilliunits, shape = BolusShape.NORMAL)
        }
        return base
            .withShape(BolusShape.DUAL, limits)
            .withPercent(nowPercent, limits)
            .withTotal(totalMilliunits, limits)
            .withDurationSeconds(extendedSeconds)
    }
}
