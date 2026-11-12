package com.pumppan.controlx2

import android.content.Context
import android.content.SharedPreferences

/**
 * pumppan 个人分支的设置项，与主程序共用 "WearX2" prefs 文件，
 * 因此不需要修改 `com.jwoglom.controlx2.Prefs`。
 */
internal object PumppanPrefs {
    internal const val PREFS_NAME = "WearX2"
    private const val KEY_LOCALE = "pumppan-locale"
    private const val KEY_PUMP_MODEL = "pumppan-pump-model"
    internal const val KEY_BOLUS_UI = "pumppan-bolus-ui"
    private const val KEY_LEDGER_CARD = "pumppan-ledger-card"
    private const val KEY_LEDGER_GRACE_MS = "pumppan-ledger-grace-ms"
    private const val KEY_LEDGER_MAX = "pumppan-ledger-max"
    private const val KEY_LEDGER_ADOPT_MS = "pumppan-ledger-adopt-ms"
    private const val KEY_LEDGER_RETENTION_MS = "pumppan-ledger-retention-ms"
    private const val KEY_LEDGER_PURGE_MS = "pumppan-ledger-purge-ms"
    private const val KEY_SCHEDULED_MAX_CHAIN = "pumppan-scheduled-max-chain"
    private const val KEY_SCHEDULED_MAX_PRESET = "pumppan-scheduled-max-preset"
    private const val KEY_SCHEDULED_LINK_LOSS_MS = "pumppan-scheduled-link-loss-ms"
    private const val KEY_SCHEDULED_ADVANCE_MS = "pumppan-scheduled-advance-ms"
    private const val KEY_SCHEDULED_CONFIRM_MS = "pumppan-scheduled-confirm-ms"
    private const val KEY_SCHEDULED_MAX_BG_AGE_MS = "pumppan-scheduled-max-bg-age-ms"
    private const val KEY_SCHEDULED_CHAIN_JSON = "pumppan-scheduled-chain-json"
    /** 控制页开关：定时大剂量入口是否出现。默认关（高手功能，严格隐藏）。 */
    private const val KEY_SCHEDULED_VISIBLE = "pumppan-scheduled-visible"
    /** 危险页开关：「不确认即输注」是否开启。持久化，但入口开关关闭时强制清零。 */
    private const val KEY_SCHEDULED_AUTOSEND = "pumppan-scheduled-autosend"
    /** 6 个预约槽（模板）：只存「相对时隔 + 剂量」，不存绝对时间。 */
    private const val KEY_SCHEDULED_PRESETS_JSON = "pumppan-scheduled-presets-json"
    /** 执行前置检查：最近一条成功报文的最大年龄（容忍度）。 */
    private const val KEY_SCHEDULED_LINK_FRESH_MS = "pumppan-scheduled-link-fresh-ms"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun locale(context: Context): PumppanLocale =
        PumppanLocale.fromKey(prefs(context).getString(KEY_LOCALE, null))

    fun setLocale(context: Context, mode: PumppanLocale) {
        prefs(context).edit().putString(KEY_LOCALE, mode.key).apply()
    }

    fun pumpModel(context: Context): PumppanPumpModel =
        PumppanPumpModel.fromKey(prefs(context).getString(KEY_PUMP_MODEL, null))

    fun setPumpModel(context: Context, mode: PumppanPumpModel) {
        prefs(context).edit().putString(KEY_PUMP_MODEL, mode.key).apply()
    }

    /** 是否使用新的大剂量输注页面（导航栏入口）；默认 false = legacy。 */
    fun bolusUiV2(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOLUS_UI, false)

    fun setBolusUiV2(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOLUS_UI, enabled).apply()
    }

    /**
     * 通知页「大剂量流水」开关。默认 false：仅 X2 机型、且用户显式开启后才渲染。
     * 关闭时只是不显示卡片，不清理已有条目（维持“重启后清空”这一条唯一清空条件）。
     */
    fun ledgerCard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEDGER_CARD, false)

    fun setLedgerCard(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEDGER_CARD, enabled).apply()
    }

    /** 空态后等待终态的宽限期（毫秒）。默认 60 000：实测 20 s / 35 s。 */
    fun ledgerTerminalGraceMs(context: Context): Long =
        prefs(context).getLong(KEY_LEDGER_GRACE_MS, 60_000L)

    fun setLedgerTerminalGraceMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LEDGER_GRACE_MS, ms).apply()
    }

    /** 最多保留多少笔已终态的条目；进行中的笔永不淘汰。 */
    fun ledgerMaxEpisodes(context: Context): Int =
        prefs(context).getInt(KEY_LEDGER_MAX, 20)

    fun setLedgerMaxEpisodes(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_LEDGER_MAX, count).apply()
    }

    /** 补录窗口（毫秒）：只收这段时间内结束、但没看到开笔的笔。默认 600 000（10 分钟）。 */
    fun ledgerAdoptTerminalMs(context: Context): Long =
        prefs(context).getLong(KEY_LEDGER_ADOPT_MS, 600_000L)

    fun setLedgerAdoptTerminalMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LEDGER_ADOPT_MS, ms).apply()
    }

    /** 流水保留时长（毫秒），默认 72 小时；超期条目在下次清理时丢弃。 */
    fun ledgerRetentionMs(context: Context): Long =
        prefs(context).getLong(KEY_LEDGER_RETENTION_MS, 72L * 60 * 60 * 1000)

    fun setLedgerRetentionMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LEDGER_RETENTION_MS, ms).apply()
    }

    /** 过期清理间隔（毫秒），默认 30 分钟。 */
    fun ledgerPurgeIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_LEDGER_PURGE_MS, 30L * 60 * 1000)

    fun setLedgerPurgeIntervalMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LEDGER_PURGE_MS, ms).apply()
    }

    // ---- 定时大剂量：运行期覆盖（未设置时返回 null，表示「用 JSON 默认」） ----

    fun scheduledMaxChainCount(context: Context): Int? =
        prefs(context).let { if (it.contains(KEY_SCHEDULED_MAX_CHAIN)) it.getInt(KEY_SCHEDULED_MAX_CHAIN, 3) else null }

    fun setScheduledMaxChainCount(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SCHEDULED_MAX_CHAIN, value).apply()

    fun scheduledMaxPresetCount(context: Context): Int? =
        prefs(context).let { if (it.contains(KEY_SCHEDULED_MAX_PRESET)) it.getInt(KEY_SCHEDULED_MAX_PRESET, 6) else null }

    fun setScheduledMaxPresetCount(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SCHEDULED_MAX_PRESET, value).apply()

    fun scheduledLinkLossAbortMs(context: Context): Long? =
        prefs(context).let {
            if (it.contains(KEY_SCHEDULED_LINK_LOSS_MS)) it.getLong(KEY_SCHEDULED_LINK_LOSS_MS, 120_000L) else null
        }

    fun setScheduledLinkLossAbortMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_SCHEDULED_LINK_LOSS_MS, ms).apply()

    fun scheduledAdvanceNoticeMs(context: Context): Long? =
        prefs(context).let {
            if (it.contains(KEY_SCHEDULED_ADVANCE_MS)) it.getLong(KEY_SCHEDULED_ADVANCE_MS, 120_000L) else null
        }

    fun setScheduledAdvanceNoticeMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_SCHEDULED_ADVANCE_MS, ms).apply()

    fun scheduledConfirmTimeoutMs(context: Context): Long? =
        prefs(context).let {
            if (it.contains(KEY_SCHEDULED_CONFIRM_MS)) it.getLong(KEY_SCHEDULED_CONFIRM_MS, 300_000L) else null
        }

    fun setScheduledConfirmTimeoutMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_SCHEDULED_CONFIRM_MS, ms).apply()

    fun scheduledMaxBgAgeMs(context: Context): Long? =
        prefs(context).let {
            if (it.contains(KEY_SCHEDULED_MAX_BG_AGE_MS)) it.getLong(KEY_SCHEDULED_MAX_BG_AGE_MS, 600_000L) else null
        }

    fun setScheduledMaxBgAgeMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_SCHEDULED_MAX_BG_AGE_MS, ms).apply()

    /** 链状态单条 JSON（§8.0）；null = 无预约。 */
    fun scheduledChainJson(context: Context): String? =
        prefs(context).getString(KEY_SCHEDULED_CHAIN_JSON, null)

    fun setScheduledChainJson(context: Context, json: String?) {
        val editor = prefs(context).edit()
        if (json == null) editor.remove(KEY_SCHEDULED_CHAIN_JSON) else editor.putString(KEY_SCHEDULED_CHAIN_JSON, json)
        editor.apply()
    }

    /** 控制页开关：定时大剂量是否显示。关闭时由调用方负责作废正在跑的对链。 */
    fun scheduledVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULED_VISIBLE, false)

    fun setScheduledVisible(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_SCHEDULED_VISIBLE, enabled)
        // 强制联动：入口一关，「不确认即输注」必须同时关掉，避免「隐藏但自动打」的组合。
        if (!enabled) editor.putBoolean(KEY_SCHEDULED_AUTOSEND, false)
        editor.apply()
    }

    /** 危险页开关：到点不确认也发。持久化；入口开关关闭时被 [setScheduledVisible] 强制清零。 */
    fun scheduledAutoSend(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULED_AUTOSEND, false)

    fun setScheduledAutoSend(context: Context, enabled: Boolean) {
        // 入口未开启时不允许单独打开自动输注。
        if (enabled && !scheduledVisible(context)) return
        prefs(context).edit().putBoolean(KEY_SCHEDULED_AUTOSEND, enabled).apply()
    }

    fun scheduledPresetsJson(context: Context): String? =
        prefs(context).getString(KEY_SCHEDULED_PRESETS_JSON, null)

    fun setScheduledPresetsJson(context: Context, json: String?) {
        val editor = prefs(context).edit()
        if (json == null) editor.remove(KEY_SCHEDULED_PRESETS_JSON)
        else editor.putString(KEY_SCHEDULED_PRESETS_JSON, json)
        editor.apply()
    }

    /** 前置检查的链路容忍度；默认 15 秒（比 10 秒一轮的查询周期略宽）。 */
    fun scheduledLinkFreshMs(context: Context): Long =
        prefs(context).getLong(KEY_SCHEDULED_LINK_FRESH_MS, 15_000L)

    fun setScheduledLinkFreshMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_SCHEDULED_LINK_FRESH_MS, ms).apply()
}
