package com.jwoglom.controlx2.shared

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Per-device runtime gates for experimental behavior. Backed by the shared
 * "WearX2" SharedPreferences file (the same store mobile's `Prefs` and
 * wear's `WearPrefs` use), so values follow the app data lifecycle and are
 * wiped by Clear data. Values are NOT synced between phone and watch — toggle
 * on each device independently.
 *
 * Adding a flag is one enum entry; the per-module Feature Flags screens
 * iterate `values()` automatically.
 */
enum class FeatureFlag {
    BTHostSwitch,
    HistoryLogSyncBarVisible,
    ;

    val slug: String get() = when (this) {
        BTHostSwitch -> name
        HistoryLogSyncBarVisible -> "切换首页历史记录进度条的显示"
    }

    val defaultValue: Boolean get() = when (this) {
        BTHostSwitch -> false
        HistoryLogSyncBarVisible -> true
    }

    companion object {
        private const val PREFS_NAME = "WearX2"
        private const val PREFIX = "feature-flag-"

        // 全局状态，实时响应开关变化
        val historyLogSyncBarVisibleState = mutableStateOf(true)

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun enabled(context: Context, flag: FeatureFlag): Boolean =
            prefs(context).getBoolean(PREFIX + flag.slug, flag.defaultValue)

        fun set(context: Context, flag: FeatureFlag, value: Boolean) {
            prefs(context).edit().putBoolean(PREFIX + flag.slug, value).commit()
            // 更新全局状态
            when (flag) {
                HistoryLogSyncBarVisible -> historyLogSyncBarVisibleState.value = value
                else -> {}
            }
        }

        fun initState(context: Context) {
            // 初始化全局状态
            historyLogSyncBarVisibleState.value = enabled(context, HistoryLogSyncBarVisible)
        }
    }
}
