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
import com.pumppan.controlx2.bolus.state.BolusReconciler
import com.pumppan.controlx2.bolus.state.BolusSession
import com.pumppan.controlx2.bolus.state.BolusSessionController
import com.pumppan.controlx2.bolus.state.BolusSessionHolder
import com.pumppan.controlx2.bolus.state.BolusStatusRequestSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S1/S2 的收敛点：把「消费泵侧大剂量报文 + 冷启动/重连对账」从 `MainActivity` 收进一个类，
 * 使 Activity 只保留一个字段与两处调用（消息转发、连上/重连），且不含任何开关判断。
 *
 * - 消息：44 / 46 / -74 / 48 / -94 / -96 全部交给 [BolusSessionController]；首条消息顺带触发一次对账。
 * - 对账：四步顺序、单步 2s、整体 5s 与 S2 完全一致；只发只读状态请求，不发任何写命令。
 * - 开关：由 [mode] lambda 决定（默认 [BolusControllerMode.LEGACY]，LEGACY 下零请求、零 timer）。
 *   S9 之后由 `BolusRouter.controllerMode(context)` 传入，与设置页开关同源。
 */
class BolusEntryCoordinator(
    private val sendPumpCommands: (SendType, List<Message>) -> Unit,
    private val isPumpConnected: () -> Boolean,
    private val scope: CoroutineScope,
    private val mode: () -> BolusControllerMode = { BolusControllerMode.LEGACY },
    private val tickMillis: Long = RECONCILE_TICK_MILLIS,
    /** 非空时启用大剂量 v2 常驻进度通知（预览/单测传 null 即可关闭）。 */
    private val notificationContext: Context? = null,
) {
    private val controller = BolusSessionController(mode = mode)

    private val notifications: BolusNotifications? = notificationContext?.let { ctx ->
        BolusNotifications(
            context = ctx,
            enabled = { mode() == BolusControllerMode.V2 },
            session = { controller.session },
            outcome = { controller.outcome },
            connected = { isPumpConnected() },
            cancelStatus = { controller.holder.cancelStatus() },
        )
    }

    private val reconciler = BolusReconciler(
        sender = object : BolusStatusRequestSender {
            override fun requestCurrentBolusStatus() {
                sendPumpCommands(SendType.STANDARD, listOf(CurrentBolusStatusRequest()))
            }

            override fun requestExtendedBolusStatus() {
                sendPumpCommands(SendType.STANDARD, listOf(ExtendedBolusStatusRequest()))
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

    /** S4 的读取源（普通可变字段，非可观察类型）。 */
    val holder: BolusSessionHolder get() = controller.holder

    /**
     * S4 的 Compose 桥接：协调器在每次报文/对账推进后刷新该快照状态，
     * UI 侧直接 `val session by coordinator.sessionState` 即可参与重组。
     */
    val sessionState: MutableState<BolusSession?> = mutableStateOf(controller.session)

    /** 结局页读取源：NACK / ALARM / TIMEOUT / DISCONNECTED 等分类由 UI 映射。 */
    val outcomeState: MutableState<com.pumppan.controlx2.bolus.state.BolusOutcome> =
        mutableStateOf(controller.outcome)

    /** 一条泵侧报文：先更新会话，首条消息触发冷启动对账，再喂给对账器。 */
    fun onPumpMessage(message: Message) {
        // Mobi 延展的本地取证日志（未 armed 时直接返回）。
        BolusEvidenceLog.onMessage(message)
        controller.applyMessage(message)
        if (!coldStartReconciled) {
            coldStartReconciled = true
            onPumpConnected()
        }
        reconciler.onMessage(message)
        sessionState.value = controller.session
        outcomeState.value = controller.outcome
        notifications?.onMessage(message)
    }

    /** 连上/重连时做一次只读对账；对账进行中由内部 500ms ticker 推进。 */
    fun onPumpConnected() {
        notifications?.refresh()
        if (mode() != BolusControllerMode.V2) return
        if (reconciler.running) return
        if (!isPumpConnected()) return
        reconciler.start()
        scope.launch {
            while (reconciler.running) {
                delay(tickMillis)
                reconciler.onTick()
                sessionState.value = controller.session
            }
        }
    }

    /** 断连：通知正文改为「已断开」，停止每秒刷新。 */
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

    private companion object {
        const val RECONCILE_TICK_MILLIS = 500L
    }
}

/**
 * UI 侧拿到协调器的唯一入口：`MainActivity` 构造协调器时登记，`Landing` 的 v2 页面只读。
 * 未登记（预览/单测）时为 null，页面按「无会话」渲染。
 */
object BolusRuntime {
    var coordinator: BolusEntryCoordinator? = null
}

