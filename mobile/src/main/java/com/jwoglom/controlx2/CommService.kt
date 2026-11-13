package com.jwoglom.controlx2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.jwoglom.controlx2.db.historylog.HistoryLogDatabase
import com.jwoglom.controlx2.db.historylog.HistoryLogItem
import com.jwoglom.controlx2.shared.enums.DeviceRole
import com.jwoglom.controlx2.db.historylog.HistoryLogRepo
import com.jwoglom.controlx2.messaging.MessageBusFactory
import com.jwoglom.controlx2.presentation.util.ShouldLogToFile
import com.jwoglom.controlx2.presentation.util.cgmQueryAllowed
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import com.jwoglom.controlx2.pump.BleChangeReceiver
import com.jwoglom.controlx2.pump.BolusManager
import com.pumppan.controlx2.query.PumpQueryService
import com.pumppan.controlx2.query.QueryCoordinator
import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.pumppan.controlx2.query.QueryDomainRequests
import com.pumppan.controlx2.query.QueryPolicy
import com.pumppan.controlx2.query.PumpTopic
import com.pumppan.controlx2.query.QueryPolicyLoader
import com.jwoglom.controlx2.pump.CommServiceCallbacks
import com.jwoglom.controlx2.pump.PumpCommHandler
import com.jwoglom.pumpx2.pump.messages.request.control.SetTempRateRequest
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.controlx2.shared.util.canControlPump
import com.jwoglom.controlx2.pump.PumpCommState
import com.jwoglom.controlx2.pump.PumpFinderCommHandler
import com.jwoglom.controlx2.pump.PumpHistoryLogFetcher
import com.jwoglom.controlx2.pump.PumpHistoryLogSyncWorker
import com.jwoglom.controlx2.pump.PumpSession
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncWorker
import com.jwoglom.controlx2.sync.xdrip.XdripMessageDispatcher
import com.jwoglom.controlx2.util.DataClientState
import com.jwoglom.controlx2.util.HistoryLogFetcher
import com.jwoglom.controlx2.util.HistoryLogSyncWorker
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.controlx2.shared.CommServiceCodes
import com.jwoglom.controlx2.shared.FeatureFlags
import com.jwoglom.controlx2.shared.InitiateConfirmedBolusSerializer
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.messaging.MessageBus
import com.jwoglom.controlx2.shared.messaging.MessageBusSender
import com.jwoglom.controlx2.shared.messaging.MessageListener
import com.jwoglom.controlx2.shared.util.registerAppReloadShutdownHook
import com.jwoglom.controlx2.shared.util.setupTimber
import com.jwoglom.controlx2.shared.util.triggerAppReload
import com.jwoglom.controlx2.shared.util.shortTime
import com.jwoglom.controlx2.shared.util.unregisterAppReloadShutdownHook
import com.jwoglom.controlx2.util.AppVersionCheck
import com.pumppan.controlx2.bolus.BolusRouter
import com.pumppan.controlx2.bolus.state.BolusControllerMode
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.bluetooth.Characteristic
import com.jwoglom.pumpx2.pump.messages.builders.CurrentBatteryRequestBuilder
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.models.KnownApiVersion
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.InsulinStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.InsulinStatusResponse
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber
import java.time.Instant
import com.jwoglom.controlx2.R


/**
 * 协调器节拍。5s 足够覆盖最短的 30s 生效周期，也不会把「到期判定」本身变成负担——
 * tick 只做内存计算，SHADOW 阶段不下发任何报文。
 */
private const val QUERY_TICK_MS = 5_000L

/**
 * 窗口期节拍：大剂量确认窗（`BOLUS_OUTCOME` 1s）需要 1s 的调度节拍，
 * 平时仍回 5s，避免屏灭时常驻 1s 唤醒。
 */
private const val QUERY_WINDOW_TICK_MS = 1_000L


class CommService : Service(), CommServiceCallbacks {
    // pumppan: 通知栏文案跟随语言开关（Service 的 base context 不会继承 Application 的包装）
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.pumppan.controlx2.PumppanLocale.wrap(newBase))
    }

    override val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)

    private var serviceLooper: Looper? = null
    private var queryCoordinator: QueryCoordinator? = null
    private var queryTickHandler: Handler? = null
    private var queryTickRunnable: Runnable? = null
    private var pumpCommHandler: PumpCommHandler? = null
    private var pumpFinderCommHandler: PumpFinderCommHandler? = null

    private lateinit var messageBus: MessageBus
    var httpDebugApiService: HttpDebugApiService? = null
        private set

    @androidx.annotation.VisibleForTesting
    internal fun setMessageBusForTesting(bus: MessageBus) {
        messageBus = bus
    }

    @androidx.annotation.VisibleForTesting
    internal fun simulateConnectedPump(peripheral: BluetoothPeripheral) {
        pumpCommHandler!!.simulateConnectedPump(peripheral)
    }

    private lateinit var bolusManager: BolusManager

    // Hook used by triggerAppReload to give pumpx2 a chance to drop the GATT
    // link cleanly before Runtime.exit(0). Held as a field so we can
    // unregister in onDestroy and avoid duplicate registrations on
    // service-restart cycles.
    private val appReloadShutdownHook: (Long) -> Unit = { timeoutMs ->
        try {
            val ok = pumpCommHandler?.shutdownAndAwaitDisconnect(timeoutMs) ?: false
            Timber.i("appReloadShutdownHook: graceful BLE shutdown ok=$ok timeoutMs=$timeoutMs")
        } catch (t: Throwable) {
            Timber.w(t, "appReloadShutdownHook: graceful BLE shutdown threw")
        }
    }

    private var serviceStatusAcknowledged = false
    private val serviceStatusTask = object : Runnable {
        override fun run() {
            if (!serviceStatusAcknowledged) {
                Timber.i("Periodically sending service status (not yet acknowledged)")
                if (Prefs(applicationContext).pumpFinderServiceEnabled()) {
                    sendWearCommMessage(MessagePaths.TO_SERVER_PUMP_FINDER_STARTED, "".toByteArray())
                } else {
                    sendWearCommMessage(MessagePaths.TO_SERVER_COMM_STARTED, "".toByteArray())
                }
                serviceLooper?.let { looper ->
                    Handler(looper).postDelayed(this, 2000)
                }
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    override val pumpCommState = PumpCommState()

    val historyLogDb by lazy { HistoryLogDatabase.getDatabase(this) }
    val historyLogRepo by lazy { HistoryLogRepo(historyLogDb.historyLogDao()) }


    // PumpCommHandler extracted to pump/PumpCommHandler.kt
    // PumpFinderCommHandler extracted to pump/PumpFinderCommHandler.kt

    override fun sendWearCommMessage(path: String, message: ByteArray) {
        Timber.i("service sendMessage: $path ${String(message)}")
        messageBus.sendMessage(path, message, MessageBusSender.COMM_SERVICE)
    }

    // BleChangeReceiver and BondState extracted to pump/BleChangeReceiver.kt
    private var bleChangeReceiver = BleChangeReceiver()

    // 2026-11-12：旧 5min 周期任务已整体并入 QueryCoordinator（见 startQueryObservation）：
    // 电池 → `PUMP_VITALS`、IOB → `IOB`、胰岛素状态 → `PUMP_VITALS`、
    // HistoryLogStatus → `HISTORY`、CGM 探活 → `CGM` 保底域的 300s 一拍。
    // 这里不再保留第二条下发路径，避免「协调器已接管但旧任务还在发」的静默双发。

    fun isPumpReadyForHistoryFetch(): Boolean {
        return pumpCommHandler?.isPumpReadyForHistoryFetch() == true
    }

    fun getPumpSession(): PumpSession? {
        return pumpCommHandler?.currentSession
    }

    private val checkForUpdatesDelayMs: Long = 1000 * 30 // 30 seconds
    private var checkForUpdatesTask: Runnable = Runnable {
        if (Prefs(applicationContext).checkForUpdates()) {
            AppVersionCheck(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Timber already set in up MUA, but for good measure:
        setupTimber("MWC",
            context = this,
            logToFile = true,
            shouldLog = ShouldLogToFile(this),
            writeCharacteristicFailedCallback = handleWriteCharacteristicFailedCallback)
        Timber.i("service onCreate")
        com.jwoglom.controlx2.shared.diag.DiagMonitor.onServiceEvent("SERVICE_CREATE") // DIAG-TEMP

        registerAppReloadShutdownHook(appReloadShutdownHook)

        // The Wear data layer auto-starts CommService whenever a /to-phone or
        // /from-pump message arrives (see AndroidManifest intent-filter). When
        // the phone is in CLIENT mode the watch is the pump-host and CommService
        // must NOT scan/bond — otherwise the OS pairing popup keeps appearing
        // and the pump's bond stays on the phone, blocking watch-side pairing.
        if (Prefs(applicationContext).deviceRole() != DeviceRole.PUMP_HOST) {
            Timber.w("commService is short-circuiting because deviceRole is CLIENT")
            stopSelf()
            return
        }

        // Listen to BLE state changes
        val intentFilter = IntentFilter()
        intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED")
        intentFilter.addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
        registerReceiver(bleChangeReceiver, intentFilter)


        if (!Prefs(applicationContext).tosAccepted()) {
            Timber.w("commService is short-circuiting because first TOS not accepted")
            return
        } else if (!Prefs(applicationContext).serviceEnabled()) {
            Timber.w("commService is short-circuiting because service not enabled")
            return
        }

        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        val handlerThread = HandlerThread("PumpCommServiceThread", Process.THREAD_PRIORITY_FOREGROUND)
        handlerThread.start()
        Timber.d("service thread start")

        // Get the HandlerThread's Looper and use it for our Handler
        serviceLooper = handlerThread.looper

        messageBus = MessageBusFactory.createMessageBus(this)
        messageBus.addMessageListener(object : MessageListener {
            override fun onMessageReceived(path: String, data: ByteArray, sourceNodeId: String) {
                handleMessageReceived(path, data, sourceNodeId)
            }
        })

        bolusManager = BolusManager(applicationContext, ::sendWearCommMessage)

        // Initialize and start HTTP Debug API service
        httpDebugApiService = HttpDebugApiService(applicationContext)
        httpDebugApiService?.getCurrentPumpDataCallback = { getCurrentPumpDataJson() }
        httpDebugApiService?.getCurrentPumpSidCallback = { pumpCommHandler?.getPumpSid() ?: Prefs(applicationContext).currentPumpSid() }
        httpDebugApiService?.sendPumpMessagesCallback = { data -> sendPumpCommMessages(data) }
        httpDebugApiService?.sendRawPumpCommandCallback = { message -> sendPumpCommRawCommand(message) }
        httpDebugApiService?.sendBolusCommandCallback = { message -> sendSignedBolusCommand(message) }
        httpDebugApiService?.sendMessagingCallback = { path, data -> sendWearCommMessage(path, data) }
        httpDebugApiService?.onHistoryLogInsertedCallback = { item -> httpDebugApiService?.onHistoryLogInserted(item) }
        httpDebugApiService?.start()

        if (Prefs(applicationContext).pumpFinderServiceEnabled()) {
            pumpFinderCommHandler = PumpFinderCommHandler(serviceLooper!!, this)
        } else {
            pumpCommHandler = newPumpCommHandler()

            pumpCommHandler?.postDelayed(checkForUpdatesTask, checkForUpdatesDelayMs)
        }


        // Start periodic status sender (will stop when acknowledged)
        serviceLooper?.let { looper ->
            Handler(looper).postDelayed(serviceStatusTask, 500)
        }
    }

    override fun onBind(intent: Intent?) = null

    /**
     * 统一的 handler 构造入口：构造后立刻注入查询策略里的链路阈值。
     *
     * 三个创建点（服务启动 / 结束找泵 / 重新找泵）都走这里，避免某一处忘了注入
     * 而让端口退回 30s 默认值——那正是上一版「配置改了但端口行为没变」的原因。
     */
    private fun newPumpCommHandler(): PumpCommHandler {
        val policy = QueryPolicyLoader.current()
        val states = policy.states
        val handler = PumpCommHandler(serviceLooper!!, this).also {
            it.applyQueryLinkPolicy(
                linkSilentAfterMs = states.linkSilentAfterMs,
                connectSettleMs = states.connectedSettleMs,
            )
            val flap = policy.flap
            it.applyQueryFlapPolicy(
                windowMs = flap.windowMs,
                disconnectsInWindow = flap.disconnectsInWindow,
                shortLivedMs = flap.shortLivedMs,
                shortLivedCount = flap.shortLivedCount,
                stableAfterMs = flap.stableAfterMs,
                quietAfterReconnectMs = flap.quietAfterReconnectMs,
                probeIntervalMs = flap.probeIntervalMs,
            )
        }
        startQueryObservation(handler, policy)
        return handler
    }

    /**
     * S2/S4：把协调器接到真实端口上跑。
     *
     * 三个不能省的点：
     * 1. 端口必须是 handler 里那一个实例——另建一个会得到不同的在飞登记和静默窗，
     *    「被拒/在飞」统计就和真实下发对不上；
     * 2. `activeDomains` 是「有清单可算」的全集，`dispatchedDomains` 才是「真下发」的子集。
     *    两者分开，才能在全局 ACTIVE 下逐域迁移而不双发；
     * 3. 窗口域（`BOLUS_OUTCOME`）只在窗口开着时才发，窗口由大剂量会话开启。
     */
    private fun startQueryObservation(handler: PumpCommHandler, policy: QueryPolicy) {
        stopQueryObservation()
        val coordinator = QueryCoordinator(
            policy = policy,
            port = handler.queryPortForOrchestration(),
            // S4 已全局 ACTIVE：下面的域都真下发；未列出的已登记域只能靠 SHADOW 只算不发。
            mode = QueryCoordinator.Mode.ACTIVE,
            activeDomains = QueryDomainRequests.pollingDomains() + QueryDomainRequests.BOLUS_OUTCOME,
            dispatchedDomains = setOf(
                // 保底域：旧 5min 周期任务并进来之后由它们常驻驱动，页面不再自己发。
                // PUMP_VITALS 60s（电量 V2 / 胰岛素 / 复位时长）、IOB 60s，
                // BASAL / BOLUS_LAST 120s（对应首页第二档的频率），
                // CGM 300s（会话状态，兼作「用户在泵上启用 G6/G7」的探活），
                // CONTROL_IQ 300s（闭环开关）、HISTORY 300s（HistoryLogStatus）。
                "PUMP_VITALS",
                "IOB",
                QueryDomainRequests.BASAL,
                QueryDomainRequests.BOLUS_LAST,
                "CGM",
                "CONTROL_IQ",
                QueryDomainRequests.HISTORY,
                QueryDomainRequests.BOLUS_OUTCOME,
                // S4 第一批：这四域只在对应页面可见时才查（策略里 onlyWhenVisible），
                // 页面改由订阅驱动、删掉自己的 60s 定时器，因此不会与旧路径双发。
                QueryDomainRequests.NOTIFICATIONS,
                QueryDomainRequests.CARTRIDGE,
                QueryDomainRequests.SAFETY_LIMITS,
                QueryDomainRequests.PUMP_GLOBALS,
                // 控制页（暂停 / 运动 / 睡眠 / 临时基础率）：批次与页面原表完全一致。
                QueryDomainRequests.PUMP_STATE,
                // 首页：第二档 120s（底栏状态 / 最大大剂量）、第三档 300s（EGV 图形）；
                // 其余常显项已由保底域接管，页面只保留首帧 / 下拉 / 连接补拉。
                QueryDomainRequests.DASHBOARD_DETAIL,
                QueryDomainRequests.DASHBOARD_CGM,
                QueryDomainRequests.CGM_STATUS,
                QueryDomainRequests.CONTROL_IQ_SETTINGS,
                QueryDomainRequests.PROFILE,
            ),
        )
        queryCoordinator = coordinator

        val looper = serviceLooper ?: return
        val ticker = object : Runnable {
            override fun run() {
                try {
                    coordinator.tick()
                } catch (t: Throwable) {
                    // 调度异常绝不能因为一次异常把服务带崩；坏了就只记日志。
                    Timber.w(t, "query coordinator tick failed")
                }
                val next = if (runCatching { coordinator.hasOpenWindow() }.getOrDefault(false)) {
                    QUERY_WINDOW_TICK_MS
                } else {
                    QUERY_TICK_MS
                }
                queryTickHandler?.postDelayed(this, next)
            }
        }
        queryTickRunnable = ticker
        queryTickHandler = Handler(looper).also { it.postDelayed(ticker, QUERY_TICK_MS) }
        // 页面「进页/切标签立刻查一眼」的请求来自 UI 线程，必须回到这个节拍线程执行，
        // 否则它会与 tick 抢同一批计时表（lastPlannedAt / pendingFrom）。
        // 先登记线程再 attach：attach 会补发先前攒下的 kick 意图，那一刻必须已经在正确线程上。
        QueryCoordinatorHolder.setTicker(queryTickHandler)
        QueryCoordinatorHolder.attach(coordinator)
    }

    private fun stopQueryObservation() {
        queryTickHandler?.let { h ->
            queryTickRunnable?.let { h.removeCallbacks(it) }
        }
        queryTickHandler = null
        queryTickRunnable = null
        queryCoordinator = null
        QueryCoordinatorHolder.detach()
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleMessageReceived(path: String, data: ByteArray, sourceNodeId: String) {
        // Ignore noisy loopback logs for pump-originated broadcasts.
        if (!path.startsWith(MessagePaths.PREFIX_FROM_PUMP)) {
            Timber.d("service messageReceived: $path ${String(data)} from $sourceNodeId")
        }
        httpDebugApiService?.onMessagingReceived(path, data, sourceNodeId)
        when (path) {
            MessagePaths.TO_SERVER_FORCE_RELOAD -> {
                Timber.i("force-reload")
                triggerAppReload(applicationContext)
            }
            MessagePaths.TO_SERVER_RESTART_PUMP_RECONNECT -> {
                Timber.i("restart-pump-reconnect")
                sendRestartPumpReconnect()
            }
            MessagePaths.TO_SERVER_WIZARD_PEER_RESCUED -> {
                if (!com.jwoglom.controlx2.shared.FeatureFlag.enabled(
                        applicationContext,
                        com.jwoglom.controlx2.shared.FeatureFlag.BTHostSwitch,
                    )) {
                    Timber.w("peer-rescued ignored: BTHostSwitch feature flag disabled")
                } else {
                    Timber.i("peer-rescued: peer is taking pump-host, flipping to CLIENT")
                    com.jwoglom.controlx2.util.applyPeerRescueFromService(
                        applicationContext, becomeClient = true,
                    )
                }
            }
            MessagePaths.TO_SERVER_SET_PAIRING_CODE -> {
                Timber.i("set-pairing-code received in service")
            }
            MessagePaths.TO_SERVER_STOP_PUMP_FINDER -> {
                Timber.i("stop-pump-finder")
                sendStopPumpFinderComm()
                if (String(data) == "init_comm") {
                    pumpFinderCommHandler = null
                    pumpCommHandler = newPumpCommHandler()
                    val filterToMac = Prefs(applicationContext).pumpFinderPumpMac().orEmpty()
                    val pairingCodeType = Prefs(applicationContext).pumpFinderPairingCodeType().orEmpty()
                    val pairingCodeTypeEnum = if (pairingCodeType.isNotEmpty())
                        PairingCodeType.fromLabel(pairingCodeType)
                    else
                        PairingCodeType.SHORT_6CHAR
                    Prefs(applicationContext).setPumpFinderServiceEnabled(false)
                    Prefs(applicationContext).setUnbondOnNextCommInitMac(filterToMac)
                    Timber.i("stop-pump-finder-next: filterToMac=$filterToMac pairingCodeType=$pairingCodeType")

                    pumpCommHandler?.postDelayed(checkForUpdatesTask, checkForUpdatesDelayMs)
                    sendInitPumpComm(pairingCodeTypeEnum, filterToMac)
                    sendWearCommMessage(MessagePaths.TO_SERVER_COMM_STARTED, "".toByteArray())
                }
            }
            MessagePaths.TO_SERVER_RESTART_PUMP_FINDER -> {
                Timber.i("restart-pump-finder")
                sendStopPumpFinderComm()
                pumpCommHandler = newPumpCommHandler()
                Prefs(applicationContext).setPumpFinderServiceEnabled(true)
                Timber.i("restart-pump-finder")
                triggerAppReload(applicationContext)
                //sendInitPumpComm()
            }
            MessagePaths.TO_SERVER_CHECK_PUMP_FINDER_FOUND_PUMPS -> {
                Timber.i("check-pump-finder-found-pumps")
                sendCheckPumpFinderFoundPumps()
            }
            MessagePaths.TO_SERVER_REQUEST_SERVICE_STATUS -> {
                Timber.i("request-service-status received, responding with current status")
                if (Prefs(applicationContext).pumpFinderServiceEnabled()) {
                    sendWearCommMessage(MessagePaths.TO_SERVER_PUMP_FINDER_STARTED, "".toByteArray())
                } else {
                    sendWearCommMessage(MessagePaths.TO_SERVER_COMM_STARTED, "".toByteArray())
                }
            }
            MessagePaths.TO_SERVER_REFRESH_HISTORY_LOG_SYNC -> {
                Timber.i("refresh-history-log-sync received")
                pumpCommHandler?.refreshHistoryLogSyncWorker(triggerImmediateSync = true)
            }
            MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS -> {
                // Soft-apply preference changes to the live pump session so the
                // caller does not have to trigger a process-killing app reload
                // (which leaves the BLE link in a "peer vanished" state and
                // breaks the next CONFIRM-mode JPAKE handshake — see post-pair
                // pairing-failure repro). pumpx2 only exposes one-way enables
                // for these statics, so we can move off→on at runtime; off is
                // still left to the legacy reload path.
                val prefs = Prefs(applicationContext)
                if (prefs.insulinDeliveryActions() && !PumpState.actionsAffectingInsulinDeliveryEnabled()) {
                    Timber.i("apply-runtime-prefs: enabling actionsAffectingInsulinDelivery")
                    PumpState.enableActionsAffectingInsulinDelivery()
                }
                if (FeatureFlags.ConnectionSharing && prefs.connectionSharingEnabled()) {
                    if (!PumpState.tconnectAppConnectionSharing) {
                        Timber.i("apply-runtime-prefs: enabling tconnectAppConnectionSharing")
                        PumpState.tconnectAppConnectionSharing = true
                    }
                    if (!PumpState.sendSharedConnectionResponseMessages) {
                        Timber.i("apply-runtime-prefs: enabling sendSharedConnectionResponseMessages")
                        PumpState.sendSharedConnectionResponseMessages = true
                    }
                }
                if (prefs.onlySnoopBluetoothEnabled() && !PumpState.onlySnoopBluetooth) {
                    Timber.i("apply-runtime-prefs: enabling onlySnoopBluetooth")
                    PumpState.onlySnoopBluetooth = true
                }
                // Rebuild the ongoing notification so toggled quick-action buttons
                // (bolus / temp-rate, issue #152) appear or disappear immediately.
                updateNotification()
            }
            MessagePaths.TO_SERVER_SERVICE_STATUS_ACKNOWLEDGED -> {
                Timber.i("service-status acknowledged, stopping periodic sender")
                serviceStatusAcknowledged = true
            }
            MessagePaths.TO_SERVER_STOP_COMM -> {
                Timber.w("stop-comm")
                sendStopPumpComm()
            }
            MessagePaths.TO_SERVER_IS_PUMP_CONNECTED -> {
                sendCheckPumpConnected()
            }
            MessagePaths.TO_PUMP_PAIR -> {
                sendPumpPairingMessage()
            }
            MessagePaths.TO_SERVER_BOLUS_REQUEST_WEAR -> {
                bolusManager.confirmBolusRequest(PumpMessageSerializer.fromBytes(data) as InitiateBolusRequest, BolusManager.BolusRequestSource.WEAR)
            }
            MessagePaths.TO_SERVER_BOLUS_REQUEST_PHONE -> {
                val request = PumpMessageSerializer.fromBytes(data) as InitiateBolusRequest
                // v2：确认已在 v2 确认页完成 → 抑制旧通知 / 旧确认对话框，但发送仍走
                // confirmBolusRequest 内的 INITIATE 签名链路（-98 照旧发出）。
                bolusManager.confirmBolusRequest(
                    request,
                    BolusManager.BolusRequestSource.PHONE,
                    autoApprove = BolusRouter.controllerMode(this) == BolusControllerMode.V2,
                )
            }
            MessagePaths.TO_SERVER_BOLUS_CANCEL -> {
                Timber.i("bolus state cancelled")
                bolusManager.resetBolusPrefs()
            }
            MessagePaths.TO_SERVER_INITIATE_CONFIRMED_BOLUS -> {
                // removed: initialized check
                val secretKey = prefs(this)?.getString("initiateBolusSecret", "") ?: ""
                val confirmedBolus =
                    InitiateConfirmedBolusSerializer.fromBytes(secretKey, data)

                val messageOk = confirmedBolus.left
                val initiateMessage = confirmedBolus.right
                if (!messageOk) {
                    Timber.e("invalid message -- blocked signature $messageOk $initiateMessage")
                    sendWearCommMessage(MessagePaths.TO_CLIENT_BLOCKED_BOLUS_SIGNATURE,
                        "CommService".toByteArray()
                    )
                    NotificationCompat.Builder(this)
                        .setContentTitle(this.getString(R.string.comm_bolus_blocked))
                        .setContentText(this.getString(R.string.comm_bolus_mess_blocked_invalid))
                        .build()
                    return
                }

                Timber.i("sending confirmed bolus request: $initiateMessage")
                sendPumpCommBolusMessage(data)
            }
            MessagePaths.TO_SERVER_WRITE_CHARACTERISTIC_FAILED_CALLBACK -> {
                Timber.i("writeCharacteristicFailedCallback from message")
                handleWriteCharacteristicFailedCallback(String(data))
            }
            MessagePaths.TO_PUMP_COMMAND -> {
                sendPumpCommMessage(data)
            }
            MessagePaths.TO_PUMP_COMMANDS -> {
                sendPumpCommMessages(data)
            }
            MessagePaths.TO_PUMP_DEBUG_COMMANDS -> {
                sendPumpCommDebugMessages(data)
            }
            MessagePaths.TO_PUMP_COMMANDS_BUST_CACHE -> {
                sendPumpCommMessagesBustCache(data)
            }
            MessagePaths.TO_PUMP_CACHED_COMMANDS -> {
                handleCachedCommandsRequest(data)
            }
            MessagePaths.TO_PUMP_DEBUG_MESSAGE_CACHE -> {
                handleDebugGetMessageCache()
            }
            MessagePaths.TO_PUMP_DEBUG_HISTORYLOG_CACHE -> {
                handleDebugGetHistoryLogCache()
            }
            MessagePaths.TO_PUMP_DEBUG_CLEAR_HISTORYLOG_CACHE -> {
                handleDebugClearHistoryLogCache()
            }
            MessagePaths.TO_PUMP_DEBUG_WRITE_BT_CHARACTERISTIC -> {
                sendDebugWriteBtCharacteristic(data)
            }
        }
    }

    // --- Preference accessors ---
    override fun prefAutoFetchHistoryLogs() = Prefs(applicationContext).autoFetchHistoryLogs()
    override fun prefConnectionSharingEnabled() = Prefs(applicationContext).connectionSharingEnabled()
    override fun prefOnlySnoopBluetoothEnabled() = Prefs(applicationContext).onlySnoopBluetoothEnabled()
    override fun prefInsulinDeliveryActions() = Prefs(applicationContext).insulinDeliveryActions()
    override fun prefQualifyingEventToastsEnabled() = Prefs(applicationContext).qualifyingEventToastsEnabled()
    override fun prefGlucoseUnit(): GlucoseUnit? = Prefs(applicationContext).glucoseUnit()
    override fun prefSetPumpModelName(name: String) { Prefs(applicationContext).setPumpModelName(name) }
    override fun prefSetCurrentPumpSid(sid: Int) { Prefs(applicationContext).setCurrentPumpSid(sid) }
    override fun prefPumpFinderPumpMac(): String? = Prefs(applicationContext).pumpFinderPumpMac()
    override fun prefUnbondOnNextCommInitMac(): String? = Prefs(applicationContext).unbondOnNextCommInitMac()
    override fun prefSetUnbondOnNextCommInitMac(mac: String?) { Prefs(applicationContext).setUnbondOnNextCommInitMac(mac) }

    // --- Sync/dispatch callbacks ---
    override fun onPumpConnectedSync(pumpSid: Int) {
        val ctx = applicationContext
        NightscoutSyncWorker.startIfEnabled(ctx, ctx.getSharedPreferences("controlx2", Context.MODE_PRIVATE), pumpSid)
    }

    private val xdripMessageDispatcher by lazy { XdripMessageDispatcher(applicationContext) }

    override fun dispatchExternalMessage(message: com.jwoglom.pumpx2.pump.messages.Message) {
        xdripMessageDispatcher.onReceiveMessage(message)
    }

    override fun updateComplicationData(key: String, value: String, timestamp: Instant) {
        val state = DataClientState(applicationContext)
        val pair = Pair(value, timestamp)
        when (key) {
            "pumpBattery" -> state.pumpBattery = pair
            "pumpIOB" -> state.pumpIOB = pair
            "pumpCartridgeUnits" -> state.pumpCartridgeUnits = pair
            "pumpCurrentBasal" -> state.pumpCurrentBasal = pair
            "cgmReading" -> state.cgmReading = pair
        }
    }

    // --- Debug API callbacks ---
    override fun onPumpMessageReceived(message: com.jwoglom.pumpx2.pump.messages.Message, source: SendType) {
        httpDebugApiService?.onPumpMessageReceived(message, source = source)
    }

    override fun onPumpCriticalError(error: TandemError, source: SendType) {
        httpDebugApiService?.onPumpCriticalError(error, source = source)
    }

    // --- Bolus response broadcasting ---
    override fun broadcastBolusInitiateResponse(responseBytes: ByteArray) {
        val intent = android.content.Intent(applicationContext, BolusNotificationBroadcastReceiver::class.java).apply {
            putExtra("action", "INITIATE_RESPONSE")
            putExtra("response", responseBytes)
        }
        applicationContext.sendBroadcast(intent)
    }

    override fun broadcastBolusStatusUpdate(statusBytes: ByteArray) {
        val intent = android.content.Intent(applicationContext, BolusNotificationBroadcastReceiver::class.java).apply {
            putExtra("action", "STATUS_UPDATE")
            putExtra("status", statusBytes)
        }
        applicationContext.sendBroadcast(intent)
    }

    // --- History log factories ---
    override fun createHistoryLogFetcher(
        pumpSid: Int,
        pumpSession: PumpSession,
        autoFetchEnabled: () -> Boolean,
    ): PumpHistoryLogFetcher {
        return HistoryLogFetcher(
            historyLogRepo = historyLogRepo,
            pumpSid = pumpSid,
            pumpSession = pumpSession,
            autoFetchEnabled = autoFetchEnabled,
            broadcastCallback = { item -> httpDebugApiService?.onHistoryLogInsertedCallback?.invoke(item) }
        )
    }

    override fun createHistoryLogSyncWorker(requestSync: () -> Unit): PumpHistoryLogSyncWorker {
        return HistoryLogSyncWorker(requestSync = requestSync)
    }

    override fun markConnectionTime() {
        currentPumpData.connectionTime = Instant.now()
    }

    override fun showToast(text: String, duration: Int) {
        Toast.makeText(this, text, duration).show()
    }

    override fun getWearPrefs(): SharedPreferences? {
        return getSharedPreferences("WearX2", MODE_PRIVATE)
    }

    private var started = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("CommService onStartCommand $intent $flags $startId")

        if (started) {
            Timber.w("CommService onStartCommand when already running, ignoring")
            return START_STICKY
        }

        started = true
        com.jwoglom.controlx2.shared.diag.DiagMonitor.onServiceEvent("SERVICE_START") // DIAG-TEMP
        Toast.makeText(this, this.getString(R.string.comm_ctrl_x2_service_starting), Toast.LENGTH_SHORT).show()

        updateNotification("Initializing...")

        Timber.i("CommService onStartCommand has pumpFinderCommHandler=${pumpFinderCommHandler} pumpCommHandler=${pumpCommHandler}")

        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job
        if (Prefs(applicationContext).pumpFinderServiceEnabled()) {
            Timber.i("Starting CommService in PumpFinder mode")
            sendInitPumpFinderComm()
        } else {
            val pairingCodeType = Prefs(applicationContext).pumpFinderPairingCodeType().orEmpty()
            val pairingCodeTypeEnum = if (!pairingCodeType.isEmpty())
                PairingCodeType.fromLabel(pairingCodeType)
            else
                PairingCodeType.SHORT_6CHAR
            val filterToMac = Prefs(applicationContext).pumpFinderPumpMac().orEmpty()
            Timber.i("Starting CommService in standard mode: filterToMac=$filterToMac pairingCodeType=$pairingCodeType")

            sendInitPumpComm(pairingCodeTypeEnum, filterToMac)
        }

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    private fun startForegroundWrapped(id: Int, notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(id, notification)
            }
            com.jwoglom.controlx2.shared.diag.DiagMonitor.onFgs(true) // DIAG-TEMP
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to start foreground service: missing connectedDevice permissions")
            Toast.makeText(
                this,
                this.getString(R.string.comm_ctrl_x2_needs_bt_server_start),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            false
        }
    }


    override fun updateNotification(statusText: String?) {
        if (statusText != null) {
            currentPumpData.statusText = statusText
        }
        var notification = createNotification()
        startForegroundWrapped(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.w("CommService onTaskRemoved")
        com.jwoglom.controlx2.shared.diag.DiagMonitor.onServiceEvent("ON_TASK_REMOVED") // DIAG-TEMP
        com.jwoglom.controlx2.shared.diag.DiagMonitor.onAppExit("onTaskRemoved") // DIAG-TEMP
        triggerAppReload(applicationContext)
        Toast.makeText(this, this.getString(R.string.comm_ctrl_x2_service_removed), Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private data class DisplayablePumpData(
        var statusText: String = "Initializing...",
        var connectionTime: Instant? = null,
        var lastMessageTime: Instant? = null,
        var batteryPercent: Int? = null,
        var batteryCharging: Boolean? = null,
        var iobUnits: Double? = null,
        var cartridgeRemainingUnits: Int? = null,
    )

    private val currentPumpData: DisplayablePumpData = DisplayablePumpData()

    private fun getCurrentPumpDataJson(): String {
        return """{"statusText":"${currentPumpData.statusText}","connectionTime":"${currentPumpData.connectionTime}","lastMessageTime":"${currentPumpData.lastMessageTime}","batteryPercent":${currentPumpData.batteryPercent},"batteryCharging":${currentPumpData.batteryCharging},"iobUnits":${currentPumpData.iobUnits},"cartridgeRemainingUnits":${currentPumpData.cartridgeRemainingUnits}}"""
    }

    override fun updateNotificationWithPumpData(message: com.jwoglom.pumpx2.pump.messages.Message) {
        var changed = false
        when (message) {
            is CurrentBatteryAbstractResponse -> {
                changed = currentPumpData.batteryPercent != message.batteryPercent
                currentPumpData.batteryPercent = message.batteryPercent
                if (message is CurrentBatteryV2Response) {
                    changed = currentPumpData.batteryCharging != message.isCharging || changed
                    currentPumpData.batteryCharging = message.isCharging
                }
            }
            is ControlIQIOBResponse -> {
                changed = currentPumpData.iobUnits != InsulinUnit.from1000To1(message.pumpDisplayedIOB)
                currentPumpData.iobUnits = InsulinUnit.from1000To1(message.pumpDisplayedIOB)
            }
            is InsulinStatusResponse -> {
                changed = currentPumpData.cartridgeRemainingUnits != message.currentInsulinAmount
                currentPumpData.cartridgeRemainingUnits = message.currentInsulinAmount
            }
        }

        if (changed) {
            currentPumpData.lastMessageTime = Instant.now()
            updateNotification()
        }
    }

    private fun sendInitPumpFinderComm() {
        pumpFinderCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.INIT_PUMP_FINDER_COMM.ordinal
            pumpFinderCommHandler?.sendMessage(msg)
        }
    }

    private fun sendStopPumpFinderComm() {
        pumpFinderCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.STOP_PUMP_FINDER_COMM.ordinal
            pumpFinderCommHandler?.sendMessage(msg)
        }
    }

    private fun sendCheckPumpFinderFoundPumps() {
        pumpFinderCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.CHECK_PUMP_FINDER_FOUND_PUMPS.ordinal
            pumpFinderCommHandler?.sendMessage(msg)
        }
    }

    private fun sendInitPumpComm(pairingCodeType: PairingCodeType, filterToBluetoothMac: String) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.INIT_PUMP_COMM.ordinal
            if (filterToBluetoothMac.length > 0) {
                msg.obj = "${pairingCodeType.label} $filterToBluetoothMac"
            } else {
                msg.obj = "${pairingCodeType.label}"
            }
            pumpCommHandler?.sendMessage(msg)
        }
    }
    
    private fun sendCheckPumpConnected() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.CHECK_PUMP_CONNECTED.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }

    /** 「继续」：用户要求重新开始自动重连（30 分钟提示后的人工干预入口）。 */
    private fun sendRestartPumpReconnect() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.RESTART_PUMP_RECONNECT.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun sendPumpPairingMessage() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_PAIRING_MESSAGE.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }
    private fun sendPumpCommMessage(pumpMsgBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_COMMAND.ordinal
            msg.obj = pumpMsgBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }
    override fun sendPumpCommMessages(pumpMsgBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_COMMANDS_BULK.ordinal
            msg.obj = pumpMsgBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }
    private fun sendPumpCommDebugMessages(pumpMsgBytes: ByteArray) {
        try {
            val messages = PumpMessageSerializer.fromBulkBytes(pumpMsgBytes)
            synchronized(pumpCommState.debugPromptResponseCounts) {
                messages.forEach {
                    val key = Pair(it.characteristic, it.responseOpCode)
                    pumpCommState.debugPromptResponseCounts[key] = (pumpCommState.debugPromptResponseCounts[key] ?: 0) + 1
                }
            }
            Timber.i("Registered ${messages.size} debug-prompt request(s) for stream tagging")
        } catch (e: Exception) {
            Timber.w(e, "Failed to register debug-prompt requests")
        }
        sendPumpCommMessages(pumpMsgBytes)
    }
    private fun sendPumpCommMessagesBustCache(pumpMsgBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_COMMANDS_BUST_CACHE_BULK.ordinal
            msg.obj = pumpMsgBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }
    private fun handleCachedCommandsRequest(rawBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.CACHED_PUMP_COMMANDS_BULK.ordinal
            msg.obj = rawBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun consumeDebugPromptResponse(characteristic: Characteristic, opCode: Byte): Boolean {
        return pumpCommState.consumeDebugPromptResponse(characteristic, opCode)
    }
    private fun sendPumpCommBolusMessage(initiateConfirmedBolusBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_COMMAND_BOLUS.ordinal
            msg.obj = initiateConfirmedBolusBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }

    /**
     * Debug-only raw delivery used by POST /api/pump/raw-command. Delivers the
     * already-constructed Message object (so classes that PumpMessageSerializer
     * cannot round-trip still work) through a code that skips isBolusCommand
     * filtering but keeps Pump.command()'s signature/encryption/connection checks.
     */
    private fun sendPumpCommRawCommand(message: com.jwoglom.pumpx2.pump.messages.Message) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.SEND_PUMP_COMMAND_RAW_UNSAFE.ordinal
            msg.obj = message
            pumpCommHandler?.sendMessage(msg)
        } ?: Timber.w("sendPumpCommRawCommand: pumpCommHandler not initialized")
    }

    /**
     * Signs the message with the same HMAC secret BolusManager uses and delivers it
     * via SEND_PUMP_COMMAND_BOLUS, i.e. the production bolus channel including
     * the prefInsulinDeliveryActions check in PumpCommHandler.
     */
    private fun sendSignedBolusCommand(message: com.jwoglom.pumpx2.pump.messages.Message) {
        val secretKey = getWearPrefs()?.getString("initiateBolusSecret", "") ?: ""
        val signed = InitiateConfirmedBolusSerializer.toBytes(secretKey, message)
        if (signed == null) {
            Timber.e("sendSignedBolusCommand: failed to serialize/sign $message")
            return
        }
        sendPumpCommBolusMessage(signed)
    }

    private val handleWriteCharacteristicFailedCallback: (String) -> Unit = { uuid ->
        Timber.i("handleWriteCharacteristicFailedCallback($uuid)")
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.WRITE_CHARACTERISTIC_FAILED_CALLBACK.ordinal
            msg.obj = uuid
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun handleDebugGetMessageCache() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.DEBUG_GET_MESSAGE_CACHE.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun handleDebugGetHistoryLogCache() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.DEBUG_GET_HISTORYLOG_CACHE.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun handleDebugClearHistoryLogCache() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.DEBUG_CLEAR_HISTORYLOG_CACHE.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun sendDebugWriteBtCharacteristic(jsonBlobBytes: ByteArray) {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.DEBUG_WRITE_BT_CHARACTERISTIC.ordinal
            msg.obj = jsonBlobBytes
            pumpCommHandler?.sendMessage(msg)
        }
    }

    private fun sendStopPumpComm() {
        pumpCommHandler?.obtainMessage()?.also { msg ->
            msg.what = CommServiceCodes.STOP_PUMP_COMM.ordinal
            pumpCommHandler?.sendMessage(msg)
        }
    }


    companion object {
        fun cancelAutoApproveStatic(context: Context?) {
            BolusManager.cancelAutoApproveStatic(context)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.jwoglom.controlx2.shared.diag.DiagMonitor.onServiceEvent("SERVICE_DESTROY") // DIAG-TEMP
        unregisterAppReloadShutdownHook(appReloadShutdownHook)
        stopQueryObservation()
        pumpCommHandler?.stopHistoryLogSyncWorker()
        scope.cancel()
        messageBus.close()
        httpDebugApiService?.stop()
        Toast.makeText(this, this.getString(R.string.comm_ctrl_x2_service_destroyed), Toast.LENGTH_SHORT).show()
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "ControlX2 Background Notification"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        val channel = NotificationChannel(
            notificationChannelId,
            this.getString(R.string.comm_endless_service_notice_channel),
            NotificationManager.IMPORTANCE_NONE
        ).let {
            it.description = this.getString(R.string.comm_endless_service_channel)
            it.setShowBadge(false)
            it.lockscreenVisibility = 0

            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE)
            }

        val builder: NotificationCompat.Builder = NotificationCompat.Builder(
            this,
            notificationChannelId
        )

        var title = "${currentPumpData.statusText}"
        var atTime = currentPumpData.lastMessageTime
        currentPumpData.connectionTime?.let {
            if (atTime == null || it.isAfter(atTime)) atTime = it
        }
        atTime?.let {
            title += this.getString(R.string.comm_at_stime, shortTime(it))
        }

        var contentText = ""
        if (currentPumpData.batteryPercent != null) {
            contentText += this.getString(R.string.comm_battery, currentPumpData.batteryPercent)
        }
        if (currentPumpData.iobUnits != null) {
            contentText += this.getString(R.string.comm_iob_u, currentPumpData.iobUnits)
        }
        if (currentPumpData.cartridgeRemainingUnits != null) {
            contentText += this.getString(R.string.comm_cartridge_u, currentPumpData.cartridgeRemainingUnits)
        }
        currentPumpData.connectionTime?.let {
            contentText += this.getString(R.string.comm_conn_established_at_stime, shortTime(it))
        }

        builder
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, false)
            .setSmallIcon(IconCompat.createWithResource(this, R.drawable.pump_notif_1d))
            .setTicker(currentPumpData.statusText)
            .setPriority(NotificationCompat.PRIORITY_MAX) // for under android 26 compatibility
            .setOngoing(true)

        // Optional quick-action buttons that deep-link into the bolus / temp-rate
        // entry screens (issue #152). Each is gated by its own preference and reuses
        // MainActivity's exported ACTION_OPEN_* deep links.
        if (Prefs(applicationContext).showBolusNotificationButton()) {
            builder.addAction(
                R.drawable.bolus_icon, getString(R.string.dashboard_bolus),
                openActivityAction(MainActivity.ACTION_OPEN_BOLUS, 2010)
            )
        }
        // 临时基础率只有 Mobi 能远程操作（X2 控制页只能看），X2 上不显示按钮
        val canTempRate = canControlPump(
            Prefs(applicationContext).pumpModelName()?.let { determinePumpModel(it) },
            SetTempRateRequest::class.java
        )
        if (Prefs(applicationContext).showTempRateNotificationButton() && canTempRate) {
            builder.addAction(
                R.drawable.bolus_icon_secondary, getString(R.string.temp_basal_temp_rate),
                openActivityAction(MainActivity.ACTION_OPEN_TEMP_RATE, 2011)
            )
        }

        return builder.build()
    }

    /**
     * Builds a PendingIntent that opens MainActivity for the given deep-link action
     * (e.g. MainActivity.ACTION_OPEN_BOLUS). SINGLE_TOP ensures an already-foreground
     * activity receives it via onNewIntent() instead of spawning a duplicate.
     */
    private fun openActivityAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun prefs(context: Context): SharedPreferences? {
        return context.getSharedPreferences("WearX2", MODE_PRIVATE)
    }

}
