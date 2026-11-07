package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * Control-IQ 版本信号（来自 `ControlIQInfoV1Response` / `ControlIQInfoV2Response`）。
 * 取不到时由调用方传 null，时长上限按 2h 保守处理，不猜 8h。
 */
enum class CiqVersion { V1, V2 }

/**
 * **唯一**的延展发起能力校验：该机型能否从 App 远程发起延展/双波。
 *
 * 输入页开关、预设「应用」、确认页兜底、发送前校验都走这里，不在各调用点重复写型号分支。
 */
fun BolusLimits.canStartRemoteExtended(): Boolean = remoteExtendedBolusStart

/**
 * 大剂量相关阈值的统一视图。数值实际来自 `assets/pump-capabilities.json`
 * （经 [PumpCapabilities] 加载），本文件的常量只作兜底与默认值。
 */
data class BolusLimits(
    val minBolusMilliunits: Int = MIN_BOLUS_MILLIUNITS,
    /**
     * 延展/双波「立即段」下限：X2 为 50 mIU（`total=0` 必被 `statusTypeId=1` 拒），
     * Mobi 允许 0（纯方波）。普通大剂量不走此字段。
     */
    val minImmediateMilliunits: Int = MIN_IMMEDIATE_MILLIUNITS,
    /**
     * 输注步进（milliunits），按泵型号分档（[incrementFor] / [forModel]）。
     * t:slim X2 = 0.05U（50，用户已核实 2026-11-05）；
     * Mobi 暂按 0.05U（50）；未知型号走默认 25；均以配置为准。
     */
    val bolusIncrementMilliunits: Int = BOLUS_INCREMENT_MILLIUNITS,
    val maxBolusMilliunits: Int = MAX_BOLUS_MILLIUNITS,
    /**
     * 延展段的**客户端**兜底校验下限（毫单位），与 pumpx2 `MIN_EXTENDED_BOLUS_MILLIUNITS` 同值。
     * 注意：这不是泵的下限——Mobi 手册没有延展段最小剂量（501 的 0.25U 也被接受），
     * 唯一下限是整次大剂量 0.05U。仅作本地提示，不做协议裁决。
     */
    val clientMinExtendedCheckMilliunits: Int = CLIENT_MIN_EXTENDED_CHECK_MILLIUNITS,
    /** 延展时长下限（秒）。UI 以分钟展示：15–480 分钟、1 分钟步进。 */
    val minExtendedSeconds: Int = MIN_EXTENDED_SECONDS,
    val maxExtendedSecondsNonCiq: Int = MAX_EXTENDED_SECONDS_NON_CIQ,
    val maxExtendedSecondsCiq: Int = MAX_EXTENDED_SECONDS_CIQ,
    /**
     * 是否允许从 App 远程发起延展/双波。X2 = false（`-98` 延展字段被固件忽略，
     * 真机 6851/6852 已定论）；Mobi = true（库内 `Mobi_Extended` 语义，未真机验证）。
     * 校验、入口显隐都读这里，不写型号分支。
     */
    val remoteExtendedBolusStart: Boolean = true,
    /**
     * 延展发起能力是否已真机验证（配置 `commands.verified`）。Mobi 为 false：
     * 确认页显示实验提示，发送时写本地取证日志。
     */
    val remoteExtendedBolusVerified: Boolean = true,
    /**
     * 「此处做不了」的提示文案（可空）：X2 =「请到泵本体操作」，Mobi = null（无屏，操作就在本应用内）。
     * 为 null 时 UI **不渲染**提示行。
     */
    val unsupportedHint: String? = null,
    /** 型号显示名（来自 `pump-capabilities.json` 的 `displayName`）；null 时 UI 回落「当前型号」。 */
    val modelDisplayName: String? = null,
    /** 无输注时会话痕迹的过期窗口。 */
    val staleNoBolusMillis: Long = STALE_NO_BOLUS_MILLIS,
    /** 立即段输注期的证据过期窗口（3× 10 秒轮询）。 */
    val staleImmediateMillis: Long = STALE_IMMEDIATE_MILLIS,
    /** 延展段过了预计结束时刻后的兜底窗口。 */
    val staleExtendedGraceMillis: Long = STALE_EXTENDED_GRACE_MILLIS,
    /** 同一 opCode 在飞请求的超时。 */
    val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    /** 确认阶梯第一档：`-98` 发出后多久未确认转 UNCONFIRMED。 */
    val confirmUnconfirmedMillis: Long = CONFIRM_UNCONFIRMED_MILLIS,
    /** 确认阶梯第二档：`-98` 发出后多久仍无证据落 UNKNOWN。 */
    val confirmUnknownMillis: Long = CONFIRM_UNKNOWN_MILLIS,
    /**
     * 取消确认窗口：发出 `-96` 后多久仍没有 `-95` / `-91` 停止证据 → 落 UNKNOWN。
     * 取消分支的**终态兜底**，与界面「最长 10 秒」同源；不能只靠回执到达才推进。
     */
    val cancelConfirmMillis: Long = CANCEL_CONFIRM_MILLIS,
    /** 取消节流窗口（窗口内最多 [maxCancelAttempts] 次）。 */
    val cancelThrottleWindowMillis: Long = CANCEL_THROTTLE_WINDOW_MILLIS,
    val maxCancelAttempts: Int = MAX_CANCEL_ATTEMPTS,
    /** V2 常驻通知的重绘间隔。 */
    val notificationRefreshIntervalMillis: Long = NOTIFICATION_REFRESH_INTERVAL_MILLIS,
    /** 结局页自动关闭时长。 */
    val endingPageDismissMillis: Long = ENDING_PAGE_DISMISS_MILLIS,
    /**
     * 扩展大剂量「立即占比」的预设快捷项（百分比整数）。
     * 硬默认兜底；外置文件 schema 见 docs/feature/阈值配置外置-设计.md。
     * 0（0:100，立即 0）已真机确认可被泵接受（2026-11-05 泵本体截图）。
     */
    val extendedPercentPresets: List<Int> = EXTENDED_PERCENT_PRESETS,
) {
    /**
     * 延展时长上限（秒），按 Control-IQ 版本分叉：
     * - [CiqVersion.V1]（Control-IQ 1.0）→ 2h（7200s）；
     * - [CiqVersion.V2]（Control-IQ+ 1.5）→ 8h（28800s）；
     * - 版本取不到（null）→ **保守按 2h**；仅当明确知道「CIQ 关闭」时才允许 8h（沿用旧配置口径）。
     */
    fun maxExtendedSeconds(ciqEnabled: Boolean?, ciqVersion: CiqVersion? = null): Int = when (ciqVersion) {
        CiqVersion.V1 -> maxExtendedSecondsCiq
        CiqVersion.V2 -> maxExtendedSecondsNonCiq
        null -> if (ciqEnabled == false) maxExtendedSecondsNonCiq else maxExtendedSecondsCiq
    }

    companion object {
        const val MIN_BOLUS_MILLIUNITS = 50

        /** 延展/双波立即段下限兜底值：X2 要求 ≥50 mIU；Mobi 由配置改为 0。 */
        const val MIN_IMMEDIATE_MILLIUNITS = 50

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

        /** 型号 → 步进档位；单一来源是 `assets/pump-capabilities.json`（缺失时用兜底档案）。 */
        fun incrementFor(model: KnownDeviceModel?): Int =
            PumpCapabilities.forModel(model).bolusIncrementMilliunits

        /** 由能力档案（配置或兜底）生成阈值；所有数值单一来源是 `pump-capabilities.json`。 */
        fun fromProfile(profile: PumpCapabilityProfile): BolusLimits = Default.copy(
            bolusIncrementMilliunits = profile.bolusIncrementMilliunits,
            minBolusMilliunits = profile.minBolusMilliunits,
            minImmediateMilliunits = profile.minImmediateMilliunits,
            clientMinExtendedCheckMilliunits = profile.clientMinExtendedCheckMilliunits,
            minExtendedSeconds = profile.minExtendedSeconds,
            maxBolusMilliunits = profile.maxBolusMilliunits,
            maxExtendedSecondsCiq = profile.maxExtendedSecondsCiq,
            maxExtendedSecondsNonCiq = profile.maxExtendedSecondsNonCiq,
            remoteExtendedBolusStart = profile.remoteExtendedBolusStart,
            remoteExtendedBolusVerified = profile.remoteExtendedBolusVerified,
            unsupportedHint = profile.unsupportedHint,
            modelDisplayName = profile.modelDisplayName,
            staleNoBolusMillis = profile.staleNoBolusMs,
            staleImmediateMillis = profile.staleImmediateMs,
            staleExtendedGraceMillis = profile.staleExtendedGraceMs,
            requestTimeoutMillis = profile.requestTimeoutMs,
            confirmUnconfirmedMillis = profile.confirmUnconfirmedMs,
            confirmUnknownMillis = profile.confirmUnknownMs,
            cancelConfirmMillis = profile.cancelConfirmMs,
            cancelThrottleWindowMillis = profile.cancelThrottleWindowMs,
            maxCancelAttempts = profile.maxCancelAttempts,
            notificationRefreshIntervalMillis = profile.notificationRefreshIntervalMs,
            endingPageDismissMillis = profile.endingPageDismissMs,
            extendedPercentPresets = profile.extendedPercentPresets,
        )

        fun forModel(model: KnownDeviceModel?): BolusLimits =
            fromProfile(PumpCapabilities.forModel(model))

        const val MAX_BOLUS_MILLIUNITS = 25_000
        /** 客户端兜底：与 pumpx2 `MIN_EXTENDED_BOLUS_MILLIUNITS` 同值，**不是**泵的下限。 */
        const val CLIENT_MIN_EXTENDED_CHECK_MILLIUNITS = 400
        /** 15 分钟；Mobi 手册的 UI 可设下限（秒）。 */
        const val MIN_EXTENDED_SECONDS = 900
        const val MAX_EXTENDED_SECONDS_NON_CIQ = 28_800
        const val MAX_EXTENDED_SECONDS_CIQ = 7_200
        /** 无输注时会话痕迹的过期兜底（10 分钟）。 */
        const val STALE_NO_BOLUS_MILLIS = 600_000L
        /** 立即段证据过期兜底（3× 10 秒轮询）。 */
        const val STALE_IMMEDIATE_MILLIS = 30_000L
        /** 延展段预计结束后仍无结束证据的兜底（5 分钟）。 */
        const val STALE_EXTENDED_GRACE_MILLIS = 300_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val CONFIRM_UNCONFIRMED_MILLIS = 10_000L
        const val CONFIRM_UNKNOWN_MILLIS = 60_000L
        const val CANCEL_CONFIRM_MILLIS = 10_000L
        const val CANCEL_THROTTLE_WINDOW_MILLIS = 15_000L
        const val MAX_CANCEL_ATTEMPTS = 3
        const val NOTIFICATION_REFRESH_INTERVAL_MILLIS = 1_000L
        const val ENDING_PAGE_DISMISS_MILLIS = 15_000L

        /** 立即占比预设：0:100、50:50、75:25（0 已真机确认可接受）。 */
        val EXTENDED_PERCENT_PRESETS = listOf(0, 50, 75)

        val Default = BolusLimits()
    }
}
