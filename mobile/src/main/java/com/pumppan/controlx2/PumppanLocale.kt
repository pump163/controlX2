package com.pumppan.controlx2

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 语言切换（设置页）：AUTO 跟随系统，ZH / EN 强制覆盖。
 * 覆盖只影响资源解析；`Locale.getDefault()` 不变，数字/日期格式不受影响。
 */
enum class PumppanLocale(val key: String, val locale: Locale?) {
    AUTO("auto", null),
    ZH("zh", Locale.SIMPLIFIED_CHINESE),
    EN("en", Locale.ENGLISH),
    ;

    companion object {
        fun fromKey(key: String?): PumppanLocale =
            values().firstOrNull { it.key == key } ?: AUTO

        /** 把 context 的资源 locale 换成用户选择的语言；AUTO 原样返回。 */
        fun wrap(base: Context): Context {
            val locale = PumppanPrefs.locale(base).locale ?: return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
