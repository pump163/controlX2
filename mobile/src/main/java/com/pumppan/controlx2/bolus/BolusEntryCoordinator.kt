package com.pumppan.controlx2.bolus

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import android.content.Context
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusV2Request
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.LastBolusStatusV2Request
import com.pumppan.controlx2.bolus.send.BolusEvidenceLog
import com.pumppan.controlx2.bolus.state.BolusControllerMode
import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusPollScheduler
import com.pumppan.controlx2.bolus.state.BolusReconciler
import com.pumppan.controlx2.bolus.state.BolusSessionController
import com.pumppan.controlx2.bolus.state.BolusSessionHolder
import com.pumppan.controlx2.bolus.state.BolusStatusRequestSender
import com.pumppan.controlx2.bolus.state.PumpCapabilities
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S1/S2 的收敛点：把「消费泵侧大剂量报文 + 冷启动/重连对账 + 查询调度」从 `MainActivity`
 * 收进一个类，使 Activity 只保留一个字段与两处调用（消息转发、连上/重连），且不含任何开关判断。
 *
 * - 消息：44 / 46 / -74 / 48 / -94 / -97 / -96 全部交给 [BolusSessionController]；
 *   首条消息顺带触发一次对账。
 * - 对账：四步顺序、单步 2s、整体 5s；只发只读状态请求，不发任何写命令。
 * - 查询调度：[BolusPollScheduler] 负责「同一 opCode 一个在飞、10 秒内不重复发」，
 *   立即段 10 秒一次、延展段 5 分钟兜底（间隔来自 `pump-capabilities.json`）。
 * - 开关：由 [mode] lambda 决定（默认 [BolusControllerMode.LEGACY]，LEGACY 下零请求、零 timer）。
 */
class BolusEntryCoordinator(
    private val sendPumpCommands: (SendType, List<Message>) -> Unit,
    private val isPumpConnected: () -> Boolean,
    private val scope: CoroutineScope,
    private val mode: () -> BolusControllerMode = { BolusControllerMode.LEGACY },
    private val tickMillis: Long = RECONCILE_TICK_MILLIS,
    /** 非空时启用大剂量 v2 常驻进度通知（预览/单测传 null 即可关闭）。 */
    private val notificationContext: Context? = null,
    /** 查询间隔来源；默认读 `assets/pump-capabilities.json` 的 default 档案。 */
    private val capabilities: () -> PumpCapabilityProfile = { PumpCapabilities.forModel(null) },
) {
    private val controller = BolusSessionController(mode = mode)

    private val notifications: BolusNotifications? = notificationContext?.let { ctx ->
        BolusNotifications(
            context = ctx,
            enabled = { mode() == BolusControllerMode.V2 },
            delivery = { controller.session },
            outcome = { controller.outcome },
            connected = { isPumpConnected() },
            cancelStatus = { controller.holder.cancelStatus() },
        )
    }

    private val scheduleRequests: () -> Unit = {
        sendPumpCommands(SendType.STANDARD, listOf(CurrentBolusStatusRequest()))
    }
    private val scheduleExtendedRequests: () -> Unit = {
        sendPumpCommands(SendType.STANDARD, listOf(ExtendedBolusStatusRequest()))
    }

    private val scheduler = BolusPollScheduler(
        requestCurrentBolusStatus = scheduleRequests,
        requestExtendedBolusStatus = scheduleExtendedRequests,
        immediatePollIntervalMs = capabilities().immediatePollIntervalMs,
        extendedPollIntervalMs = capabilities().extendedPollIntervalMs,
    )

    private val reconciler = BolusReconciler(
        sender = object : BolusStatusRequestSender {
            override fun requestCurrentBolusStatus() {
                scheduleRequests()
            }

            override fun requestExtendedBolusStatus() {
                scheduleExtendedRequests()
            }

            override fun requestExtendedBolusStatusV2() {
                sendPumpCommands(SendType.STANDARD, listOf(ExtendedBolusStatusV2Request()))
            }

            override fun requestLastBolusStatus() {
                sendPumpCommands(SendType.STANDARD, listOf(LastBolusStatusV2Request()))
            }
        },
        holder = controller.holder,
        mode = mode,
    )

    private var coldStartReconciled = false

    /** 主 ticker：确认阶梯、延展兜底、查询调度都由它推进；无活跃会话时自动退出。 */
    private var tickerJob: Job? = null

    /** S4 的读取源（普通可变字段，非可观察类型）。 */
    val holder: BolusSessionHolder get() = controller.holder

    /**
     * S4 的 Compose 桥接：协调器在每次报文/对账推进后刷新该快照状态，
     * UI 侧直接 `val session by coordinator.sessionState` 即可参与重组。
     */
    val sessionState: MutableState<BolusDelivery?> = mutableStateOf(controller.session)

    /** 结局页读取源：NACK / ALARM / TIMEOUT / DISCONNECTED 等分类由 UI 映射。 */
    val outcomeState: MutableState<BolusOutcome> = mutableStateOf(controller.outcome)

    /**
     * 本 App 刚发出 `-98`：进入 CONFIRMING 并起算确认阶梯（0–10s / 10–60s / 60s）。
     * 由 `BolusFlowScreen` 在发送成功后调用；LEGACY 模式下状态机直接忽略。
     */
    fun onBolusInitiated(
        bolusId: Int,
        hasImmediateLeg: Boolean,
        expectedEndAtMillis: Long? = null,
    ) {
        if (mode() != BolusControllerMode.V2) return
        val now = System.currentTimeMillis()
        controller.markInitiateSent(bolusId, hasImmediateLeg, expectedEndAtMillis, now)
        scheduler.reset(now)
        pushState()
        notifications?.refresh()
        ensureTicker()
    }

    /** 一条泵侧报文：先销掉在飞请求，再更新会话，首条消息触发冷启动对账，最后喂给对账器。 */
    fun onPumpMessage(message: Message) {
        // Mobi 延展的本地取证日志（未 armed 时直接返回）。
        BolusEvidenceLog.onMessage(message)
        scheduler.onMessage(message)

        val before = controller.session
        controller.applyMessage(message)
        val after = controller.session
        if (before?.bolusId != after?.bolusId) {
            // 新会话（含外部发起）：清掉上一笔的查询节流窗口。
            scheduler.reset(System.currentTimeMillis())
        }

        if (!coldStartReconciled) {
            coldStartReconciled = true
            onPumpConnected()
        }
        reconciler.onMessage(message)
        pushState()
        notifications?.onMessage(message)
        ensureTicker()
    }

    /** 连上/重连时做一次只读对账；对账与后续查询由同一个 ticker 推进。 */
    fun onPumpConnected() {
        notifications?.refresh()
        if (mode() != BolusControllerMode.V2) return
        if (reconciler.running) {
            ensureTicker()
            return
        }
        if (!isPumpConnected()) return
        reconciler.start()
        ensureTicker()
    }

    /** 断连：通知正文改为「已断开」，查询调度暂停（ticker 仍在跑，重连后自动续上）。 */
    fun onPumpDisconnected() {
        notifications?.refresh()
    }

    /** 通知上的取消动作发出 `-96` 后刷新为「取消中」。 */
    fun refreshNotifications() {
        notifications?.refresh()
    }

    /** 用户关闭终结态通知，避免同一 bolusId 再次弹出。 */
    fun dismissNotification() {
        notifications?.dismiss()
    }

    private fun pushState() {
        sessionState.value = controller.session
        outcomeState.value = controller.outcome
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(tickMillis)
                val now = System.currentTimeMillis()
                val before = controller.session
                val wasReconciling = reconciler.running

                controller.onTick(now)
                if (wasReconciling) reconciler.onTick()
                scheduler.tick(controller.session, isPumpConnected(), now)

                val changed = controller.session != before || wasReconciling
                if (changed) {
                    pushState()
                    notifications?.refresh()
                }
                if (wasReconciling && !reconciler.running) {
                    // 对账刚结束：延展兜底从此刻起算，避免立刻重复查询。
                    scheduler.markReconciled(now)
                }

                if (!reconciler.running && !needsTicker(controller.session)) break
            }
            tickerJob = null
        }
    }

    /**
     * CONFIRMING 但尚未发出 `-98`（`startedAtMillis == null`）不需要推进阶梯；
     * UNKNOWN / CANCELLED / 已完成 / 无会话都不再 tick。
     */
    private fun needsTicker(delivery: BolusDelivery?): Boolean {
        if (delivery == null || delivery.isFinished) return false
        return when (delivery.state) {
            BolusDeliveryState.CONFIRMING -> delivery.startedAtMillis != null
            BolusDeliveryState.UNCONFIRMED,
            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            -> true

            BolusDeliveryState.IDLE,
            BolusDeliveryState.UNKNOWN,
            BolusDeliveryState.CANCELLED,
            -> false
        }
    }

    private companion object {
        const val RECONCILE_TICK_MILLIS = 1000L
    }
}

/**
 * UI 侧拿到协调器的唯一入口：`MainActivity` 构造协调器时登记，`Landing` 的 v2 页面只读。
 * 未登记（预览/单测）时为 null，页面按「无会话」渲染。
 */
object BolusRuntime {
    var coordinator: BolusEntryCoordinator? = null
}
