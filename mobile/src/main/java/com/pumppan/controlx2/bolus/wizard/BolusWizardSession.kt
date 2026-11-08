package com.pumppan.controlx2.bolus.wizard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pumppan.controlx2.bolus.state.BolusCalcPayload
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.WizardConfig

enum class BolusWizardStep { INPUT, RESULT }

/**
 * 多步向导的唯一状态。**不在页面组合里**：切标签、离开标签页都原样保留。
 *
 * 保留是流程成立的前提，不是便利性：算出的胰岛素值需要人工判断，明显不对时
 * 用户必然要返回上一页核对是哪个输入填错了。只有三种情况清空：
 * 用户按 X（见 UI 的「清空」）、流程完成、成功发送（[BolusWizardRuntime.clear]）。
 *
 * 血糖内部只留一个 canonical 值（mg/dL 整数），以 [bgText] 作为可编辑的显示文本：
 * 换单位只重排文本，不重新解析上一轮的换算结果，所以来回切换不会走样。
 *
 * 结果页把「当时用的血糖 / 泵参数」一起冻结在 `result*` 字段里，
 * 返回输入页再前进也不会因为数据刷新而悄悄换掉结果依据。
 */
data class BolusWizardState(
    val step: BolusWizardStep = BolusWizardStep.INPUT,
    /** 原始文本而不是 Int：空 / 非法 / 合法三态必须保留，空不能变成 0。 */
    val carbsText: String = "",
    /** 血糖显示文本；canonical 值在 [bgMgdl]，文本只是排版结果。 */
    val bgText: String = "",
    /**
     * canonical 血糖值（mg/dL 整数）；`null` 表示当前文本为空或不可解析。
     * 换单位时从它重新排版，**不回读旧单位的文本**，所以来回切换不会累积误差。
     */
    val bgMgdl: Int? = null,
    val bgUnit: GlucoseUnit = GlucoseUnit.MG_DL,
    /** 用户是否动过血糖框。没动过才允许 CGM 预填，动过就绝不覆盖。 */
    val bgTouched: Boolean = false,
    /** 当前血糖是否来自 CGM 预填（只用于界面标注来源）。 */
    val bgFromCgm: Boolean = false,
    val bgCgmAtMillis: Long? = null,
    /** 上一次向泵取数的时刻，用于节流；不属于业务结果。 */
    val lastFetchAtMillis: Long = 0L,
    val result: WizardCalcResult? = null,
    val resultCarbsGrams: Int = 0,
    val resultBgMgdl: Int? = null,
    val resultBgFromCgm: Boolean = false,
    val resultBgAtMillis: Long? = null,
    val resultParams: WizardPumpParams? = null,
    val resultAtMillis: Long = 0L,
    /** 用户改写的「实际剂量」文本；空 = 沿用建议值。 */
    val editedTotalText: String = "",
    /** 改写后的 canonical 毫单位；空 / 非法为 null。 */
    val editedTotalMilliunits: Int? = null,
) {
    fun withCarbs(text: String): BolusWizardState = copy(carbsText = text)

    /** 用户编辑血糖：立刻解除与 CGM 预填的关联，之后不再被覆盖。 */
    fun withBg(text: String, config: WizardConfig): BolusWizardState = copy(
        bgText = text,
        bgMgdl = BolusWizardGlucose.parse(text, bgUnit, config).value,
        bgTouched = true,
        bgFromCgm = false,
    )

    /**
     * 切单位只重排 canonical 值，**不重新解析旧单位留下的文本**：
     * 100 mg/dL → 5.6 mmol/L → 100 mg/dL，而不是 101。
     * 文本不可解析（空 / 非法）时原样保留，不擅自改写用户输入。
     */
    fun withBgUnit(unit: GlucoseUnit, config: WizardConfig): BolusWizardState {
        if (unit == bgUnit) return this
        val canonical = bgMgdl
        return copy(
            bgUnit = unit,
            bgText = canonical?.let { BolusWizardGlucose.format(it, unit) } ?: bgText,
        )
    }

    /** CGM 有实时值且用户没动过血糖框时才预填；越界 / 过期由快照层判成不可用。 */
    fun prefillFromCgm(glucose: WizardGlucose): BolusWizardState {
        val mgdl = glucose.mgdl ?: return this
        if (bgTouched || !glucose.available) return this
        return copy(
            bgText = BolusWizardGlucose.format(mgdl, bgUnit),
            bgMgdl = mgdl,
            bgFromCgm = true,
            bgCgmAtMillis = glucose.atMillis,
        )
    }

    fun withFetchStamp(atMillis: Long): BolusWizardState = copy(lastFetchAtMillis = atMillis)

    fun showResult(
        result: WizardCalcResult,
        bgMgdl: Int?,
        bgFromCgm: Boolean,
        bgAtMillis: Long?,
        params: WizardPumpParams,
        atMillis: Long,
    ): BolusWizardState = copy(
        step = BolusWizardStep.RESULT,
        result = result,
        resultCarbsGrams = result.carbsGrams ?: 0,
        resultBgMgdl = bgMgdl,
        resultBgFromCgm = bgFromCgm,
        resultBgAtMillis = bgAtMillis,
        resultParams = params,
        resultAtMillis = atMillis,
        // 新一轮计算重置上一次的改动，避免把旧建议值上的改写带进新结果。
        editedTotalText = "",
        editedTotalMilliunits = null,
    )

    /** 用户改写建议剂量：只改「实际」，建议值原样保留，便于结果页 / 确认页做对照。 */
    fun withEditedTotal(text: String, limits: BolusLimits): BolusWizardState = copy(
        editedTotalText = text,
        editedTotalMilliunits = BolusWizardValidation.dose(text, limits).value,
    )

    /** 实际将采用的剂量：改写过且合法就用改写值，否则用建议值。 */
    val actualTotalMilliunits: Int
        get() = editedTotalMilliunits ?: result?.totalMilliunits ?: 0

    /** 是否改写过建议剂量（只用于是否显示「建议 / 实际」对照行）。 */
    val totalEdited: Boolean get() = editedTotalMilliunits != null

    /** 返回输入页：原样保留输入，满足「返回能核对原输入」；结果留着但不再展示。 */
    fun backToInput(): BolusWizardState = copy(step = BolusWizardStep.INPUT)

    /** 计算依据封成载荷随草稿走；是否写进 `-98` 由机型配置的 report 开关决定。 */
    val calcPayload: BolusCalcPayload?
        get() = result?.toPayload(resultCarbsGrams, resultBgMgdl)
}

/**
 * 进程内唯一一份向导输入。**容器只保留一份**：所有入口共享同一个实例，不按页面另建。
 */
object BolusWizardRuntime {

    var state by mutableStateOf(BolusWizardState())
        private set

    fun update(block: (BolusWizardState) -> BolusWizardState) {
        state = block(state)
    }

    /** 仅在按 X / 流程完成 / 成功发送时调用。切标签**不得**调用。 */
    fun clear() {
        state = BolusWizardState()
    }
}
