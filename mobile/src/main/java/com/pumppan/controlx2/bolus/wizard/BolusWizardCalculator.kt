package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.BolusCalcPayload
import com.pumppan.controlx2.bolus.state.WizardConfig
import kotlin.math.max
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * 一次向导计算的结果（毫单位整型，UI 只做一位小数显示）。
 *
 * 这些数值就是结果页公式的全部数据源：**展示层不得再算一遍**，
 * 只能把这里给出的数排版成「餐食 / 校正 / 已扣活性 / 建议」四段。
 *
 * - [correctionMilliunits] 是**扣过 IOB 之后**实际参与计算的校正量，可能为负（低血糖分支）。
 * - [correctionGrossMilliunits] 是扣 IOB 之前的校正量，公式里「(BG − 目标) ÷ ISF」那一段。
 * - [iobDeductedMilliunits] 是本次实际扣掉的量（等于 gross − net），恒 ≥ 0。
 * - [iobMilliunits] 是上游原始 IOB，本机不加工；不可用时为 0。
 * - [iobUnavailable] 给出不可用的原因（A 查不到 / B 输注中），界面据此分开写文案。
 */
data class WizardCalcResult(
    val totalMilliunits: Int,
    val foodMilliunits: Int,
    val correctionMilliunits: Int,
    val iobMilliunits: Int,
    val belowLow: Boolean,
    /** 总量算出负值被截到 0（只可能在低血糖 + 大 IOB 时出现）。 */
    val clampedToZero: Boolean,
    /** 扣 IOB 之前的校正量（可为负）；公式里除法那一段的结果。 */
    val correctionGrossMilliunits: Int = correctionMilliunits,
    /** 本次实际扣掉的 IOB 量（≥ 0）。 */
    val iobDeductedMilliunits: Int = 0,
    /** IOB 本次不可用：不显示数值、不参与运算。 */
    val iobStale: Boolean = false,
    /** IOB 不可用的具体原因；[iobStale] 为 false 时恒为 null。 */
    val iobUnavailable: WizardIobUnavailableReason? = null,
    /** IOB 是否参与本次扣减（只有血糖高于目标或低于低血糖阈值才会扣）。 */
    val iobApplied: Boolean = false,
    /** 本次 IOB 是「本次查询」还是「缓存」；只用于界面如实标注。 */
    val iobSource: WizardIobSource = WizardIobSource.CACHE,
    /** 输入碳水克数；`null` = 未填（空不等于 0）。 */
    val carbsGrams: Int? = null,
    /** 参与计算的血糖（整数 mg/dL）；`null` = 未填。 */
    val glucoseMgdl: Int? = null,
    /** 血糖落在 [lowBg, 目标] 之间：本笔不做校正（不是「校正 0」的算式）。 */
    val correctionInRange: Boolean = false,
    /**
     * IOB 大于校正段、没有地方可扣的超出量（≥ 0）。只用于提示，**不改变剂量**：
     * 上游只把 IOB 并入血糖校正段，碳水段不参与扣减。
     */
    val iobExcessMilliunits: Int = 0,
)

/**
 * 计算器的规范化入参。**入口复查**（不是输入框那层）：血糖必须是整数 mg/dL，
 * 碳水按整数克；剂量输出本来就在毫单位整数上。
 */
data class WizardCalcInput(
    val carbsGrams: Int?,
    val glucoseMgdl: Int?,
    /** 上游换算漏出小数被四舍五入；只用于程序诊断。 */
    val glucoseWasFractional: Boolean,
)

/**
 * 大剂量计算器的纯计算部分，规则按 Tandem 口径：
 *
 * - 食物量 = 碳水 / ICR。
 * - 血糖高于目标：校正 = (BG − 目标) / ISF，**再扣 IOB**，扣完小于 0 按 0。
 * - 血糖在目标与低血糖阈值之间：不校正、不扣 IOB。
 * - 血糖低于阈值：校正为负，且 IOB 继续参与扣减（总量可能低于食物量，最低 0）。
 * - 读不到实时血糖：只按食物量计算（「空」不等于 0）。
 *
 * IOB 的扣减位置不是本机自定：pumpx2 `BolusCalculator` 把 `addedFromBG` 与 `addedFromIOB`
 * 合并成一段，合并结果为负时记 `NO_POSITIVE_BG_CORRECTION`（校正段记 0），碳水段不参与。
 * 本实现照此执行，并把「IOB 超出校正段」的余额留在 [WizardCalcResult.iobExcessMilliunits] 供界面提示。
 *
 * IOB 一律取上游原始值，本机**不重算、不按时间外推、不扣基础率**。时效 / 输注否决
 * 由快照层给出（[WizardIob] 为 `null` 即本次不可用）：不可用就当它不存在（不扣减），
 * 并由 [WizardCalcResult.iobUnavailable] 区分「查不到」与「输注中」两种原因；
 * 本次查询的值按新鲜对待。
 */
object BolusWizardCalculator {

    /**
     * 入口前的统一规范化。发现血糖带小数（说明上游换算漏了）时四舍五入，**不抛异常、不中断**，
     * 只把「发生过」这一事实留给诊断日志。
     */
    fun normalizeInput(carbsGrams: Double?, glucoseMgdl: Double?, config: WizardConfig): WizardCalcInput {
        val carbs = carbsGrams?.let { clampCarbs(it.roundToInt(), config) }
        val rawBg = glucoseMgdl?.takeIf { it > 0 }
        val bg = rawBg?.roundToInt()
        val fractional = rawBg != null && bg != null && rawBg != bg.toDouble()
        return WizardCalcInput(carbsGrams = carbs, glucoseMgdl = bg, glucoseWasFractional = fractional)
    }

    fun calculate(
        carbsGrams: Double?,
        glucoseMgdl: Double?,
        params: WizardPumpParams,
        config: WizardConfig,
        /** 快照层已判过时效 / 输注否决的 IOB；`null` 视为本次不可用。 */
        iob: WizardIob? = null,
        /** IOB 本次不可用的原因（A / B）；由快照层给出，只用于界面文案。 */
        iobUnavailable: WizardIobUnavailableReason? = null,
    ): WizardCalcResult {
        val input = normalizeInput(carbsGrams, glucoseMgdl, config)
        if (input.glucoseWasFractional) {
            // 诊断记录只写「换算出小数」这一事实，不写任何血糖数值，符合不留医疗记录的原则。
            Timber.tag("BolusWizard")
                .w("glucose input arrived fractional; rounded before calculating")
        }
        val iobStale = iob == null
        val iobU = if (iobStale) 0.0 else iob!!.milliunits / 1000.0
        val glucose = input.glucoseMgdl

        val foodU = (input.carbsGrams ?: 0).toDouble() / params.carbRatioGramsPerUnit
        val grossCorrectionU = grossCorrectionUnits(glucose, params)
        val correctionU = correctionUnits(glucose, params, config, iobU)
        val iobApplied = glucose != null && (glucose > params.targetBgMgdl || glucose < config.lowBgMgdl)
        val iobExcessU = if (glucose != null && glucose > params.targetBgMgdl) {
            max(0.0, iobU - max(0.0, grossCorrectionU))
        } else {
            0.0
        }
        val rawTotalU = foodU + correctionU

        return WizardCalcResult(
            totalMilliunits = milliunits(max(0.0, rawTotalU)),
            foodMilliunits = milliunits(foodU),
            correctionMilliunits = milliunits(correctionU),
            iobMilliunits = iob?.milliunits ?: 0,
            belowLow = glucose != null && glucose < config.lowBgMgdl,
            clampedToZero = rawTotalU < 0.0,
            correctionGrossMilliunits = milliunits(grossCorrectionU),
            iobDeductedMilliunits = if (iobApplied) {
                max(0, milliunits(grossCorrectionU) - milliunits(correctionU))
            } else {
                0
            },
            iobStale = iobStale,
            iobUnavailable = if (iobStale) iobUnavailable ?: WizardIobUnavailableReason.EXPIRED_CACHE else null,
            iobApplied = iobApplied,
            iobSource = iob?.source ?: WizardIobSource.CACHE,
            carbsGrams = input.carbsGrams,
            glucoseMgdl = glucose,
            correctionInRange = glucose != null &&
                glucose >= config.lowBgMgdl &&
                glucose <= params.targetBgMgdl,
            iobExcessMilliunits = milliunits(iobExcessU),
        )
    }

    private fun clampCarbs(grams: Int, config: WizardConfig): Int = grams.coerceIn(0, config.maxCarbsGrams)

    /** 公式里「(BG − 目标) ÷ ISF」的结果；血糖缺失时为 0。 */
    private fun grossCorrectionUnits(glucoseMgdl: Int?, params: WizardPumpParams): Double {
        if (glucoseMgdl == null) return 0.0
        return (glucoseMgdl - params.targetBgMgdl).toDouble() / params.isfMgdlPerUnit
    }

    private fun correctionUnits(
        glucoseMgdl: Int?,
        params: WizardPumpParams,
        config: WizardConfig,
        iobU: Double,
    ): Double {
        if (glucoseMgdl == null) return 0.0
        val raw = (glucoseMgdl - params.targetBgMgdl).toDouble() / params.isfMgdlPerUnit
        return when {
            glucoseMgdl > params.targetBgMgdl -> max(0.0, raw - iobU)
            glucoseMgdl >= config.lowBgMgdl -> 0.0
            else -> raw - iobU
        }
    }

    private fun milliunits(units: Double): Int = (units * 1000.0).roundToInt()
}

/** 把本次计算封成随草稿走的载荷；是否真的写进 `-98` 由机型配置决定。 */
fun WizardCalcResult.toPayload(
    carbsGrams: Int,
    glucoseMgdl: Int?,
): BolusCalcPayload = BolusCalcPayload(
    foodMilliunits = foodMilliunits,
    correctionMilliunits = correctionMilliunits,
    carbsGrams = carbsGrams,
    bgMgdl = glucoseMgdl,
    // 过期 IOB 不参与运算，也不进载荷；否则会把一个本机不再使用的数值写进请求。
    iobMilliunits = if (iobStale) 0 else iobMilliunits,
)
