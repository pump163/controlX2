package com.jwoglom.controlx2.pump

import android.content.Context
import android.os.SystemClock

/**
 * 链路断开的起始时刻，供 UI 分层提示与后台 watchdog 对齐使用。
 *
 * 用 SharedPreferences 保存而不是只在内存里记：App 切后台、Activity 重建、
 * 甚至断联发生在界面没打开的时候，UI 回来后也能算出真实的已断联时长，
 * 而不是从 0 重新数（否则 30 分钟档的「继续」按钮永远不会出现）。
 *
 * 时间基准是 elapsedRealtime（不含深睡），与 watchdog 的计时同源；因此重启
 * 手机会丢失，这与 watchdog 本身的行为一致（重启后本来就重新开始一轮）。
 */
object BleLinkClock {
    private const val PREFS = "ble_link_clock"
    private const val KEY_DOWN_SINCE = "down_since_elapsed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录本次断联起点；已有值时不覆盖，避免多次断开事件把计时往后推。 */
    fun markDown(context: Context) {
        prefs(context).edit().putLong(KEY_DOWN_SINCE, SystemClock.elapsedRealtime()).apply()
    }

    /** 连接成功，清除断联起点。 */
    fun markUp(context: Context) {
        prefs(context).edit().remove(KEY_DOWN_SINCE).apply()
    }

    /** 用户点「继续」：重新开始计时。 */
    fun restart(context: Context) {
        markDown(context)
    }

    /** 当前仍在断联状态时，返回断联起点；已连接或从未断开返回 0。 */
    fun downSince(context: Context): Long = prefs(context).getLong(KEY_DOWN_SINCE, 0L)

    /** 已断联的秒数；未断开返回 0。 */
    fun downSeconds(context: Context): Int {
        val since = downSince(context)
        if (since <= 0L) return 0
        return ((SystemClock.elapsedRealtime() - since) / 1000).toInt()
    }
}
