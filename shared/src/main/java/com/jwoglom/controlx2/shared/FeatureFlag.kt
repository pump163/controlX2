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
    CgmChartVisible,
    SensorInfoCardVisible,
    HistoryLogSyncBarVisible,
    ;

    val slug: String get() = when (this) {
        BTHostSwitch -> name
        CgmChartVisible -> "首页CGM图表显示"
        SensorInfoCardVisible -> "首页传感器信息卡片"
        HistoryLogSyncBarVisible -> "首页历史记录进度条"
    }

    val defaultValue: Boolean get() = when (this) {
        BTHostSwitch -> false
        CgmChartVisible -> true
        SensorInfoCardVisible -> true
        HistoryLogSyncBarVisible -> true
    }

    companion object {
        private const val PREFS_NAME = "WearX2"
        private const val PREFIX = "feature-flag-"

        // 全局状态，实时响应开关变化
        val cgmChartVisibleState = mutableStateOf(true)
        val sensorInfoCardVisibleState = mutableStateOf(true)
        val historyLogSyncBarVisibleState = mutableStateOf(true)

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun enabled(context: Context, flag: FeatureFlag): Boolean =
            prefs(context).getBoolean(PREFIX + flag.slug, flag.defaultValue)

        fun set(context: Context, flag: FeatureFlag, value: Boolean) {
            prefs(context).edit().putBoolean(PREFIX + flag.slug, value).commit()
            // 更新全局状态
            when (flag) {
                CgmChartVisible -> cgmChartVisibleState.value = value
                SensorInfoCardVisible -> sensorInfoCardVisibleState.value = value
                HistoryLogSyncBarVisible -> historyLogSyncBarVisibleState.value = value
                else -> {}
            }
        }

        fun initState(context: Context) {
            // 初始化全局状态
            cgmChartVisibleState.value = enabled(context, CgmChartVisible)
            sensorInfoCardVisibleState.value = enabled(context, SensorInfoCardVisible)
            historyLogSyncBarVisibleState.value = enabled(context, HistoryLogSyncBarVisible)
        }
    }
}
