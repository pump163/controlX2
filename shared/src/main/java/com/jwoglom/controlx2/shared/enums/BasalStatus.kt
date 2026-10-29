package com.jwoglom.controlx2.shared.enums
import com.jwoglom.controlx2.shared.R
import androidx.annotation.StringRes

enum class BasalStatus(@StringRes val strRes: Int) {
    ON(R.string.basal_on),
    PUMP_RESUMED(R.string.basal_on),
    ZERO(R.string.basal_zero),
    TEMP_RATE(R.string.basal_temp_rate),
    ZERO_TEMP_RATE(R.string.basal_zero_temp_rate),
    PUMP_SUSPENDED(R.string.basal_pump_suspended),
    BASALIQ_SUSPENDED(R.string.basal_iq_suspended),
    CONTROLIQ_INCREASED(R.string.basal_ciq_increased),
    CONTROLIQ_REDUCED(R.string.basal_ciq_reduced),
    UNKNOWN(R.string.basal_unknown)
}
