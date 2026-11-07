package com.jwoglom.controlx2.pump

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jwoglom.controlx2.BolusNotificationBroadcastReceiver
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces
import com.jwoglom.pumpx2.pump.messages.helpers.Bytes
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.shared.Hex
import timber.log.Timber
import java.time.Instant

class BolusManager(
    private val context: Context,
    private val sendWearCommMessage: (String, ByteArray) -> Unit,
) {
    private var bolusNotificationId: Int = 1000

    enum class BolusRequestSource(val id: String) {
        PHONE("phone"),
        WEAR("wear")
    }

    /**
     * @param autoApprove 确认已在 v2 大剂量确认页完成：跳过旧「待确认」通知与旧确认对话框，
     *   但仍然走**同一条** `INITIATE` 签名链路把 `-98` 真正发给泵。
     *   只由 v2 模式下的 phone 请求传 true；wear / legacy 保持默认 false。
     */
    fun confirmBolusRequest(
        request: InitiateBolusRequest,
        source: BolusRequestSource,
        autoApprove: Boolean = false,
    ) {
        val units = twoDecimalPlaces(InsulinUnit.from1000To1(request.totalVolume))
        Timber.i("confirmBolusRequest $units: $request")
        bolusNotificationId++
        prefs()?.edit()
            ?.putString("initiateBolusRequest", Hex.encodeHexString(PumpMessageSerializer.toBytes(request)))
            ?.putString("initiateBolusSecret", Hex.encodeHexString(Bytes.getSecureRandom10Bytes()))
            ?.putString("initiateBolusSource", source.id)
            ?.putLong("initiateBolusTime", Instant.now().toEpochMilli())
            ?.putInt("initiateBolusNotificationId", bolusNotificationId)
            ?.commit().let {
                if (it != true) {
                    Timber.e("synchronous preference write failed in confirmBolusRequest")
                }
            }

        fun getRejectIntent(): PendingIntent {
            val rejectIntent =
                Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                    putExtra("action", "REJECT")
                }
            return PendingIntent.getBroadcast(
                context,
                2000,
                rejectIntent,
                FLAG_IMMUTABLE or FLAG_ONE_SHOT
            )
        }

        fun getConfirmIntent(silent: Boolean = false): PendingIntent {
            val confirmIntent =
                Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                    putExtra("action", "INITIATE")
                    putExtra("request", PumpMessageSerializer.toBytes(request))
                    // 接收器据此跳过旧式进度通知；发送链路不受影响。
                    if (silent) putExtra("silent", true)
                }
            return PendingIntent.getBroadcast(
                context,
                2001,
                confirmIntent,
                FLAG_IMMUTABLE or FLAG_ONE_SHOT
            )
        }

        val minNotifyThreshold = Prefs(context).bolusConfirmationInsulinThreshold()
        val autoApproveTimeout = if (source == BolusRequestSource.WEAR)
            Prefs(context).wearBolusAutoApproveTimeoutSeconds() else 0
        sendWearCommMessage(MessagePaths.TO_CLIENT_BOLUS_MIN_NOTIFY_THRESHOLD, "$minNotifyThreshold".toByteArray())
        sendWearCommMessage(MessagePaths.TO_CLIENT_WEAR_AUTO_APPROVE_TIMEOUT, "$autoApproveTimeout".toByteArray())

        if (autoApprove) {
            // v2：用户在 v2 确认页已经确认过，这里只把请求交回同一条 INITIATE 签名链路：
            // 不发旧「待确认」通知，也不广播旧确认对话框（否则会和 v2 页面重复确认）。
            Timber.i("v2 bolus: 确认已完成，抑制旧通知/旧对话框，直接发 INITIATE（$units U）")
            getConfirmIntent(silent = true).send()
            return
        }

        if (InsulinUnit.from1000To1(request.totalVolume) >= minNotifyThreshold || minNotifyThreshold == 0.0) {
            Timber.i("Requesting permission for bolus because $units >= minNotifyThreshold=$minNotifyThreshold")

            val builder = confirmBolusRequestBaseNotification(
                context,
                context.getString(R.string.bolus_request),
                if (autoApproveTimeout > 0) context.getString(R.string.bolus_auto_approve_in_sec_unless_canceled, units, autoApproveTimeout)
                else context.getString(R.string.bolus_units_press_confirm_to_deliver, units)
            )
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setVibrate(longArrayOf(500L, 500L, 500L, 500L, 500L, 500L, 500L, 500L, 500L, 500L))

            builder.addAction(R.drawable.decline, context.getString(R.string.bolus_reject), getRejectIntent())

            builder.addAction(R.drawable.bolus_icon, context.getString(R.string.bolus_confirm_u, units), getConfirmIntent())

            val notif = builder.build()
            Timber.i("bolus notification $bolusNotificationId $builder $notif")
            makeNotif(bolusNotificationId, notif)

            // Broadcast to phone UI for in-app dialog
            sendWearCommMessage(MessagePaths.TO_SERVER_BOLUS_CONFIRM_DIALOG,
                "${Hex.encodeHexString(PumpMessageSerializer.toBytes(request))}|${source.id}|$autoApproveTimeout".toByteArray())

            // Schedule auto-approve if timeout is set
            if (autoApproveTimeout > 0) {
                scheduleAutoApprove(autoApproveTimeout, getConfirmIntent())
            }
        } else {
            Timber.i("Sending immediate bolus request because $units is less than minNotifyThreshold=$minNotifyThreshold")

            val confirmIntent =
                Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                    putExtra("action", "INITIATE")
                    putExtra("request", PumpMessageSerializer.toBytes(request))
                }
            val confirmPendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                confirmIntent,
                FLAG_IMMUTABLE or FLAG_ONE_SHOT
            )
            // wait to avoid prefs not being saved
            Thread.sleep(250)
            confirmPendingIntent.send()
        }
    }

    private fun scheduleAutoApprove(timeoutSeconds: Int, confirmIntent: PendingIntent) {
        cancelAutoApproveStatic(context)
        val handler = Handler(Looper.getMainLooper())
        _autoApproveHandler = handler
        val runnable = Runnable {
            Timber.i("Auto-approving bolus after ${timeoutSeconds}s timeout")
            try {
                confirmIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                Timber.w("Auto-approve PendingIntent already cancelled (bolus was manually handled)")
            }
            _autoApproveRunnable = null
            _autoApproveHandler = null
        }
        _autoApproveRunnable = runnable
        handler.postDelayed(runnable, timeoutSeconds * 1000L)
    }

    fun resetBolusPrefs() {
        prefs()?.edit()
            ?.remove("initiateBolusRequest")
            ?.remove("initiateBolusTime")
            ?.apply()
    }

    private fun prefs(): SharedPreferences? {
        return context.getSharedPreferences("WearX2", Context.MODE_PRIVATE)
    }

    private fun makeNotif(id: Int, notif: Notification) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notif)
    }

    companion object {
        @Volatile
        private var _autoApproveHandler: Handler? = null
        @Volatile
        private var _autoApproveRunnable: Runnable? = null

        fun cancelAutoApproveStatic(context: Context?) {
            _autoApproveRunnable?.let { runnable ->
                _autoApproveHandler?.removeCallbacks(runnable)
                Timber.i("Auto-approve timer cancelled")
            }
            _autoApproveRunnable = null
            _autoApproveHandler = null
        }
    }
}

fun confirmBolusRequestBaseNotification(context: Context?, title: String, text: String): NotificationCompat.Builder {
    val notificationChannelId = "Confirm Bolus"

    val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        notificationChannelId,
        context?.getString(R.string.bolus_confirm_bolus) ?: "",
        NotificationManager.IMPORTANCE_HIGH
    ).let {
        it.description = context?.getString(R.string.bolus_confirm_bolus) ?: ""
        it
    }
    notificationManager.createNotificationChannel(channel)

    return NotificationCompat.Builder(
        context,
        notificationChannelId
    )
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.bolus_icon)
        .setTicker(title)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setAutoCancel(true)
}
