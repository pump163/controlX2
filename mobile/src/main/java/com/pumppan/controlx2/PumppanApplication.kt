package com.pumppan.controlx2

import android.app.Application
import android.content.Context
import com.jwoglom.controlx2.shared.FeatureFlag

/**
 * pumppan: 全局 Application，挂三件事。
 * 1. attachBaseContext 让 applicationContext 跟随语言开关；
 *    注意 Activity / CommService / MobileClientService 各自也在 attachBaseContext 里
 *    手动 wrap——Service 不会因语言切换而重建，已显示的通知文案要重启应用后才更新。
 * 2. onCreate 时把持久化的泵型号覆盖灌进全局开关。
 * 3. onCreate 时调 FeatureFlag.initState 读 SharedPreferences 初始化全局 mutableStateOf，
 *    保证 Dashboard 首次打开时全局状态 = 用户上次保存的值（而非默认 true）。
 */
class PumppanApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(PumppanLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        PumppanPumpModel.apply(this)
        FeatureFlag.initState(this)
    }
}
