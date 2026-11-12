package com.pumppan.controlx2.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时大剂量的闹钟入口（用户 2026-11-12 选定的宿主）。
 *
 * 只做两件事：推进状态机（[ScheduledBolusRuntime.tick]）与取消；
 * 真正的输注必须在 App 内确认后走既有大剂量链路，订阅方**不能**在这里发 `-98`。
 */
class ScheduledBolusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduledBolusAlarms.ACTION_TICK -> ScheduledBolusRuntime.tick(context)
            ScheduledBolusAlarms.ACTION_CANCEL -> ScheduledBolusRuntime.cancel(context)
        }
    }
}
