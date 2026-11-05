package com.pumppan.controlx2.bolus

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pumppan.controlx2.PumppanPrefs

/** 大剂量入口的两种形态：旧弹层（legacy）与导航栏新页面（v2，S9 暂为占位页）。 */
enum class BolusUiMode {
    LEGACY,
    V2,
}

/**
 * 大剂量入口的唯一开关判断点：`Landing` 只调这里，避免 `PumppanPrefs` 判断散落多处。
 */
object BolusRouter {
    fun mode(context: Context): BolusUiMode =
        if (PumppanPrefs.bolusUiV2(context)) BolusUiMode.V2 else BolusUiMode.LEGACY

    fun showLegacyEntry(mode: BolusUiMode): Boolean = mode == BolusUiMode.LEGACY

    fun showNavEntry(mode: BolusUiMode): Boolean = mode == BolusUiMode.V2

    /** 读取偏好并监听变化：设置页开关切换后无需重启即可生效。 */
    @Composable
    fun rememberBolusUiMode(): BolusUiMode {
        val context = LocalContext.current
        var current by remember { mutableStateOf(BolusRouter.mode(context)) }
        DisposableEffect(context) {
            val prefs = context.getSharedPreferences(PumppanPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PumppanPrefs.KEY_BOLUS_UI) current = BolusRouter.mode(context)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return current
    }
}
