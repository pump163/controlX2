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
import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.pumppan.controlx2.query.QueryDomainRequests
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
        // 44 在确认窗内由查询器按 1s 发；调度器这时不再补一条，避免同一秒两发。
        // 46（延展）不交给窗口：它是条件查询，仍归这里按会话形状判断。
        currentPollDelegated = {
            QueryCoordinatorHolder.current()?.isWindowOpen(QueryDomainRequests.BOLUS_OUTCOME) == true
        },
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

    /** 上一次观察到的会话态，用来识别「非活跃 → 活跃 / 活跃 → 取消中」的跃迁。 */
    private var lastObservedState: BolusDeliveryState? = null

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
            // 会话落定（结束 / 清空）就关掉 1s 窗口，别让「确认完了还每秒问一遍」占链路。
            if (delivery == null || delivery.isFinished) {
                QueryCoordinatorHolder.current()?.closeWindow(QueryDomainRequests.BOLUS_OUTCOME)
            }
            // 状态进来了、驱动循环必须跟着起来。外部发起（泵上直接打）与对账把会话推进到
            // ACTIVE/CONFIRMING 时，都不经过 onBolusInitiated，只有这里能保证 ticker 不死：
            // 2026-11-11 实测 22:36:21 落 UNKNOWN 后 ticker 退出，22:47:37 对账已 ACTIVE，
            // 到 22:49:03 取消为止 90s 内没有任何 44 轮询。
            if (mode() == BolusControllerMode.V2 && delivery != null && !delivery.isFinished &&
                needsTicker(delivery)
            ) {
                ensureTicker()
            }
            // 窗口也在这里开：只在「跃迁」上开一次，普通证据刷新不续窗，
            // 否则 2 小时的延展会被续成 1s 拍到底，正好违背「不拼命查」。
            if (mode() == BolusControllerMode.V2 && delivery != null) {
                val previous = lastObservedState
                val nowActive = delivery.state == BolusDeliveryState.ACTIVE ||
                    delivery.state == BolusDeliveryState.CANCELLING
                val wasActive = previous == BolusDeliveryState.ACTIVE ||
                    previous == BolusDeliveryState.CANCELLING
                if (nowActive && !wasActive) {
                    val coordinator = QueryCoordinatorHolder.current()
                    // App 发起时已经开了 60s 确认窗，这里不缩短它；外部发起 / 恢复 / 对账
                    // 走到 ACTIVE 时才补一个短窗，把「这一笔真的在跑」问实。
                    if (coordinator != null &&
                        !coordinator.isWindowOpen(QueryDomainRequests.BOLUS_OUTCOME)
                    ) {
                        val duration = if (delivery.state == BolusDeliveryState.CANCELLING) {
                            CANCEL_CONFIRM_WINDOW_MS
                        } else {
                            ACTIVE_CONFIRM_WINDOW_MS
                        }
                        coordinator.openWindow(
                            QueryDomainRequests.BOLUS_OUTCOME,
                            durationMs = duration,
                        )
                    }
                }
            }
            lastObservedState = delivery?.state
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
                // 恢复出来的「运行中」只有旧快照、没有新鲜证据：给查询器一个短窗口（15s、1s 一次），
                // 尽快把「泵上到底还在不在输」问清楚；之后回调度器的 10s 常规节奏。
                // 走 holder 入口而不是 current()：恢复发生在服务初始化阶段，协调器常常还没 attach，
                // 直接调 current() 会让窗口静默丢失，退化成只能等 10s 调度。
                QueryCoordinatorHolder.openWindow(
                    QueryDomainRequests.BOLUS_OUTCOME,
                    durationMs = RESTORE_WINDOW_MS,
                )
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
            // S4：确认期的 44 交给查询器（1s、≤60s 的窗口），调度器不再自己 10s 发一条。
            QueryCoordinatorHolder.current()?.openWindow(QueryDomainRequests.BOLUS_OUTCOME, now = now)
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

    /** 细条展开时的一次性核对节流窗口；连点只算一次。 */
    private var lastStripStatusRefreshAt = 0L

    /**
     * 进入「大剂量」页时的一次性核对。
     *
     * 现场：进程启动会把上一笔落盘会话恢复成 ACTIVE，若泵上其实早已结束，入口页会先渲染成
     * 「正在输注 / 无法发起新的大剂量」，直到 10s 调度或 15s 恢复窗拿到 `44` 才翻篇。
     * 真机上表现为「切到向导标签拿一次状态、再切回来就正常」，等同于让用户手动补一次查询。
     * 这里在页面出现时补一条只读 `44`，把这段空窗压到一个 RTT；若泵上真在输，回包会照旧
     * 落在 ACTIVE，页面不受影响。
     *
     * 口径：只读、不发送、不取消；无会话 / 已结束 / 未连接都不发；与周期轮询共用端口的
     * 在飞与超时，不新造第二套在飞表。
     */
    fun requestEntryStatusRefresh() {
        try {
            if (mode() != BolusControllerMode.V2) return
            if (!isPumpConnected()) return
            val session = controller.session ?: return
            if (session.isFinished) return
            Timber.d(
                "bolus: 进入大剂量页，补一条只读核对（44 会话=%s id=%s）",
                session.state,
                session.bolusId,
            )
            queryPort.request(CurrentBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            if (controller.holder.extendedSession?.takeIf { !it.isFinished } != null) {
                queryPort.request(ExtendedBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            }
        } catch (e: Exception) {
            Timber.e(e, "bolus: 入口核对失败，已忽略")
        }
    }

    /**
     * 底栏细条「展开取消按钮」时，补一条只读状态查询核对这笔还在不在。
     *
     * 为什么需要：细条显示的是 App 侧会话快照（来自本机事件 + 周期轮询）。当页面不可见、
     * 屏灭静默、或泵上已经结束/取消而恰好在两个轮询周期之间时，快照可能是旧的：细条还写
     * 「正在输注」并给出「取消」。对着一笔已经结束的输注按取消，轻则泵回错误、重则把
     * 提示当成失败原因展示（现场见过 `Bolus Not Stopped` 假警）。
     *
     * 口径（刻意保守）：
     * - **只读**，走 [queryPort]，与周期轮询共用同一个单飞/超时状态，不新造第二套在飞表；
     * - **不阻塞取消**：按钮仍然立即可点；查回来若已结束，会话快照自己推进，细条随之改文案或消失；
     * - 5s 内重复展开只发一次；未连接直接跳过（断连时发出去也只会被丢弃）。
     * - 有第二笔扩展时一并查 `46`：`44` 只描述立即段，单靠它无法证伪纯方波。
     */
    fun requestStripStatusRefresh() {
        try {
            if (mode() != BolusControllerMode.V2) return
            if (!isPumpConnected()) return
            val now = System.currentTimeMillis()
            if (now - lastStripStatusRefreshAt < STRIP_STATUS_REFRESH_MIN_INTERVAL_MS) return
            lastStripStatusRefreshAt = now
            queryPort.request(CurrentBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            if (controller.holder.extendedSession?.takeIf { !it.isFinished } != null) {
                queryPort.request(ExtendedBolusStatusRequest(), timeoutMs = limits.requestTimeoutMillis)
            }
            Timber.d("bolus: 细条展开，已下发一次状态核对（44%s）", if (controller.holder.extendedSession != null) "+46" else "")
        } catch (e: Exception) {
            // 核对失败不影响取消：细条继续用旧快照渲染。
            Timber.e(e, "bolus: 细条状态核对失败，已忽略")
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

        /** 细条展开核对的节流窗口：同一笔 5s 内重复展开不再发请求。 */
        const val STRIP_STATUS_REFRESH_MIN_INTERVAL_MS = 5_000L

        /**
         * 恢复落盘会话后的短取证窗口：15s × 1s，用来把「还在输 / 已结束」问清楚。
         * 比 60s 的确认窗轻，够拿到 44 的当前状态。
         */
        const val RESTORE_WINDOW_MS = 15_000L

        /**
         * 外部发起（泵上直接打）被发现后的取证窗：30s × 1s。
         * 只在「非活跃 → ACTIVE」这一次跃迁上开，之后回调度器 10s，不覆盖整段输注。
         */
        const val ACTIVE_CONFIRM_WINDOW_MS = 30_000L

        /** 取消落定前的确认窗：15s × 1s，够看清 44/46 是否真的归零。 */
        const val CANCEL_CONFIRM_WINDOW_MS = 15_000L
    }
}

/**
 * UI 侧拿到协调器的唯一入口：`MainActivity` 构造协调器时登记，`Landing` 的 v2 页面只读。
 * 未登记（预览/单测）时为 null，页面按「无会话」渲染。
 */
object BolusRuntime {
    var coordinator: BolusEntryCoordinator? = null
}
