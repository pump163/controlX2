package com.pumppan.controlx2.bolus.state

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope

/** 四标签，枚举顺序即界面从左到右的顺序（大剂量 / 向导 / 定时 / 预约）。 */
enum class BolusTab { BOLUS, WIZARD, SCHEDULED, PRESETS }

/**
 * 返回落点：来源标签未知（首次进入、进程重建恢复失败）时固定回「大剂量」。
 */
fun returnTab(origin: BolusTab?): BolusTab = origin ?: BolusTab.BOLUS

/**
 * 三标签 + 全局唯一草稿的纯状态（可单测）。
 *
 * **不变式：任意时刻最多一份草稿。** 草稿只存在于 [draft] 一个字段；标签只记 [origin]
 * 这一个标量，不持有第二份表单数据。所有进入流程的路径（动作表入口、预设「应用」、
 * 将来的向导）都收敛到同一份草稿槽位，从源头上杜绝「按标签各持一份、累加到第二页」。
 */
data class BolusTabSession(
    val tab: BolusTab = BolusTab.BOLUS,
    val origin: BolusTab? = null,
    val draft: BolusFlowState? = null,
) {
    val flowOpen: Boolean get() = draft != null

    /** 切标签：保留草稿与来源，各标签状态不因切换而丢。 */
    fun select(tab: BolusTab): BolusTabSession = copy(tab = tab)

    /**
     * 从 [origin] 标签进入流程，落回「大剂量」标签。
     * 已有草稿时**复用同一份**（回到原相位），只有没有草稿时才用 [seed] 新建。
     */
    fun startFlow(origin: BolusTab, seed: BolusFlowState = BolusFlowState()): BolusTabSession =
        copy(tab = BolusTab.BOLUS, origin = origin, draft = draft ?: seed)

    /**
     * 跨标签交接（预设「应用」）：沿用唯一的草稿槽位，填入预填值并直达确认页，
     * 同时把来源标签记成 [origin]（默认「预设」），退出流程时据此切回。
     */
    fun applyPreset(state: BolusFlowState, origin: BolusTab = BolusTab.PRESETS): BolusTabSession =
        copy(tab = BolusTab.BOLUS, origin = origin, draft = state)

    /**
     * 向导交接：同样是「唯一草稿」槽位，来源记「向导」，退出流程后回到向导标签
     * （那里原样保留着输入，便于核对算错的输入）。与预设交接的唯一差别是来源标签。
     */
    fun applyWizard(state: BolusFlowState): BolusTabSession =
        applyPreset(state, origin = BolusTab.WIZARD)

    fun updateDraft(state: BolusFlowState): BolusTabSession = copy(draft = state)

    /** 相位不再是 DRAFT（开始输注 / 结局）：收回草稿页并销毁草稿；输注状态由状态机照常展示。 */
    fun clearDraft(): BolusTabSession = copy(draft = null, origin = null)

    /** 退出流程：草稿退出即销毁，标签回到来源；来源未知 → 大剂量。 */
    fun exitFlow(): BolusTabSession = copy(tab = returnTab(origin), origin = null, draft = null)

    companion object {
        /** 进程重建后原样恢复标签、来源与（唯一一份）草稿。 */
        val Saver: Saver<BolusTabSession, List<Any>> = Saver(
            save = { state ->
                val scope: SaverScope = this
                listOf(
                    state.tab.name,
                    state.origin?.name.orEmpty(),
                    state.draft?.let { draft -> with(BolusFlowState.Saver) { scope.save(draft) } }
                        ?: emptyList<Any>(),
                )
            },
            restore = { saved ->
                val tab = BolusTab.values().firstOrNull { it.name == saved.getOrNull(0) } ?: BolusTab.BOLUS
                val origin = BolusTab.values().firstOrNull { it.name == saved.getOrNull(1) }
                // 恢复失败时 origin 为 null，按「未知来源」处理（退出流程回「大剂量」）。
                @Suppress("UNCHECKED_CAST")
                val rawDraft = saved.getOrNull(2) as? List<Any>
                val draft = rawDraft
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { BolusFlowState.Saver.restore(it) }
                BolusTabSession(tab = tab, origin = origin, draft = draft)
            },
        )
    }
}
