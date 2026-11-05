package com.pumppan.controlx2.bolus.state

enum class BolusValidationError {
    NEGATIVE_AMOUNT,
    AMOUNT_BELOW_MINIMUM,
    AMOUNT_ABOVE_MAXIMUM,
    NOW_BELOW_MINIMUM,
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
     * 按百分比拆分总量：half-up 取整，余数全部归延后段（now + later 恒等于 total）。
     */
    fun splitByPercent(totalMilliunits: Int, percent: Int): BolusSplit {
        require(totalMilliunits >= 0) { "totalMilliunits must be >= 0" }
        require(percent in 0..100) { "percent must be in 0..100" }
        val now = (totalMilliunits * percent + 50) / 100
        return BolusSplit(now, totalMilliunits - now)
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
                if (draft.nowMilliunits < limits.extendedNowMinMilliunits) {
                    errors += BolusValidationError.NOW_BELOW_MINIMUM
                }
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

    fun maxExtendedSeconds(
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
    ): Int = limits.maxExtendedSeconds(ciqEnabled)
}
