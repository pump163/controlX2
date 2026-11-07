package com.pumppan.controlx2.bolus.state

enum class BolusValidationError {
    NEGATIVE_AMOUNT,
    AMOUNT_BELOW_MINIMUM,
    AMOUNT_ABOVE_MAXIMUM,
    EXTENDED_TOTAL_BELOW_MINIMUM,
    IMMEDIATE_BELOW_MINIMUM,
    REMOTE_EXTENDED_NOT_SUPPORTED,
    INCREMENT_NOT_ALIGNED,
    LATER_REQUIRED,
    DURATION_OUT_OF_RANGE,
    SHAPE_INCONSISTENT,
}

data class BolusSplit(
    val nowMilliunits: Int,
    val laterMilliunits: Int,
)

object BolusForm {

    /**
     * 按百分比拆分总量。
     *
     * 取整规则（与预设 v1→v2 迁移同一条公式，`[com.pumppan.controlx2.bolus.preset.convertLegacyPreset]` 直接复用它）：
     * `later = round(total × 延展占比 / 100)`，**延后段** half-up 量化到 [increment]（泵的最小输注精度），
     * `now = total − later`。总量为 [increment] 的倍数时，两段同在网格上且总和守恒；
     * 否则由 [validate] 拦截（INCREMENT_NOT_ALIGNED）。
     *
     * 注意方向：[percent] 是**立即段**占比，延后段用 `100 − percent`。
     */
    fun splitByPercent(
        totalMilliunits: Int,
        percent: Int,
        increment: Int = BolusLimits.BOLUS_INCREMENT_MILLIUNITS,
    ): BolusSplit {
        require(totalMilliunits >= 0) { "totalMilliunits must be >= 0" }
        require(percent in 0..100) { "percent must be in 0..100" }
        val rawLater = (totalMilliunits.toLong() * (100 - percent) + 50) / 100
        val later = quantizeToIncrement(rawLater.toInt(), increment).coerceIn(0, totalMilliunits)
        return BolusSplit(totalMilliunits - later, later)
    }

    /** 就近量化到 [increment] 的整数倍（half-up）。 */
    fun quantizeToIncrement(
        milliunits: Int,
        increment: Int = BolusLimits.BOLUS_INCREMENT_MILLIUNITS,
    ): Int {
        require(increment > 0) { "increment must be > 0" }
        return ((milliunits + increment / 2) / increment) * increment
    }

    /**
     * 由绝对值反推立即段占比（half-up 到整数，**只用于显示**，不参与校验/存储）。
     * 与 [splitByPercent] 构成百分比 ↔ 绝对值的双向换算。
     */
    fun percentOf(nowMilliunits: Int, totalMilliunits: Int): Int {
        if (totalMilliunits <= 0) return 100
        return ((nowMilliunits.toLong() * 100 + totalMilliunits / 2) / totalMilliunits).toInt()
    }

    fun validate(
        draft: BolusDraft,
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
        ciqVersion: CiqVersion? = null,
    ): List<BolusValidationError> {
        val errors = mutableListOf<BolusValidationError>()

        if (draft.nowMilliunits < 0 || draft.laterMilliunits < 0 || draft.extendedSeconds < 0) {
            errors += BolusValidationError.NEGATIVE_AMOUNT
            return errors
        }

        if (draft.nowMilliunits % limits.bolusIncrementMilliunits != 0 ||
            draft.laterMilliunits % limits.bolusIncrementMilliunits != 0
        ) {
            errors += BolusValidationError.INCREMENT_NOT_ALIGNED
        }

        if (draft.totalMilliunits < limits.minBolusMilliunits) {
            errors += BolusValidationError.AMOUNT_BELOW_MINIMUM
        }
        if (draft.totalMilliunits > limits.maxBolusMilliunits) {
            errors += BolusValidationError.AMOUNT_ABOVE_MAXIMUM
        }

        when (draft.shape) {
            BolusShape.NORMAL -> {
                if (draft.laterMilliunits != 0 || draft.extendedSeconds != 0) {
                    errors += BolusValidationError.SHAPE_INCONSISTENT
                }
            }

            BolusShape.SQUARE, BolusShape.DUAL -> {
                // X2 固件忽略 -98 的延展字段（真机 6851/6852 定论），能力由配置声明；
                // 不支持时直接报错并跳过后续延展校验，避免叠加噪音错误。
                if (!limits.canStartRemoteExtended()) {
                    errors += BolusValidationError.REMOTE_EXTENDED_NOT_SUPPORTED
                    return errors
                }
                // 延展段下限只是**客户端**兜底（pumpx2 MIN_EXTENDED_BOLUS_MILLIUNITS=400），
                // 不是泵的下限：Mobi 手册无延展段最小剂量，501 的 0.25U 也被接受。
                // 与 wire 的 totalVolume 分叉：wire totalVolume = 立即段，extendedVolume = 延后段。
                if (draft.laterMilliunits < limits.clientMinExtendedCheckMilliunits) {
                    errors += BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM
                }
                // 立即段下限按型号分叉：X2=50 mIU（total=0 必被 statusTypeId=1 拒），Mobi=0（纯方波）。
                if (draft.nowMilliunits < limits.minImmediateMilliunits) {
                    errors += BolusValidationError.IMMEDIATE_BELOW_MINIMUM
                }
                if (draft.laterMilliunits <= 0) {
                    errors += BolusValidationError.LATER_REQUIRED
                }
                val maxSeconds = limits.maxExtendedSeconds(ciqEnabled, ciqVersion)
                if (draft.extendedSeconds !in limits.minExtendedSeconds..maxSeconds) {
                    errors += BolusValidationError.DURATION_OUT_OF_RANGE
                }
            }
        }

        return errors
    }

    fun isValid(
        draft: BolusDraft,
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
        ciqVersion: CiqVersion? = null,
    ): Boolean = validate(draft, ciqEnabled, limits, ciqVersion).isEmpty()

    /** 立即段非零但低于该型号下限：X2 为 <0.05U；Mobi 下限 0，恒为 false。 */
    fun isImmediatePartUnverified(
        draft: BolusDraft,
        limits: BolusLimits = BolusLimits.Default,
    ): Boolean = draft.nowMilliunits in 1 until limits.minImmediateMilliunits

    fun maxExtendedSeconds(
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
        ciqVersion: CiqVersion? = null,
    ): Int = limits.maxExtendedSeconds(ciqEnabled, ciqVersion)
}
