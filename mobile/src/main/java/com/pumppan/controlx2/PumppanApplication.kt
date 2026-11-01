package com.pumppan.controlx2

import android.app.Application
import android.content.Context

/**
 * pumppan: 让 applicationContext 也跟随语言开关。
 * 不加这个类时，语言开关只影响 Activity，通知栏 / Service 的 getString 仍是系统语言。
 */
class PumppanApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(PumppanLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        PumppanPumpModel.apply(this)
    }
}
