package com.pumppan.controlx2.bolus.state

/**
 * 大剂量相关阈值的唯一来源。S0 只提供可注入的数据类与默认值；
 * assets/prefs 加载属另一篇设计（阈值配置外置-设计.md），此处不做。
 */
data class BolusLimits(
    val minBolusMilliunits: Int = MIN_BOLUS_MILLIUNITS,
    /**
     * 输注步进（milliunits）。默认 25；现役泵常见 25 / 50 两档（美敦力 0.025U 已确认，
     * X2 / Mobi 实际值待用户核实）。所有剂量须为其整数倍。
     */
    val bolusIncrementMilliunits: Int = BOLUS_INCREMENT_MILLIUNITS,
    val maxBolusMilliunits: Int = MAX_BOLUS_MILLIUNITS,
    /** 扩展大剂量总量下限；已真机确认 0.4U，与 pumpx2 `MIN_EXTENDED_BOLUS_MILLIUNITS` 同值。 */
    val minExtendedTotalMilliunits: Int = MIN_EXTENDED_TOTAL_MILLIUNITS,
    val maxExtendedSecondsNonCiq: Int = MAX_EXTENDED_SECONDS_NON_CIQ,
    val maxExtendedSecondsCiq: Int = MAX_EXTENDED_SECONDS_CIQ,
    val staleActiveWindowSeconds: Long = STALE_ACTIVE_WINDOW_SECONDS,
    val bolusResponseTimeoutSeconds: Long = BOLUS_RESPONSE_TIMEOUT_SECONDS,
    val cancelMaxAttempts: Int = CANCEL_MAX_ATTEMPTS,
    val cancelAttemptWindowSeconds: Long = CANCEL_ATTEMPT_WINDOW_SECONDS,
    /**
     * 扩展大剂量「立即占比」的预设快捷项（百分比整数）。
     * 硬默认兜底；外置文件 schema 见 docs/feature/阈值配置外置-设计.md。
     * 0（0:100，立即 0）已真机确认可被泵接受（2026-11-05 泵本体截图）。
     */
    val extendedPercentPresets: List<Int> = EXTENDED_PERCENT_PRESETS,
) {
    val staleActiveWindowMillis: Long get() = staleActiveWindowSeconds * 1000L

    val bolusResponseTimeoutMillis: Long get() = bolusResponseTimeoutSeconds * 1000L

    val cancelAttemptWindowMillis: Long get() = cancelAttemptWindowSeconds * 1000L

    /** CIQ 开启或状态未知时取 2h（fail-closed），明确关闭时才取 8h。 */
    fun maxExtendedSeconds(ciqEnabled: Boolean?): Int =
        if (ciqEnabled == false) maxExtendedSecondsNonCiq else maxExtendedSecondsCiq

    companion object {
        const val MIN_BOLUS_MILLIUNITS = 50
        const val BOLUS_INCREMENT_MILLIUNITS = 25
        const val MAX_BOLUS_MILLIUNITS = 25_000
        const val MIN_EXTENDED_TOTAL_MILLIUNITS = 400
        const val MAX_EXTENDED_SECONDS_NON_CIQ = 28_800
        const val MAX_EXTENDED_SECONDS_CIQ = 7_200
        const val STALE_ACTIVE_WINDOW_SECONDS = 600L
        const val BOLUS_RESPONSE_TIMEOUT_SECONDS = 30L
        const val CANCEL_MAX_ATTEMPTS = 3
        const val CANCEL_ATTEMPT_WINDOW_SECONDS = 15L

        /** 立即占比预设：0:100、50:50、75:25（0 已真机确认可接受）。 */
        val EXTENDED_PERCENT_PRESETS = listOf(0, 50, 75)

        val Default = BolusLimits()
    }
}
