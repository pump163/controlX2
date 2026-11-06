package com.pumppan.controlx2.bolus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import com.jwoglom.controlx2.BolusNotificationBroadcastReceiver
import com.jwoglom.controlx2.MainActivity
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.pump.confirmBolusRequestBaseNotification
import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.controlx2.shared.util.shortTime
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces1000Unit
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusAbstractResponse
import com.pumppan.controlx2.bolus.state.BolusError
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusSession
import com.pumppan.controlx2.bolus.state.CancelStatus
import com.pumppan.controlx2.bolus.state.SessionPhase
import timber.log.Timber

/**
 * 大剂量 v2 的常驻进度通知（设计文档 §4.7）。
 *
 * 只读会话快照渲染，不主动向泵发任何请求：输送中 / 延展运行中且连接正常时每秒重绘一次，
 * 结束态（完成 / 停止 / 失败 / 中止）只重绘最后一次后停止 timer，正文停在最终结果。
 * 断连时正文改为「已断开」，不再让用户误以为仍在输注。
 *
 * 与旧通知链的关系：通知 id 独立（[NOTIFICATION_ID]），旧 `BolusManager` 的确认通知不受影响；
 * v2 模式下旧 STATUS_UPDATE 进度通知会被 `BolusNotificationBroadcastReceiver` 跳过，避免双通知。
 */
class BolusNotifications(
    context: Context,
    private val enabled: () -> Boolean,
    private val session: () -> BolusSession?,
    private val outcome: () -> BolusOutcome,
    private val connected: () -> Boolean,
    private val cancelStatus: () -> CancelStatus = { CancelStatus.IDLE },
) {
    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var tickerScheduled = false
    private val tick = object : Runnable {
        override fun run() {
            tickerScheduled = false
            render()
        }
    }

    // 从泵报文里抓到的事实（用于正文里的绝对剂量 U 与时间），随 bolusId 切换清空。
    private var factsBolusId: Int? = null
    private var immediateMilliunits: Long? = null
    private var extendedMilliunits: Long? = null
    private var deliveredMilliunits: Long? = null
    private var startTimeText: String? = null

    /** 已展示过终结态通知的 bolusId；被用户手动关闭后不再重发。 */
    private var terminalBolusId: Int? = null
    private var terminalShown = false
    private var dismissedBolusId: Int? = null

    /** 一条泵报文：抓取剂量/时间事实后重绘。 */
    fun onMessage(message: Message) {
        if (!enabled()) return
        try {
            when (message) {
                is CurrentBolusStatusResponse -> {
                    if (message.status == CurrentBolusStatus.REQUESTING ||
                        message.status == CurrentBolusStatus.DELIVERING
                    ) {
                        if (message.bolusId != 0) {
                            switchFacts(message.bolusId)
                            immediateMilliunits = message.requestedVolume
                            if (startTimeText == null && message.timestamp > 0) {
                                startTimeText = formatPumpTime(message.timestamp)
                            }
                        }
                    }
                }

                is ExtendedBolusStatusResponse -> {
                    if (message.bolusStatus != 0 && message.bolusId != 0) {
                        switchFacts(message.bolusId)
                        extendedMilliunits = message.requestedVolume
                        if (message.timestamp > 0) {
                            startTimeText = formatPumpTime(message.timestamp)
                        }
                    }
                }

                is ExtendedBolusStatusV2Response -> {
                    if (message.bolusStatus != 0 && message.bolusId != 0) {
                        switchFacts(message.bolusId)
                        extendedMilliunits = message.requestedVolume
                        if (message.timestamp > 0) {
                            startTimeText = formatPumpTime(message.timestamp)
                        }
                    }
                }

                is LastBolusStatusAbstractResponse -> {
                    val id = message.bolusId
                    if (id != 0 && id == factsBolusId) {
                        deliveredMilliunits = message.deliveredVolume
                    }
                }

                else -> Unit
            }
        } catch (e: Exception) {
            Timber.w(e, "bolus-notif: 忽略解析失败的通知事实 %s", message.javaClass.simpleName)
        }
        render()
    }

    /** 会话 / 结局 / 连接状态变化时由协调器调用。 */
    fun refresh() {
        if (!enabled()) return
        render()
    }

    /** 通知被用户关闭：不再为同一 bolusId 重发终结态。 */
    fun dismiss() {
        if (!enabled()) return
        dismissedBolusId = session()?.bolusId
        stopTicker()
        cancelNotification()
    }

    private fun render() {
        val current = session()
        if (current == null) {
            stopTicker()
            // 终结态通知要停在最终结果，会话被清空后也不主动撤掉。
            if (!terminalShown) cancelNotification()
            return
        }

        val phase = current.phase
        if (phase == SessionPhase.DRAFT || phase == SessionPhase.PERMITTING) {
            stopTicker()
            terminalShown = false
            terminalBolusId = null
            cancelNotification()
            return
        }

        val terminal = phase == SessionPhase.DONE ||
            phase == SessionPhase.ABORTED ||
            phase == SessionPhase.FAILED
        if (!terminal) {
            // 回到进行中/未知态：上一笔的终结标记失效，通知按当前相位重绘。
            terminalShown = false
            terminalBolusId = null
        }
        if (current.bolusId != dismissedBolusId) dismissedBolusId = null

        if (terminal && current.bolusId == dismissedBolusId) {
            stopTicker()
            return
        }

        val content = contentFor(
            phase = phase,
            outcome = outcome(),
            connected = connected(),
            doseText = doseTextFor(phase),
            extendedDoseText = extendedMilliunits?.let { twoDecimalPlaces1000Unit(it) },
            startText = startTimeText,
            endText = current.expectedEndAtMillis?.let { formatLocalMillis(it) },
        ) ?: run {
            stopTicker()
            cancelNotification()
            return
        }

        val canCancel = (phase == SessionPhase.DELIVERING || phase == SessionPhase.EXTENDED_RUNNING) &&
            current.bolusId != null &&
            cancelStatus() != CancelStatus.SENDING
        post(content, ongoing = isLivePhase(phase), cancellable = canCancel)

        if (terminal) {
            terminalShown = true
            terminalBolusId = current.bolusId
            stopTicker()
        } else if (isLivePhase(phase) && connected()) {
            startTicker()
        } else {
            stopTicker()
        }
    }

    private fun post(content: BolusNotificationContent, ongoing: Boolean, cancellable: Boolean) {
        val title = appContext.getString(content.titleRes)
        val text = if (content.textArgs.isEmpty()) {
            appContext.getString(content.textRes)
        } else {
            appContext.getString(content.textRes, *content.textArgs.toTypedArray<Any>())
        }

        val builder = confirmBolusRequestBaseNotification(appContext, title, text)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (cancellable) {
            builder.addAction(
                R.drawable.decline,
                appContext.getString(R.string.bolus_notif_action_cancel),
                actionPendingIntent(BolusNotificationBroadcastReceiver.ACTION_V2_CANCEL, 2020),
            )
        }
        if (!ongoing) {
            builder.addAction(
                R.drawable.confirm,
                appContext.getString(R.string.bolus_notif_action_dismiss),
                actionPendingIntent(BolusNotificationBroadcastReceiver.ACTION_V2_DISMISS, 2021),
            )
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_BOLUS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            2010,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, BolusNotificationBroadcastReceiver::class.java).apply {
            putExtra("action", action)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun startTicker() {
        if (tickerScheduled) return
        tickerScheduled = true
        handler.postDelayed(tick, REFRESH_INTERVAL_MILLIS)
    }

    private fun stopTicker() {
        handler.removeCallbacks(tick)
        tickerScheduled = false
    }

    private fun doseTextFor(phase: SessionPhase): String? {
        val milliunits = when (phase) {
            SessionPhase.EXTENDED_RUNNING -> extendedMilliunits ?: immediateMilliunits
            SessionPhase.DONE, SessionPhase.ABORTED -> deliveredMilliunits ?: totalMilliunits()
            else -> immediateMilliunits ?: extendedMilliunits
        } ?: return null
        return twoDecimalPlaces1000Unit(milliunits)
    }

    private fun totalMilliunits(): Long? = when {
        immediateMilliunits == null -> extendedMilliunits
        extendedMilliunits == null -> immediateMilliunits
        else -> immediateMilliunits!! + extendedMilliunits!!
    }

    private fun switchFacts(bolusId: Int) {
        if (factsBolusId == bolusId) return
        factsBolusId = bolusId
        immediateMilliunits = null
        extendedMilliunits = null
        deliveredMilliunits = null
        startTimeText = null
    }

    private fun formatPumpTime(pumpTimestampSeconds: Long): String =
        shortTime(
            pumpTimeToLocalTz(Dates.fromJan12008EpochSecondsToDate(pumpTimestampSeconds)),
            appContext.getString(R.string.units_m),
            appContext.getString(R.string.units_am),
            appContext.getString(R.string.units_pm),
        )

    private fun formatLocalMillis(millis: Long): String =
        shortTime(
            java.time.Instant.ofEpochMilli(millis),
            appContext.getString(R.string.units_m),
            appContext.getString(R.string.units_am),
            appContext.getString(R.string.units_pm),
        )

    private fun isLivePhase(phase: SessionPhase): Boolean = when (phase) {
        SessionPhase.DELIVERING, SessionPhase.EXTENDED_RUNNING, SessionPhase.CANCELLING -> true
        else -> false
    }

    companion object {
        const val NOTIFICATION_ID = 3001
        const val REFRESH_INTERVAL_MILLIS = 1000L
    }
}

/** 相位 → 标题/正文（纯函数，单测覆盖；不含任何 Android 资源读取）。 */
data class BolusNotificationContent(
    @StringRes val titleRes: Int,
    @StringRes val textRes: Int,
    val textArgs: List<String> = emptyList(),
)

fun contentFor(
    phase: SessionPhase,
    outcome: BolusOutcome,
    connected: Boolean,
    doseText: String?,
    extendedDoseText: String?,
    startText: String?,
    endText: String?,
): BolusNotificationContent? {
    if (!connected && (phase == SessionPhase.DELIVERING ||
            phase == SessionPhase.EXTENDED_RUNNING ||
            phase == SessionPhase.CANCELLING)
    ) {
        // 断连时不能让用户以为还在输注：正文明确「已断开」。
        return BolusNotificationContent(
            R.string.bolus_notif_title_unknown,
            R.string.bolus_notif_body_unknown_disconnected,
        )
    }

    return when (phase) {
        SessionPhase.DRAFT, SessionPhase.PERMITTING -> null

        SessionPhase.AWAITING_PHONE -> BolusNotificationContent(
            R.string.bolus_notif_title_preparing,
            if (doseText != null) R.string.bolus_notif_body_preparing else R.string.bolus_notif_body_preparing_generic,
            listOfNotNull(doseText),
        )

        SessionPhase.DELIVERING -> BolusNotificationContent(
            R.string.bolus_notif_title_delivering,
            when {
                doseText != null && startText != null -> R.string.bolus_notif_body_delivering
                doseText != null -> R.string.bolus_notif_body_delivering_no_start
                else -> R.string.bolus_notif_body_delivering_generic
            },
            listOfNotNull(doseText, startText),
        )

        SessionPhase.EXTENDED_RUNNING -> BolusNotificationContent(
            R.string.bolus_notif_title_extended,
            when {
                extendedDoseText != null && endText != null -> R.string.bolus_notif_body_extended
                extendedDoseText != null -> R.string.bolus_notif_body_extended_no_end
                else -> R.string.bolus_notif_body_extended_generic
            },
            listOfNotNull(extendedDoseText, endText),
        )

        SessionPhase.CANCELLING -> BolusNotificationContent(
            R.string.bolus_notif_title_cancelling,
            if (doseText != null) R.string.bolus_notif_body_cancelling else R.string.bolus_notif_body_cancelling_generic,
            listOfNotNull(doseText),
        )

        SessionPhase.DONE -> when (outcome) {
            BolusOutcome.Cancelled -> BolusNotificationContent(
                R.string.bolus_notif_title_stopped,
                if (doseText != null) R.string.bolus_notif_body_stopped else R.string.bolus_notif_body_stopped_generic,
                listOfNotNull(doseText),
            )

            is BolusOutcome.Failed -> failedContent(outcome, connected)
            else -> BolusNotificationContent(
                R.string.bolus_notif_title_done,
                if (doseText != null) R.string.bolus_notif_body_done else R.string.bolus_notif_body_done_generic,
                listOfNotNull(doseText),
            )
        }

        SessionPhase.ABORTED -> BolusNotificationContent(
            R.string.bolus_notif_title_stopped,
            if (doseText != null) R.string.bolus_notif_body_aborted else R.string.bolus_notif_body_stopped_generic,
            listOfNotNull(doseText),
        )

        SessionPhase.FAILED -> failedContent(outcome as? BolusOutcome.Failed, connected)

        SessionPhase.UNKNOWN -> BolusNotificationContent(
            R.string.bolus_notif_title_unknown,
            if (!connected) R.string.bolus_notif_body_unknown_disconnected else R.string.bolus_notif_body_unknown,
        )
    }
}

private fun failedContent(failed: BolusOutcome.Failed?, connected: Boolean): BolusNotificationContent =
    BolusNotificationContent(
        R.string.bolus_notif_title_failed,
        when {
            failed?.fromCancel == true -> R.string.bolus_notif_body_failed_cancel
            failed?.error == BolusError.DISCONNECTED || !connected -> R.string.bolus_notif_body_unknown_disconnected
            else -> R.string.bolus_notif_body_failed
        },
    )
