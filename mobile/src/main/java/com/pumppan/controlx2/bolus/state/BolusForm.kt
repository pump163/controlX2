package com.pumppan.controlx2.bolus.state

enum class BolusValidationError {
    NEGATIVE_AMOUNT,
    AMOUNT_BELOW_MINIMUM,
    AMOUNT_ABOVE_MAXIMUM,
    EXTENDED_TOTAL_BELOW_MINIMUM,
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
     * 取整规则：立即段按 half-up 就近量化到 [increment]（泵的最小输注精度，默认 25 milliunits），
     * 延后段 = 总量 − 立即段。总量为 [increment] 的倍数时，两段同在网格上且总和守恒；
     * 否则由 [validate] 拦截（INCREMENT_NOT_ALIGNED）。
     */
    fun splitByPercent(
        totalMilliunits: Int,
        percent: Int,
        increment: Int = BolusLimits.BOLUS_INCREMENT_MILLIUNITS,
    ): BolusSplit {
        require(totalMilliunits >= 0) { "totalMilliunits must be >= 0" }
        require(percent in 0..100) { "percent must be in 0..100" }
        val rawNow = (totalMilliunits * percent + 50) / 100
        val now = quantizeToIncrement(rawNow, increment)
        return BolusSplit(now, totalMilliunits - now)
    }

    /** 就近量化到 [increment] 的整数倍（half-up）。 */
    fun quantizeToIncrement(
        milliunits: Int,
        increment: Int = BolusLimits.BOLUS_INCREMENT_MILLIUNITS,
    ): Int {
        require(increment > 0) { "increment must be > 0" }
        return ((milliunits + increment / 2) / increment) * increment
    }

    fun validate(
        draft: BolusDraft,
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
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
                // 已真机确认：扩展总量须 ≥0.4U（与 pumpx2 的 MIN_EXTENDED_BOLUS_MILLIUNITS=400 一致）。
                if (draft.totalMilliunits < limits.minExtendedTotalMilliunits) {
                    errors += BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM
                }
                // 立即段 0（0:100）已真机确认可接受；非零但 <0.05U 未验，不硬拦，改在确认页提示。
                if (draft.laterMilliunits <= 0) {
                    errors += BolusValidationError.LATER_REQUIRED
                }
                val maxSeconds = limits.maxExtendedSeconds(ciqEnabled)
                if (draft.extendedSeconds !in 1..maxSeconds) {
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
    ): Boolean = validate(draft, ciqEnabled, limits).isEmpty()

    /** 立即段非零但 <0.05U：未验，允许提交但确认页提示泵可能拒绝。 */
    fun isImmediatePartUnverified(
        draft: BolusDraft,
        limits: BolusLimits = BolusLimits.Default,
    ): Boolean = draft.nowMilliunits in 1 until limits.minBolusMilliunits

    fun maxExtendedSeconds(
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
    ): Int = limits.maxExtendedSeconds(ciqEnabled)
}
