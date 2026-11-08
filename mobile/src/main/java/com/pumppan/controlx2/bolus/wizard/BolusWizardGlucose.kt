package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.WizardConfig
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 血糖的显示单位。**内部只存一个值**：mg/dL 整数。mmol 只是显示层的换算，
 * 来回切换不会累积误差（每次都由同一个 mg/dL 值重新排版）。
 */
enum class GlucoseUnit { MG_DL, MMOL_L }

/**
 * 血糖输入 / 预填的换算与三态校验。区间上下限全部来自 [WizardConfig]。
 *
 * 该值只用于本机计算：**不进 `-98`、不落盘、不进任何日志**（不留可追溯的医疗记录）。
 */
object BolusWizardGlucose {

    /** 换算按 1 mmol/L ≈ 18 mg/dL。 */
    const val MGDL_PER_MMOL = 18.0

    /** mg/dL → 整数文本（禁小数点）；mmol/L → 强制一位小数（13 也显示 13.0）。 */
    fun format(mgdl: Int, unit: GlucoseUnit): String = when (unit) {
        GlucoseUnit.MG_DL -> mgdl.toString()
        GlucoseUnit.MMOL_L -> String.format(Locale.US, "%.1f", mgdl / MGDL_PER_MMOL)
    }

    fun parse(text: String, unit: GlucoseUnit, config: WizardConfig): WizardField {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return WizardField(WizardFieldStatus.EMPTY, null)
        return when (unit) {
            GlucoseUnit.MG_DL -> parseMgdl(trimmed, config)
            GlucoseUnit.MMOL_L -> parseMmol(trimmed, config)
        }
    }

    private fun parseMgdl(text: String, config: WizardConfig): WizardField {
        if (text.contains('.')) {
            return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.DECIMAL_NOT_ALLOWED)
        }
        if (!text.all(Char::isDigit)) {
            return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_A_NUMBER)
        }
        val value = text.toIntOrNull()
            ?: return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_A_NUMBER)
        return when {
            value < config.fingerstick.mgdlMin -> WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.BELOW_MIN)
            value > config.fingerstick.mgdlMax -> WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.ABOVE_MAX)
            else -> WizardField(WizardFieldStatus.VALID, value)
        }
    }

    private fun parseMmol(text: String, config: WizardConfig): WizardField {
        val value = text.toDoubleOrNull()
            ?: return WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.NOT_A_NUMBER)
        return when {
            value < config.fingerstick.mmolMin -> WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.BELOW_MIN)
            value > config.fingerstick.mmolMax -> WizardField(WizardFieldStatus.INVALID, null, WizardFieldIssue.ABOVE_MAX)
            else -> WizardField(WizardFieldStatus.VALID, (value * MGDL_PER_MMOL).roundToInt())
        }
    }
}
