package com.pumppan.controlx2.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 定时大剂量的时间宿主：**`AlarmManager` + `BroadcastReceiver`**（用户 2026-11-12 选定）。
 *
 * 选它而不是前台服务的原因：预约粒度是「分钟级」，不需要常驻进程；
 * 代价是**进程被杀/厂商冻结会让闹钟晚到**，所以每次延迟/错过都靠
 * [ScheduledBolusRuntime.restore] 在下次打开 App 时对账（R10）。
 *
 * 只用 `setExactAndAllowWhileIdle`；拿不到精确闹钟权限时退回 `setAndAllowWhileIdle`，
 * 并在界面标注「可能晚几分钟」，绝不静默声称准点。
 */
object ScheduledBolusAlarms {

    const val ACTION_TICK = "com.pumppan.controlx2.scheduled.action.TICK"
    const val ACTION_CANCEL = "com.pumppan.controlx2.scheduled.action.CANCEL"

    private const val REQUEST_TICK = 4101

    /** 在 [atMillis] 触发一次 [ACTION_TICK]；同一时刻只有一个待触发闹钟（新排程覆盖旧的）。 */
    fun schedule(context: Context, atMillis: Long, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = maxOf(atMillis, now + 1_000L)
        val pending = pending(context)

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_TICK,
            Intent(context, ScheduledBolusReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
