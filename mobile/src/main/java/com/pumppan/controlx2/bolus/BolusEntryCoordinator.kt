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
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BOLUS_SESSION_SCHEMA_VERSION
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusPollScheduler
import com.pumppan.controlx2.bolus.state.BolusSessionLoad
import com.pumppan.controlx2.bolus.state.BolusSessionMeta
import com.pumppan.controlx2.bolus.state.BolusSessionStore
import timber.log.Timber
import com.pumppan.controlx2.bolus.state.BolusReconciler
import com.pumppan.controlx2.bolus.state.BolusSessionController
import com.pumppan.controlx2.bolus.state.BolusSessionHolder
import com.pumppan.controlx2.bolus.state.BolusStatusRequestSender
import com.pumppan.controlx2.bolus.state.PumpCapabilities
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.query.PumpCommandPort
import com.pumppan.controlx2.query.PumpRequestSender
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
    /** 阈值快照：确认阶梯、节流、stale 窗口、请求超时等全部来自 `pump-capabilities.json`。 */
    private val limits = BolusLimits.fromProfile(capabilities())

    private val controller = BolusSessionController(mode = mode, limits = limits)

    /**
     * UI 观察的会话快照。**必须在 [init] 之前初始化**：恢复路径会调用 [pushState]，
     * 若这两个字段晚于 init 声明，Kotlin 的属性初始化顺序会让它们在恢复时为 null。
     *
     * UI 侧直接 `val session by coordinator.sessionState` 即可参与重组。
     */
    val sessionState: MutableState<BolusDelivery?> = mutableStateOf(controller.session)
    val outcomeState: MutableState<BolusOutcome> = mutableStateOf(controller.outcome)

    /** 第二笔（泵上并行的扩展）的独立快照；细条据此渲染第二行并给独立取消。 */
    val extendedState: MutableState<BolusDelivery?> = mutableStateOf(controller.holder.extendedSession)

    /** 会话信封存储；预览/单测不传 Context 时关闭持久化。 */
    private val store: BolusSessionStore? = notificationContext?.let { BolusSessionStore(it) }

    /** 本次发起的输入元数据（shape/剂量/source），只在落盘 single 块时使用。 */
    private var sendMeta: BolusSessionMeta? = null
    private var sendMetaBolusId: Int? = null

    /** 通知器唯一构造点：新/旧实现由 [PumppanBolusNotifierFactory.USE_NEW] 决定。 */
    private val notifications: BolusProgressNotifier? = notificationContext?.let { ctx ->
        PumppanBolusNotifierFactory.create(
            context = ctx,
            enabled = { mode() == BolusControllerMode.V2 },
            delivery = { controller.session },
            outcome = { controller.outcome },
            connected = { isPumpConnected() },
            cancelStatus = { controller.holder.cancelStatus() },
            refreshIntervalMillis = limits.notificationRefreshIntervalMillis,
        )
    }

    /**
     * 查询器出口：大剂量线的只读查询（44 / 46 / 182 / 164）唯一从这里出。
     * 单飞 / 超时 / 节流归 [PumpCommandPort]，本类不再自建第二套在飞状态。
     */
    private val queryPort = PumpCommandPort(
        object : PumpRequestSender {
            override fun send(type: SendType, messages: List<Message>): Boolean {
                sendPumpCommands(type, messages)
                // sendPumpCommands 断连时会自行丢弃；这里回读连接态作为「是否真的下发」。
                return isPumpConnected()
            }
        }
    )

    private val scheduler = BolusPollScheduler(
        port = queryPort,
        currentRequest = { CurrentBolusStatusRequest() },
        extendedRequest = { ExtendedBolusStatusRequest() },
        immediatePollIntervalMs = capabilities().immediatePollIntervalMs,
        extendedPollIntervalMs = capabilities().extendedPollIntervalMs,
        requestTimeoutMs = limits.requestTimeoutMillis,
    )

    private val reconciler = BolusReconciler(
        sender = object : BolusStatusRequestSender {
            override fun requestCurrentBolusStatus() {
                queryPort.request(CurrentBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            }

            override fun requestExtendedBolusStatus() {
                queryPort.request(ExtendedBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            }

            override fun requestExtendedBolusStatusV2() {
                queryPort.request(ExtendedBolusStatusV2Request(), timeoutMs = limits.requestTimeoutMillis)
            }

            override fun requestLastBolusStatus() {
                queryPort.request(LastBolusStatusV2Request(), timeoutMs = limits.requestTimeoutMillis)
            }
        },
        holder = controller.holder,
        mode = mode,
    )

    private var coldStartReconciled = false

    /**
     * 「大剂量」页是否在前台（含大剂量 / 向导 / 预设标签与流程页）。
     *
     * 只门控延展补证（`46` 轮询）：页面不开时不做无谓取证，避免加重 BLE 通信负担。
     * 立即段的 `44` 取证不受此门控 —— 细条与通知依赖它保持新鲜。
     */
    @Volatile
    var bolusPageVisible: Boolean = false

    /** 主 ticker：确认阶梯、延展兜底、查询调度都由它推进；无活跃会话时自动退出。 */
    private var tickerJob: Job? = null

    init {
        // 先恢复，再挂回调：恢复本身不应触发一次「重复落盘」。
        restorePersistedSession()
        controller.holder.onSessionChanged = { delivery ->
            if (mode() == BolusControllerMode.V2) {
                val meta = if (sendMeta != null && delivery?.bolusId == sendMetaBolusId) {
                    sendMeta
                } else {
                    null
                }
                store?.onSessionChanged(delivery, meta)
            }
        }
    }

    /**
     * 进程重启后恢复单笔会话。只读信封，不发送、不取消：
     * - 更高版本 → 视为未知会话，直接退出（信封保留给更高版本处理）；
     * - 已结束 / 超过 [BolusLimits.staleNoBolusMillis] → 清掉，不再展示；
     * - 仍在进行 → 还原快照，由 ticker / 对账继续收敛。
     */
    private fun restorePersistedSession() {
        try {
            restorePersistedSessionInner()
        } catch (e: Exception) {
            // 启动路径的最高优先级约束：任何无法识别 / 无法映射 / 无法还原的落盘状态，
            // 都只能让这一笔会话作废，绝不能让 App 起不来。
            Timber.e(e, "bolus: 恢复落盘会话失败，按作废处理")
            runCatching { controller.holder.clearSession() }
            runCatching { store?.clear() }
            runCatching { pushState() }
        }
    }

    private fun restorePersistedSessionInner() {
        if (mode() != BolusControllerMode.V2) return
        val store = store ?: return
        when (val loaded = store.load()) {
            is BolusSessionLoad.None -> Unit

            is BolusSessionLoad.Unsupported -> Timber.w(
                "bolus: 会话信封版本 %d 高于支持版本 %d，按未知会话处理（不发送/不取消）",
                loaded.schemaVersion,
                BOLUS_SESSION_SCHEMA_VERSION,
            )

            is BolusSessionLoad.Restored -> {
                val single = loaded.envelope.single ?: run {
                    store.clear()
                    return
                }
                val delivery = single.toDelivery() ?: run {
                    store.clear()
                    return
                }
                if (loaded.envelope.schemaVersion < BOLUS_SESSION_SCHEMA_VERSION || delivery.isFinished) {
                    store.clear()
                    return
                }
                // 过渡态（等确认 / 未确认 / 取消中）不还原：这些相位既没有泵侧证据、
                // 也没有可继续推进的起点（尤其是 -94 授权后未发出 -98 的 CONFIRMING），
                // 还原出来就是一个出不去且会再次落盘的卡死屏。
                // 直接丢弃，交给冷启动对账用只读的 44/46/48 重新发现真实会话。
                if (delivery.state == BolusDeliveryState.CONFIRMING ||
                    delivery.state == BolusDeliveryState.UNCONFIRMED ||
                    delivery.state == BolusDeliveryState.CANCELLING
                ) {
                    Timber.w("bolus: 落盘会话停在过渡态 %s，丢弃并交给冷启动对账", delivery.state)
                    store.clear()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - delivery.lastEvidenceAtMillis > limits.staleNoBolusMillis) {
                    store.clear()
                    return
                }
                sendMeta = single.toMeta()
                sendMetaBolusId = delivery.bolusId
                controller.restoreSession(delivery)
                store.markRestored(delivery)
                pushState()
                notifications?.refresh()
                ensureTicker()
            }
        }
    }

    /** S4 的读取源（普通可变字段，非可观察类型）。 */
    val holder: BolusSessionHolder get() = controller.holder

    // 会话 / 结局快照已在类顶部声明（必须早于 init 的属性初始化顺序，见上方注释）。

    /**
     * 本 App 刚发出 `-98`：进入 CONFIRMING 并起算确认阶梯（0–10s / 10–60s / 60s）。
     * 由 `BolusFlowScreen` 在发送成功后调用；LEGACY 模式下状态机直接忽略。
     */
    fun onBolusInitiated(
        bolusId: Int,
        hasImmediateLeg: Boolean,
        expectedEndAtMillis: Long? = null,
        meta: BolusSessionMeta? = null,
    ) {
        if (mode() != BolusControllerMode.V2) return
        try {
            val now = System.currentTimeMillis()
            // 先登记输入元数据，状态机写入会话时才能把 shape/剂量一起落盘。
            sendMeta = meta
            sendMetaBolusId = bolusId
            controller.markInitiateSent(bolusId, hasImmediateLeg, expectedEndAtMillis, now)
            scheduler.reset(now)
            pushState()
            notifications?.refresh()
            ensureTicker()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 登记已发出会话失败，已忽略")
        }
    }

    /** 一条泵侧报文：先销掉在飞请求，再更新会话，首条消息触发冷启动对账，最后喂给对账器。 */
    fun onPumpMessage(message: Message) {
        try {
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
        } catch (e: Exception) {
            // 单条报文解析 / 映射失败（例如库枚举外的 statusTypeId）只记一条日志，
            // 绝不把异常抛到消息线程导致整个进程退出。
            Timber.e(e, "bolus: 处理报文 %s 失败，已忽略", message.javaClass.simpleName)
            runCatching { pushState() }
        }
    }

    /** 连上/重连时做一次只读对账；对账与后续查询由同一个 ticker 推进。 */
    fun onPumpConnected() {
        try {
            notifications?.refresh()
            if (mode() != BolusControllerMode.V2) return
            if (reconciler.running) {
                ensureTicker()
                return
            }
            if (!isPumpConnected()) return
            reconciler.start()
            ensureTicker()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 连接对账启动失败，已忽略")
        }
    }

    /** 断连：通知正文改为「已断开」，查询调度暂停（ticker 仍在跑，重连后自动续上）。 */
    fun onPumpDisconnected() {
        try {
            notifications?.refresh()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 断连刷新通知失败，已忽略")
        }
    }

    /** 通知上的取消动作发出 `-96` 后刷新为「取消中」。 */
    fun refreshNotifications() {
        try {
            notifications?.refresh()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 刷新通知失败，已忽略")
        }
    }

    /** 用户关闭终结态通知，避免同一 bolusId 再次弹出。 */
    fun dismissNotification() {
        try {
            notifications?.dismiss()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 关闭通知失败，已忽略")
        }
    }

    /**
     * 结局页「关闭」：清掉已落定会话 + 复位结局，并**立刻推送 UI**。
     *
     * UI 之前直接调 `holder.clear()`：内存与落盘会话都清了，但没有 `pushState()`，
     * `sessionState` / `outcomeState` 仍是旧快照，`deliveryToPage` 继续返回 DONE，
     * 页面停在「大剂量已完成」，直到下一条泵报文才刷新（真机表现为「点关闭没反应」）。
     */
    fun dismissFinish() {
        try {
            controller.dismissFinished()
            sendMeta = null
            sendMetaBolusId = null
            pushState()
        } catch (e: Exception) {
            Timber.e(e, "bolus: 关闭结局页失败，已忽略")
        }
    }

    private fun pushState() {
        // 兜底：即便未来再有人把状态声明移到 init 之后，恢复路径也不能因此崩溃。
        runCatching {
            sessionState.value = controller.session
            outcomeState.value = controller.outcome
            extendedState.value = controller.holder.extendedSession
        }.onFailure { e ->
            Timber.e(e, "bolus: 推送会话状态失败（状态未就绪），已忽略")
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            var failures = 0
            while (true) {
                delay(tickMillis)
                try {
                    val now = System.currentTimeMillis()
                    val before = controller.session
                    val beforeExtended = controller.holder.extendedSession
                    val wasReconciling = reconciler.running

                    controller.onTick(now)
                    if (wasReconciling) reconciler.onTick()
                    scheduler.tick(
                        controller.session,
                        isPumpConnected(),
                        now,
                        extendedPollAllowed = bolusPageVisible,
                    )

                    val changed = controller.session != before ||
                        controller.holder.extendedSession != beforeExtended ||
                        wasReconciling
                    if (changed) {
                        pushState()
                        notifications?.refresh()
                    }
                    if (wasReconciling && !reconciler.running) {
                        // 对账刚结束：延展兜底从此刻起算，避免立刻重复查询。
                        scheduler.markReconciled(now)
                    }
                    failures = 0

                    if (!reconciler.running && !needsTicker(controller.session)) break
                } catch (e: Exception) {
                    // ticker 抛异常会直接带到线程默认处理器，进而杀掉进程；
                    // 单次推进失败必须就地吞掉。连续失败则退出循环，避免每秒刷异常。
                    failures++
                    Timber.e(e, "bolus: ticker 推进失败（连续第 %d 次）", failures)
                    if (failures >= 3) break
                }
            }
            tickerJob = null
        }
    }

    /**
     * UNKNOWN / CANCELLED / 已完成 / 无会话都不再 tick；
     * CONFIRMING 无论是否已写出 `-98` 都要 tick，否则会退回到没有出口的等待相位。
     */
    private fun needsTicker(delivery: BolusDelivery?): Boolean {
        // 第二笔还在跑时 ticker 不能停：它的取消超时与预计结束兜底都靠这一拍推进。
        if (controller.holder.extendedSession?.takeIf { !it.isFinished } != null) return true
        if (delivery == null || delivery.isFinished) return false
        return when (delivery.state) {
            BolusDeliveryState.CONFIRMING,
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
