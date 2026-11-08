package com.pumppan.controlx2.bolus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jwoglom.controlx2.BolusNotificationBroadcastReceiver
import com.jwoglom.controlx2.MainActivity
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.controlx2.shared.util.shortTime24
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces1000Unit
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse.CurrentBolusStatus
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusAbstractResponse
import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.CancelStatus
import timber.log.Timber

/**
 * 大剂量 V2 进度通知的统一驱动接口。
 * 协调器只依赖这个接口，工厂 [PumppanBolusNotifierFactory] 决定用新实现还是旧实现。
 */
interface BolusProgressNotifier {
    fun onMessage(message: Message)
    fun refresh()
    fun dismiss()
}

/**
 * 旧实现适配器：`USE_NEW=false` 时行为与切换前完全一致（逐次委托，不做任何改造）。
 */
class BolusNotificationsAdapter(
    private val legacy: BolusNotifications,
) : BolusProgressNotifier {
    override fun onMessage(message: Message) = legacy.onMessage(message)
    override fun refresh() = legacy.refresh()
    override fun dismiss() = legacy.dismiss()
}

/**
 * 大剂量 V2 通知的不可变快照。
 *
 * `phase/bolusId/剂量文案/开始与预计结束/connected/可取消/终结态/已关闭` 全部在快照里定格，
 * 渲染是 `snapshot + outcome → content` 的纯函数；驱动只在快照变化时 `notify`。
 */
data class PumppanBolusSnapshot(
    val phase: BolusDeliveryState,
    val bolusId: Int?,
    val doseText: String?,
    val extendedDoseText: String?,
    val startText: String?,
    val endText: String?,
    val connected: Boolean,
    val cancellable: Boolean,
    val terminal: Boolean,
    val dismissed: Boolean,
    /** 已点过一次「取消」，正在等第二次确认；此时不再直接发 `-96`。 */
    val awaitingCancelConfirm: Boolean,
)

/**
 * 快照 → 通知内容（纯函数，不读 Android 资源，只返回资源 id 与文案参数）。
 * 取消二次确认态使用独立的确认文案，其余完全复用 V2 既有内容映射。
 */
internal fun PumppanBolusSnapshot.toContent(outcome: BolusOutcome): BolusNotificationContent? {
    if (awaitingCancelConfirm) {
        return BolusNotificationContent(
            R.string.bolus_v2_notif_cancel_confirm_title,
            R.string.bolus_v2_notif_cancel_confirm_body,
        )
    }
    val deliveryForContent = BolusDelivery(
        state = phase,
        bolusId = bolusId,
        hasImmediateLeg = true,
        startedAtMillis = null,
        expectedEndAtMillis = null,
        lastEvidenceAtMillis = 0L,
        finishedAtMillis = if (terminal) 0L else null,
    )
    return contentFor(
        delivery = deliveryForContent,
        outcome = outcome,
        connected = connected,
        doseText = doseText,
        extendedDoseText = extendedDoseText,
        startText = startText,
        endText = endText,
    )
}

/**
 * 新实现（D1）：
 * - **独立低优先级渠道**：不使用 legacy 的 `Confirm Bolus` / `IMPORTANCE_HIGH`；
 * - **事件驱动**：只在快照变化时渲染，没有每秒 ticker，也没有无条件 `notify`；
 * - **取消二次确认**：第一次点「取消」只进入确认态，第二次点「确认取消」才走既有
 *   `ACTION_V2_CANCEL`（经 `BolusSessionHolder.requestCancel` 节流后发 `-96`）；
 * - **生命周期**：驱动挂在协调器（进程级）上；`enabled()` 为 false 或没有活动会话时
 *   直接停止渲染并撤掉通知，不再有 Activity 销毁后仍刷旧内容的问题。
 *
 * 边界（不要绕过）：`BolusManager.confirmBolusRequest` 在进入任何接收器之前就会发出
 * 「待确认」通知。v2 的 phone 请求由 `CommService` 传 `autoApprove=true`，该入口会跳过
 * 旧通知与旧确认对话框（确认交给 v2 页面），因此 v2 下不会再有「确认」通知；
 * wear / legacy 路径仍走旧链路，二者并存只发生在这些模式下。
 */
class PumppanBolusNotifier(
    context: Context,
    private val enabled: () -> Boolean,
    private val delivery: () -> BolusDelivery?,
    private val outcome: () -> BolusOutcome,
    private val connected: () -> Boolean,
    private val cancelStatus: () -> CancelStatus = { CancelStatus.IDLE },
) : BolusProgressNotifier {
    private val appContext: Context = context.applicationContext

    // 从泵报文里抓到的剂量/时间事实；按 bolusId 切换清空。
    private var factsBolusId: Int? = null
    private var immediateMilliunits: Long? = null
    private var extendedMilliunits: Long? = null
    private var deliveredMilliunits: Long? = null
    private var startTimeText: String? = null

    private var dismissedBolusId: Int? = null
    private var terminalShown = false

    /** 只用于「取消二次确认」：已武装的 bolusId。 */
    private var armedCancelBolusId: Int? = null

    private var hasPosted = false
    private var lastSnapshot: PumppanBolusSnapshot? = null
    private var lastOutcome: BolusOutcome? = null

    init {
        V2NotifierReceiver.target = this
        ensureReceiverRegistered()
        ensureChannel()
    }

    override fun onMessage(message: Message) {
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
                        if (message.timestamp > 0) startTimeText = formatPumpTime(message.timestamp)
                    }
                }

                is ExtendedBolusStatusV2Response -> {
                    if (message.bolusStatus != 0 && message.bolusId != 0) {
                        switchFacts(message.bolusId)
                        extendedMilliunits = message.requestedVolume
                        if (message.timestamp > 0) startTimeText = formatPumpTime(message.timestamp)
                    }
                }

                is LastBolusStatusAbstractResponse -> {
                    val id = message.bolusId
                    if (id != 0 && id == factsBolusId) deliveredMilliunits = message.deliveredVolume
                }

                else -> Unit
            }
        } catch (e: Exception) {
            Timber.w(e, "bolus-v2-notif: 忽略解析失败的通知事实 %s", message.javaClass.simpleName)
        }
        sync()
    }

    override fun refresh() {
        if (!enabled()) return
        sync()
    }

    override fun dismiss() {
        if (!enabled()) return
        dismissedBolusId = delivery()?.bolusId
        armedCancelBolusId = null
        cancelNotification()
        hasPosted = false
    }

    /** 第一次点「取消」：只进入确认态，不发任何泵指令。 */
    internal fun onCancelArmRequested() {
        val current = delivery() ?: return
        armedCancelBolusId = current.bolusId
        // 立即重绘为确认态（快照会变化，但仍走同一渲染入口）。
        lastSnapshot = null
        sync()
    }

    /** 确认态里点「继续输注」：退出确认态，不改会话。 */
    internal fun onCancelArmAborted() {
        armedCancelBolusId = null
        lastSnapshot = null
        sync()
    }

    private fun sync() {
        // V2 关闭即停止：撤掉通知，不再有任何后续刷新。
        if (!enabled()) {
            armedCancelBolusId = null
            hasPosted = false
            lastSnapshot = null
            cancelNotification()
            return
        }

        val current = delivery()
        if (current == null) {
            armedCancelBolusId = null
            lastSnapshot = null
            if (!terminalShown) cancelNotification()
            return
        }

        if (current.bolusId != dismissedBolusId) dismissedBolusId = null
        if (armedCancelBolusId != current.bolusId) armedCancelBolusId = null

        val terminal = current.isFinished
        if (!terminal) terminalShown = false
        if (terminal && current.bolusId == dismissedBolusId) {
            cancelNotification()
            return
        }

        val armed = armedCancelBolusId != null && current.state == BolusDeliveryState.ACTIVE
        val snapshot = PumppanBolusSnapshot(
            phase = current.state,
            bolusId = current.bolusId,
            doseText = doseTextFor(current),
            extendedDoseText = extendedMilliunits?.let { twoDecimalPlaces1000Unit(it) },
            startText = startTimeText,
            endText = current.expectedEndAtMillis?.let(::formatLocalMillis),
            connected = connected(),
            cancellable = current.state == BolusDeliveryState.ACTIVE &&
                current.bolusId != null &&
                cancelStatus() != CancelStatus.SENDING,
            terminal = terminal,
            dismissed = false,
            awaitingCancelConfirm = armed,
        )

        if (snapshot == lastSnapshot && outcome() == lastOutcome) {
            // 快照没变 → 不重绘。这就是「不再每秒 notify」的落点。
            return
        }

        val content = try {
            snapshot.toContent(outcome())
        } catch (e: Exception) {
            Timber.e(e, "bolus-v2-notif: 生成通知内容失败，已忽略")
            null
        } ?: run {
            lastSnapshot = null
            cancelNotification()
            return
        }

        try {
            post(snapshot, content, current)
        } catch (e: Exception) {
            // 通知渲染失败（资源/格式/系统限制）只记日志，不能让启动或消息回调崩溃。
            Timber.e(e, "bolus-v2-notif: 发送通知失败，已忽略")
            return
        }
        lastSnapshot = snapshot
        lastOutcome = outcome()
        hasPosted = true

        if (terminal) {
            terminalShown = true
            armedCancelBolusId = null
        }
    }

    private fun post(
        snapshot: PumppanBolusSnapshot,
        content: BolusNotificationContent,
        current: BolusDelivery,
    ) {
        val title = appContext.getString(content.titleRes)
        val text = if (content.textArgs.isEmpty()) {
            appContext.getString(content.textRes)
        } else {
            appContext.getString(content.textRes, *content.textArgs.toTypedArray<Any>())
        }

        val ongoing = isLiveState(snapshot.phase)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.bolus_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

        if (snapshot.awaitingCancelConfirm) {
            builder.addAction(
                R.drawable.decline,
                appContext.getString(R.string.bolus_v2_notif_action_confirm_cancel),
                legacyCancelPendingIntent(),
            )
            builder.addAction(
                R.drawable.confirm,
                appContext.getString(R.string.bolus_v2_notif_action_keep),
                selfPendingIntent(ACTION_ARM_ABORT, 2041),
            )
        } else if (snapshot.cancellable) {
            builder.addAction(
                R.drawable.decline,
                appContext.getString(R.string.bolus_notif_action_cancel),
                selfPendingIntent(ACTION_ARM_CANCEL, 2040),
            )
        }

        if (!ongoing) {
            builder.addAction(
                R.drawable.confirm,
                appContext.getString(R.string.bolus_notif_action_dismiss),
                selfPendingIntent(ACTION_DISMISS, 2042),
            )
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BolusNotifications.NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.bolus_v2_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.bolus_v2_notif_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureReceiverRegistered() {
        synchronized(RECEIVER_LOCK) {
            if (receiverRegistered) return
            ContextCompat.registerReceiver(
                appContext,
                V2NotifierReceiver,
                IntentFilter().apply {
                    addAction(ACTION_ARM_CANCEL)
                    addAction(ACTION_ARM_ABORT)
                    addAction(ACTION_DISMISS)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun cancelNotification() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(BolusNotifications.NOTIFICATION_ID)
        hasPosted = false
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

    /**
     * 第二次确认才用到既有接收器的 `ACTION_V2_CANCEL`：
     * 它走 `BolusSessionHolder.requestCancel`（节流 / 断连 fail-fast / 不盲取消），
     * 与 App 内取消同一条决策链路。
     */
    private fun legacyCancelPendingIntent(): PendingIntent {
        val intent = Intent(appContext, BolusNotificationBroadcastReceiver::class.java).apply {
            putExtra("action", BolusNotificationBroadcastReceiver.ACTION_V2_CANCEL)
        }
        return PendingIntent.getBroadcast(
            appContext,
            2020,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 本文件内的动态接收器只处理「武装 / 放弃 / 关闭」，不触碰泵指令。 */
    private fun selfPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun doseTextFor(current: BolusDelivery): String? {
        val milliunits = when {
            current.isExtendedRunning -> extendedMilliunits ?: immediateMilliunits
            current.isFinished -> deliveredMilliunits ?: totalMilliunits()
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

    // 通知里的开始 / 预计结束时刻与页面同源：统一走 shortTime24（24 小时制）。
    private fun formatPumpTime(pumpTimestampSeconds: Long): String =
        shortTime24(
            pumpTimeToLocalTz(Dates.fromJan12008EpochSecondsToDate(pumpTimestampSeconds)),
        )

    private fun formatLocalMillis(millis: Long): String =
        shortTime24(java.time.Instant.ofEpochMilli(millis))

    private fun isLiveState(state: BolusDeliveryState): Boolean = when (state) {
        BolusDeliveryState.ACTIVE, BolusDeliveryState.CANCELLING -> true
        else -> false
    }

    /**
     * 动态接收器：`RECEIVER_NOT_EXPORTED`，只在进程内把通知按钮事件转回本实例。
     * 进程级单例（[target] 指向最新协调器的通知器），不做 unregister，避免多 Activity
     * 反复注册/注销造成的事件丢失。
     */
    private object V2NotifierReceiver : BroadcastReceiver() {
        @Volatile
        var target: PumppanBolusNotifier? = null

        override fun onReceive(context: Context?, intent: Intent?) {
            val notifier = target ?: return
            when (intent?.action) {
                ACTION_ARM_CANCEL -> notifier.onCancelArmRequested()
                ACTION_ARM_ABORT -> notifier.onCancelArmAborted()
                ACTION_DISMISS -> notifier.dismiss()
            }
        }
    }

    companion object {
        /** 独立渠道：低优先级，不与 legacy 的 `Confirm Bolus`（IMPORTANCE_HIGH）共用。 */
        const val CHANNEL_ID = "pumppan_bolus_v2"

        private const val ACTION_ARM_CANCEL = "com.pumppan.controlx2.bolus.V2_CANCEL_ARM"
        private const val ACTION_ARM_ABORT = "com.pumppan.controlx2.bolus.V2_CANCEL_ABORT"
        private const val ACTION_DISMISS = "com.pumppan.controlx2.bolus.V2_DISMISS"

        private val RECEIVER_LOCK = Any()
        @Volatile
        private var receiverRegistered = false
    }
}

/**
 * 唯一切换点：协调器只调用 `create(...)`，`USE_NEW=false` 即完全回退到旧实现。
 * 上面的 [PumppanBolusNotifier] 与这里都只做加法，旧 `BolusNotifications` 文件保持原样。
 */
object PumppanBolusNotifierFactory {
    const val USE_NEW = true

    fun create(
        context: Context,
        enabled: () -> Boolean,
        delivery: () -> BolusDelivery?,
        outcome: () -> BolusOutcome,
        connected: () -> Boolean,
        cancelStatus: () -> CancelStatus,
        refreshIntervalMillis: Long,
    ): BolusProgressNotifier = if (USE_NEW) {
        PumppanBolusNotifier(
            context = context,
            enabled = enabled,
            delivery = delivery,
            outcome = outcome,
            connected = connected,
            cancelStatus = cancelStatus,
        )
    } else {
        BolusNotificationsAdapter(
            BolusNotifications(
                context = context,
                enabled = enabled,
                delivery = delivery,
                outcome = outcome,
                connected = connected,
                cancelStatus = cancelStatus,
                refreshIntervalMillis = refreshIntervalMillis,
            )
        )
    }
}
