package com.pumppan.controlx2.scheduled

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.MainActivity

/**
 * 定时大剂量的常驻/提醒通知。
 *
 * 口径（R9/R16/R23）：通知必须带**一键取消**，且「取消」在锁屏也能用；
 * 确认只能回 App 做，通知的正文只负责把信息说清，绝不把「点通知」等于「已确认」。
 */
object ScheduledBolusNotifications {

    const val CHANNEL_ID = "scheduled_bolus"
    const val NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.scheduled_bolus_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.scheduled_bolus_channel_name)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(
        context: Context,
        title: String,
        text: String,
        cancellable: Boolean = true,
        ongoing: Boolean = true,
    ) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SCHEDULED, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ScheduledBolusReceiver::class.java)
                .setAction(ScheduledBolusAlarms.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.bolus_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (cancellable) {
            builder.addAction(0, context.getString(R.string.scheduled_bolus_action_cancel), cancel)
        }

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 从通知进入 App 时用于定位到预约界面；当前只作标记，不改变导航。 */
    const val EXTRA_OPEN_SCHEDULED = "pumppan.open_scheduled"
}
