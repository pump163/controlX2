package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.WizardConfig
import kotlin.math.roundToInt

/**
 * #61 三态校验：**校验挂在字段值上，不挂在按钮上**。
 *
 * - [WizardFieldStatus.EMPTY]：还没开始填。不等于 0，也不能当 0 直接算。
 * - [WizardFieldStatus.INVALID]：填了但非法（非数字 / 超上限）。
 * - [WizardFieldStatus.VALID]：可以计算。
 *
 * 按钮只读状态、不自己做校验；按「下一步」时若为 INVALID 则不推进，
 * 由页面把焦点落回字段并把提示加重。
 */
enum class WizardFieldStatus { EMPTY, INVALID, VALID }

/**
 * 非法原因只陈述事实（格式不对 / 超出范围），**不推测原因、不给修改建议**：
 * 程序只提示错误，判断权交回用户。
 */
enum class WizardFieldIssue {
    NOT_A_NUMBER,
    /** mg/dL 不接受小数点；带小数点的输入按此提示。 */
    DECIMAL_NOT_ALLOWED,
    BELOW_MIN,
    ABOVE_MAX,
    /** 剂量不是泵步进的整数倍（例：X2 步进 0.05 U）。 */
    NOT_ALIGNED,
}

data class WizardField(
    val status: WizardFieldStatus,
    /** 仅 VALID 有值；EMPTY / INVALID 一律 null，避免把「空」当成 0。 */
    val value: Int?,
    val issue: WizardFieldIssue? = null,
)

object BolusWizardValidation {

    fun carbs(text: String, config: WizardConfig): WizardField {
        if (text.isBlank()) return WizardField(WizardFieldStatus.EMPTY, null)
        val grams = text.trim().toIntOrNull()
            ?: return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_A_NUMBER)
        return when {
            grams < 0 -> WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.BELOW_MIN)
            grams > config.maxCarbsGrams ->
                WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.ABOVE_MAX)

            else -> WizardField(WizardFieldStatus.VALID, grams)
        }
    }

    /**
     * 建议剂量改写后的三态校验。合法 = 非空、数字、在 [BolusLimits] 区间内、且是泵步进的整数倍。
     * 只回报事实，不替用户取整。
     */
    fun dose(text: String, limits: BolusLimits): WizardField {
        if (text.isBlank()) return WizardField(WizardFieldStatus.EMPTY, null)
        val units = text.trim().toDoubleOrNull()
            ?: return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_A_NUMBER)
        val milliunits = (units * 1000.0).roundToInt()
        return when {
            milliunits < limits.minBolusMilliunits ->
                WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.BELOW_MIN)

            milliunits > limits.maxBolusMilliunits ->
                WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.ABOVE_MAX)

            milliunits % limits.bolusIncrementMilliunits != 0 ->
                WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_ALIGNED)

            else -> WizardField(WizardFieldStatus.VALID, milliunits)
        }
    }
}
