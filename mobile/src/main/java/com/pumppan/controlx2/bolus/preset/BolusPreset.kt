package com.pumppan.controlx2.bolus.preset

import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource

/**
 * 预设只保存**绝对值**：立即段 + 延后段（0 = 非延展）+ 延展时长（分钟）。
 *
 * 不存百分比：泵的 BLE 协议（`totalVolume` / `extendedVolume`）与历史记录
 * （`requestedNow` / `requestedLater`）都是绝对值；百分比乘总量会带步进舍入，
 * 同一比例在不同总量下得不到同一条处方，而预设本质是「重放某个具体处方」。
 */
data class BolusPresetSpec(
    val immediateMilliunits: Int,
    val laterMilliunits: Int,
    val durationMinutes: Int,
) {
    val extendedEnabled: Boolean get() = laterMilliunits > 0

    companion object {
        /** 从草稿取值：普通形状延后段恒为 0。 */
        fun from(state: BolusFlowState): BolusPresetSpec =
            if (state.shape == BolusShape.NORMAL) {
                BolusPresetSpec(state.nowMilliunits, 0, 0)
            } else {
                BolusPresetSpec(state.nowMilliunits, state.laterMilliunits, state.extendedSeconds / 60)
            }
    }
}

/** 短按选中的单选规则：点同一条取消选中，点另一条直接切换。 */
fun toggleSelection(currentId: Long?, tappedId: Long): Long? =
    if (currentId == tappedId) null else tappedId

/** 一条预设（纯数据，不含 Room 类型，便于单测与 UI 使用）。 */
data class BolusPreset(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val immediateMilliunits: Int,
    val laterMilliunits: Int,
    val durationMinutes: Int,
) {
    val totalMilliunits: Int get() = immediateMilliunits + laterMilliunits

    /** 延后段为 0 即非延展。 */
    val extendedEnabled: Boolean get() = laterMilliunits > 0

    val extendedSeconds: Int get() = durationMinutes * 60

    /** 派生的立即段占比：**只用于显示**，不进库、不参与校验。 */
    val percent: Int get() = BolusForm.percentOf(immediateMilliunits, totalMilliunits)

    fun spec(): BolusPresetSpec =
        BolusPresetSpec(immediateMilliunits, laterMilliunits, durationMinutes)

    /** 是否已设置数值：预设页「新建」刚建出的空条目为 false（0 U，不允许应用）。 */
    val isConfigured: Boolean get() = totalMilliunits > 0

    /**
     * 预填成第 2 步的草稿：绝对段原样还原（不做百分比再拆分，避免步进舍入）；
     * `percent` 只填派生值供界面显示。进入后可任意改，不回写预设。
     */
    fun prefilledState(limits: BolusLimits = BolusLimits.Default): BolusFlowState {
        val base = BolusFlowState().withSource(BolusSource.PRESET)
        if (!extendedEnabled) {
            return base.copy(nowMilliunits = immediateMilliunits, shape = BolusShape.NORMAL)
        }
        return base.copy(
            shape = BolusShape.DUAL,
            nowMilliunits = immediateMilliunits,
            laterMilliunits = laterMilliunits,
            extendedSeconds = extendedSeconds,
            percent = percent,
        )
    }

    /** 「应用」：预填草稿并直接进入第 3 步（确认页），不经过第 2 步。 */
    fun appliedState(limits: BolusLimits = BolusLimits.Default): BolusFlowState =
        prefilledState(limits).copy(step = BolusFlowStep.CONFIRM)
}
