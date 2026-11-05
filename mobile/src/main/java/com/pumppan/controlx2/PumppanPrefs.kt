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
}
