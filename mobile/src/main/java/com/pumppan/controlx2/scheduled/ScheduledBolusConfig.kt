package com.pumppan.controlx2.scheduled

import android.content.Context
import com.pumppan.controlx2.PumppanPrefs
import org.json.JSONObject

/**
 * 定时大剂量的全部数值参数（设计 `定时大剂量-设计.md` §3 / §3.1）。
 *
 * 优先级：**用户覆盖（prefs） > JSON 默认（`assets/pump-capabilities.json` 的 `limits`） > 代码兜底**。
 * 读取点只有一个：链启动时取一次快照，链内不再改，避免半途参数漂移。
 */
data class ScheduledBolusConfig(
    /** 链最多笔数（R3）；不含预约池。 */
    val maxChainCount: Int = DEFAULT_MAX_CHAIN_COUNT,
    /** 预约池模板上限（R3/R21）。 */
    val maxPresetCount: Int = DEFAULT_MAX_PRESET_COUNT,
    /** 链运行中连续断连超过该值即整链作废（R22）。 */
    val linkLossAbortMs: Long = DEFAULT_LINK_LOSS_ABORT_MS,
    /** T−N 提前通知（R5/R12），只发通知、不弹框。 */
    val advanceNoticeMs: Long = DEFAULT_ADVANCE_NOTICE_MS,
    /** 提前量上限（R5）：大于该值不接受。 */
    val advanceNoticeMaxMs: Long = DEFAULT_ADVANCE_NOTICE_MAX_MS,
    /** 血糖读数年龄标注阈值；**纯提示**，不参与任何判定（§5.3）。 */
    val maxBgAgeMs: Long = DEFAULT_MAX_BG_AGE_MS,
    /** 到点后仍未二次确认的放弃时限（R13）；超时即整链作废。 */
    val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    /** 到点断连宽限；R14 固定 0，连不上即作废。 */
    val reconnectGraceMs: Long = 0L,
    /**
     * 相邻两笔的最小间隔（从上一笔**开始证据**算起）。防止把两笔排得太近。
     * 初始值 5 分钟，属待实测校准的可配项。
     */
    val minLinkIntervalMs: Long = DEFAULT_MIN_LINK_INTERVAL_MS,
    /** 相邻两笔的最大间隔；默认 24 小时，避免误填出跨天链路。 */
    val maxLinkIntervalMs: Long = DEFAULT_MAX_LINK_INTERVAL_MS,
    /**
     * 链的**最少**笔数（含首笔）。默认 2：单笔请直接用普通大剂量，
     * 定时功能存在的意义是「第二笔起」。
     */
    val minChainLinks: Int = DEFAULT_MIN_CHAIN_LINKS,
    /** 执行前置检查的「链路新鲜度」容忍度：最近一条成功报文不得超过该年龄。 */
    val preflightLinkFreshMs: Long = DEFAULT_PREFLIGHT_LINK_FRESH_MS,
) {
    /** 夹取到合法区间：提前量 0–上限；链长至少 1。 */
    fun normalized(): ScheduledBolusConfig = copy(
        maxChainCount = maxChainCount.coerceAtLeast(1),
        maxPresetCount = maxPresetCount.coerceAtLeast(1),
        advanceNoticeMs = advanceNoticeMs.coerceIn(0L, advanceNoticeMaxMs),
        confirmTimeoutMs = confirmTimeoutMs.coerceAtLeast(0L),
        linkLossAbortMs = linkLossAbortMs.coerceAtLeast(0L),
        minLinkIntervalMs = minLinkIntervalMs.coerceAtLeast(1L),
        maxLinkIntervalMs = maxLinkIntervalMs.coerceAtLeast(minLinkIntervalMs.coerceAtLeast(1L)),
        minChainLinks = minChainLinks.coerceIn(1, maxChainCount.coerceAtLeast(1)),
        preflightLinkFreshMs = preflightLinkFreshMs.coerceAtLeast(1_000L),
    )

    companion object {
        const val DEFAULT_MAX_CHAIN_COUNT = 3
        const val DEFAULT_MAX_PRESET_COUNT = 6
        const val DEFAULT_LINK_LOSS_ABORT_MS = 120_000L
        const val DEFAULT_ADVANCE_NOTICE_MS = 120_000L
        const val DEFAULT_ADVANCE_NOTICE_MAX_MS = 300_000L
        const val DEFAULT_MAX_BG_AGE_MS = 600_000L
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 300_000L
        const val DEFAULT_MIN_LINK_INTERVAL_MS = 300_000L
        const val DEFAULT_MAX_LINK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MIN_CHAIN_LINKS = 2
        const val DEFAULT_PREFLIGHT_LINK_FRESH_MS = 15_000L

        private const val ASSET_NAME = "pump-capabilities.json"

        /** 唯一读取入口。缺文件/解析失败一律回落代码兜底，不阻断启动。 */
        fun load(context: Context): ScheduledBolusConfig {
            val json = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()?.let { runCatching { parse(it) }.getOrNull() }
            return (json ?: ScheduledBolusConfig()).let { base ->
                base.copy(
                    maxChainCount = PumppanPrefs.scheduledMaxChainCount(context) ?: base.maxChainCount,
                    maxPresetCount = PumppanPrefs.scheduledMaxPresetCount(context) ?: base.maxPresetCount,
                    linkLossAbortMs = PumppanPrefs.scheduledLinkLossAbortMs(context) ?: base.linkLossAbortMs,
                    advanceNoticeMs = PumppanPrefs.scheduledAdvanceNoticeMs(context) ?: base.advanceNoticeMs,
                    maxBgAgeMs = PumppanPrefs.scheduledMaxBgAgeMs(context) ?: base.maxBgAgeMs,
                    confirmTimeoutMs = PumppanPrefs.scheduledConfirmTimeoutMs(context) ?: base.confirmTimeoutMs,
                    preflightLinkFreshMs = PumppanPrefs.scheduledLinkFreshMs(context),
                )
            }.normalized()
        }

        /** 只读 `default.limits`；型号档案不重复配置这些与机型无关的阈值。 */
        internal fun parse(raw: String): ScheduledBolusConfig {
            val limits = JSONObject(raw)
                .optJSONObject("default")
                ?.optJSONObject("limits")
                ?: return ScheduledBolusConfig()
            return ScheduledBolusConfig(
                maxChainCount = limits.optInt("scheduledMaxChainCount", DEFAULT_MAX_CHAIN_COUNT),
                maxPresetCount = limits.optInt("scheduledMaxPresetCount", DEFAULT_MAX_PRESET_COUNT),
                linkLossAbortMs = limits.optLong("scheduledLinkLossAbortMs", DEFAULT_LINK_LOSS_ABORT_MS),
                advanceNoticeMs = limits.optLong("scheduledAdvanceNoticeMs", DEFAULT_ADVANCE_NOTICE_MS),
                advanceNoticeMaxMs = limits.optLong("scheduledAdvanceNoticeMaxMs", DEFAULT_ADVANCE_NOTICE_MAX_MS),
                maxBgAgeMs = limits.optLong("scheduledMaxBgAgeMs", DEFAULT_MAX_BG_AGE_MS),
                confirmTimeoutMs = limits.optLong("scheduledConfirmTimeoutMs", DEFAULT_CONFIRM_TIMEOUT_MS),
                reconnectGraceMs = limits.optLong("scheduledReconnectGraceMs", 0L),
                minLinkIntervalMs = limits.optLong("scheduledMinLinkIntervalMs", DEFAULT_MIN_LINK_INTERVAL_MS),
                maxLinkIntervalMs = limits.optLong("scheduledMaxLinkIntervalMs", DEFAULT_MAX_LINK_INTERVAL_MS),
                minChainLinks = limits.optInt("scheduledMinChainLinks", DEFAULT_MIN_CHAIN_LINKS),
                preflightLinkFreshMs = limits.optLong("scheduledPreflightLinkFreshMs", DEFAULT_PREFLIGHT_LINK_FRESH_MS),
            )
        }
    }
}
