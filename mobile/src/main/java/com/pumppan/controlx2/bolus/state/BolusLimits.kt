package com.pumppan.controlx2.bolus.state

/**
 * 大剂量相关阈值的唯一来源。S0 只提供可注入的数据类与默认值；
 * assets/prefs 加载属另一篇设计（阈值配置外置-设计.md），此处不做。
 */
data class BolusLimits(
    val minBolusMilliunits: Int = MIN_BOLUS_MILLIUNITS,
    val maxBolusMilliunits: Int = MAX_BOLUS_MILLIUNITS,
    val extendedNowMinMilliunits: Int = EXTENDED_NOW_MIN_MILLIUNITS,
    val maxExtendedSecondsNonCiq: Int = MAX_EXTENDED_SECONDS_NON_CIQ,
    val maxExtendedSecondsCiq: Int = MAX_EXTENDED_SECONDS_CIQ,
    val staleActiveWindowSeconds: Long = STALE_ACTIVE_WINDOW_SECONDS,
    val bolusResponseTimeoutSeconds: Long = BOLUS_RESPONSE_TIMEOUT_SECONDS,
    val cancelMaxAttempts: Int = CANCEL_MAX_ATTEMPTS,
    val cancelAttemptWindowSeconds: Long = CANCEL_ATTEMPT_WINDOW_SECONDS,
) {
    val staleActiveWindowMillis: Long get() = staleActiveWindowSeconds * 1000L

    val bolusResponseTimeoutMillis: Long get() = bolusResponseTimeoutSeconds * 1000L

    val cancelAttemptWindowMillis: Long get() = cancelAttemptWindowSeconds * 1000L

    /** CIQ 开启或状态未知时取 2h（fail-closed），明确关闭时才取 8h。 */
    fun maxExtendedSeconds(ciqEnabled: Boolean?): Int =
        if (ciqEnabled == false) maxExtendedSecondsNonCiq else maxExtendedSecondsCiq

    companion object {
        const val MIN_BOLUS_MILLIUNITS = 50
        const val MAX_BOLUS_MILLIUNITS = 25_000
        const val EXTENDED_NOW_MIN_MILLIUNITS = 50
        const val MAX_EXTENDED_SECONDS_NON_CIQ = 28_800
        const val MAX_EXTENDED_SECONDS_CIQ = 7_200
        const val STALE_ACTIVE_WINDOW_SECONDS = 600L
        const val BOLUS_RESPONSE_TIMEOUT_SECONDS = 30L
        const val CANCEL_MAX_ATTEMPTS = 3
        const val CANCEL_ATTEMPT_WINDOW_SECONDS = 15L

        val Default = BolusLimits()
    }
}
