package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * 大剂量相关阈值的唯一来源。S0 只提供可注入的数据类与默认值；
 * assets/prefs 加载属另一篇设计（阈值配置外置-设计.md），此处不做。
 */
data class BolusLimits(
    val minBolusMilliunits: Int = MIN_BOLUS_MILLIUNITS,
    /**
     * 输注步进（milliunits），按泵型号分档（[incrementFor] / [forModel]）。
     * t:slim X2 = 0.05U（50，用户已核实 2026-11-05）；
     * Mobi 未经真机核实，暂按 0.05U（50）；若核实为 0.025U，只需改配置的 `models.MOBI` 一行；
     * 未知型号走默认 25；美敦力 0.025U 仅作参考对照。
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

        /**
         * 未知型号 / 外置配置 `default` 的步进：25。
         *
         * 为什么不取 50：25 网格是 50 的超集，识别不出型号时不会误拦现役机型的合法剂量；
         * 50 网格会在将来出现 0.025U 设备时直接拒掉合法值。识别到型号后再按 [incrementFor] 收紧。
         */
        const val BOLUS_INCREMENT_MILLIUNITS = 25

        /** t:slim X2 的 bolus 精度 0.05U（用户核实，2026-11-05）。 */
        const val TSLIM_X2_INCREMENT_MILLIUNITS = 50

        /**
         * Mobi 暂按 0.05U（50）：未经真机核实。
         * 若核实为 0.025U，只需改配置 `models.MOBI.bolusIncrementMilliunits`，不改代码。
         */
        const val MOBI_INCREMENT_MILLIUNITS = 50

        /** 型号 → 步进档位；未知型号（未解析出）取默认 25。 */
        fun incrementFor(model: KnownDeviceModel?): Int = when (model) {
            KnownDeviceModel.TSLIM_X2 -> TSLIM_X2_INCREMENT_MILLIUNITS
            KnownDeviceModel.MOBI -> MOBI_INCREMENT_MILLIUNITS
            else -> BOLUS_INCREMENT_MILLIUNITS
        }

        /** 按型号生成一份阈值（现只影响步进；外置配置落地后由这里合并文件值）。 */
        fun forModel(model: KnownDeviceModel?): BolusLimits =
            Default.copy(bolusIncrementMilliunits = incrementFor(model))

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
