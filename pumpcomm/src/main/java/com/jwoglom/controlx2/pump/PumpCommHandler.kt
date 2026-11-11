package com.jwoglom.controlx2.pump

import androidx.annotation.VisibleForTesting
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Context
import java.util.concurrent.CountDownLatch
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.widget.Toast
import com.jwoglom.controlx2.pumpcomm.R
import com.jwoglom.controlx2.shared.CommServiceCodes
import com.jwoglom.controlx2.shared.FeatureFlags
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.diag.DiagMonitor // DIAG-TEMP
import com.jwoglom.controlx2.shared.InitiateConfirmedBolusSerializer
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.util.extractPumpSid
import com.pumppan.controlx2.query.PumpCommandPort
import com.pumppan.controlx2.query.PumpQueryService
import com.pumppan.controlx2.query.PumpReadOnlyQueries
import com.pumppan.controlx2.query.PumpRequestSender
import com.pumppan.controlx2.query.PumpTopic
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemConfig
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Packetize
import com.jwoglom.pumpx2.pump.messages.bluetooth.Characteristic
import com.jwoglom.pumpx2.pump.messages.bluetooth.CharacteristicUUID
import com.jwoglom.pumpx2.pump.messages.bluetooth.PumpStateSupplier
import com.jwoglom.pumpx2.pump.messages.bluetooth.ServiceUUID
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ApiVersionRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractPumpChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentEGVGuiDataResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HistoryLogStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.InsulinStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.TimeSinceResetResponse
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLogStreamResponse
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent
import com.jwoglom.pumpx2.shared.Hex
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.ConnectionPriority
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import timber.log.Timber
import java.security.Security
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

const val CacheSeconds = 30

/**
 * 出口层「同 opCode 在飞」的超时窗口。
 *
 * 必须**小于**大剂量调度器的查询周期（`pump-capabilities.json` 里 immediate 10 000 ms），
 * 否则两层超时等长：外层在 T 发送、内层在 T+Δ 记账，下一周期 `now - (T+Δ) < 10 000`
 * 会被内层判 IN_FLIGHT 而静默丢掉这次查询。留 2 秒余量后，跨周期必放行。
 */
private const val QUERY_IN_FLIGHT_TIMEOUT_MS = 8_000L

/**
 * 批量下发的最小间隔。
 *
 * 16 条首页批请求如果连发，会在几十毫秒内全塞进 blessed 写队列；链路在距离极限时
 * 一旦开始连续写失败，10s 内累计 3 次就会触发剪线重连，正是「越抖越重发」的来源。
 * 200ms 让一批 16 条铺开约 3 秒，单条失败也不会在同一个失败窗口里叠满 3 次。
 */
private const val BULK_SEND_GAP_MS = 200L

/**
 * 蓝牙链路策略（诊断包 2026-11-11 实测后调整，见 docs/bug/断连诊断包分析-20261111.md）。
 *
 * 阈值不再写死在这里，改由 `assets/ble-link-policy.json`（可被
 * `files/ble-link-policy.json` 覆盖）提供，见 [BleLinkPolicy]。理由：这些值是按
 * 机型/固件行为定的，Mobi 真机数据还没到手，写死等于每改一次都要重编。
 *
 * - reassert：开启连接参数复位。泵在连上约 15s 后会把链路降到省电档
 *   （supervision timeout 2s），这是 15–17s 掉线簇的最可能来源。
 *   driver=library 走库实现（10s 周期 + 参数被改坏时事件驱动复检 + 泵忙豁免）；
 *   driver=app 走 App 自建定时器（周期可配，代价是拿不到库的忙判据）。
 * - writeFailure.windowMs / cutThreshold：非授权特征写失败不再一刀剪线，
 *   同一 uuid 在窗口内累计到阈值才判定为链路故障并剪线。
 * - writeFailure.readCooldownMs：一次写失败后暂停**只读查询**的发送窗口；
 *   控制 / 签名 / 取消类不冷却，保证胰岛素相关操作不被拖延。
 */

/**
 * Handler for pump communication. Manages the TandemPump connection lifecycle,
 * message routing, response caching, and bolus command handling.
 *
 * Extracted from CommService's inner class to enable independent testing.
 */
class PumpCommHandler(
    looper: Looper,
    private val callbacks: CommServiceCallbacks
) : Handler(looper) {
    private lateinit var pump: Pump
    private lateinit var tandemBTHandler: TandemBluetoothHandler
    var currentSession: PumpSession? = null
        internal set

    /**
     * 查询层收敛 · 唯一出口（进程内唯一实例，由本 handler 持有）。
     *
     * 只读请求在这里过「同 opCode 单飞」；控制 / 签名 / 取消类仍直发，
     * 判定见 [PumpReadOnlyQueries]。发送器每次取当前 session，断连时返回 false。
     */
    private val queryPort: PumpCommandPort by lazy {
        PumpCommandPort(
            sender = PumpRequestSender { _, messages ->
                messages.firstOrNull()?.let { dispatchDirect(it) } ?: false
            },
            // 先用兜底值；策略由 mobile 侧在构造后立刻注入（pumpcomm 不能依赖 mobile 的 QueryPolicy）。
            linkSilentAfterMs = queryLinkSilentAfterMs,
        )
    }

    /** `states.linkSilentAfterMs`：策略注入前保持端口默认 30s。 */
    @Volatile
    private var queryLinkSilentAfterMs: Long = PumpCommandPort.DEFAULT_LINK_SILENT_MS

    /** `states.connectedSettleMs`：重连后握手静默窗；0 表示不设窗。 */
    @Volatile
    private var queryConnectSettleMs: Long = 0L

    /**
     * 注入查询策略里的链路相关阈值（由 mobile 侧 `QueryPolicyLoader` 提供）。
     *
     * 之所以用参数而不是读配置：`pumpcomm` 模块看不到 mobile 模块的 `QueryPolicy`，
     * 而策略值又必须只有一份来源。
     */
    fun applyQueryLinkPolicy(linkSilentAfterMs: Long, connectSettleMs: Long) {
        queryLinkSilentAfterMs = linkSilentAfterMs
        queryConnectSettleMs = connectSettleMs
        queryPort.setLinkSilentAfterMs(linkSilentAfterMs)
    }

    /**
     * S5：注入 `flap` 段（边界抖动判定阈值）。
     *
     * 与链路静默阈值同理，`pumpcomm` 看不到 mobile 的 `QueryPolicy`，只能由 mobile 侧
     * 在构造后立刻把这一组值传进来；漏传时端口用设计 §14 的默认值，不会退化成「不判抖动」。
     */
    fun applyQueryFlapPolicy(
        windowMs: Long,
        disconnectsInWindow: Int,
        shortLivedMs: Long,
        shortLivedCount: Int,
        stableAfterMs: Long,
        quietAfterReconnectMs: Long,
        probeIntervalMs: Long,
    ) {
        queryPort.configureFlap(
            PumpCommandPort.FlapConfig(
                windowMs = windowMs,
                disconnectsInWindow = disconnectsInWindow,
                shortLivedMs = shortLivedMs,
                shortLivedCount = shortLivedCount,
                stableAfterMs = stableAfterMs,
                quietAfterReconnectMs = quietAfterReconnectMs,
                probeIntervalMs = probeIntervalMs,
            )
        )
    }

    /**
     * 链路策略：首次使用时从 `files/` 覆盖文件或 assets 加载，进程内缓存。
     * 加载失败一律回退内建默认值（与改动前的常量完全一致），不会阻断启动。
     */
    private val linkPolicy: BleLinkPolicy.Values by lazy {
        BleLinkPolicy.current(callbacks.getApplicationContext())
    }

    /**
     * 真实下发（不含「发不发」的决定）。只读查询经端口调用它，直发路径也用它。
     * 调用方需已完成 null / 无外设 / 断连 / 无会话 守卫；这里再兜一次 session。
     */
    private fun dispatchDirect(message: com.jwoglom.pumpx2.pump.messages.Message): Boolean {
        val session = currentSession ?: return false
        PumpQueryService.noteWireRequest(
            opCode = message.opCode().toInt() and 0xFF,
            simpleName = message.javaClass.simpleName,
        )
        Timber.i("Pump send command: $message")
        DiagMonitor.onTx(message.javaClass.simpleName) // DIAG-TEMP
        PumpQueryService.noteInFlight(true)
        return try {
            runBlocking { session.sendCommand(message) }
            true
        } catch (t: Throwable) {
            Timber.w(t, "Pump send failed: $message")
            false
        } finally {
            PumpQueryService.noteInFlight(false)
        }
    }

    /**
     * 供 mobile 侧构造 `QueryCoordinator` 用。
     *
     * 必须复用同一个实例：端口内部维护单飞登记和静默窗，另建一个等于把链路状态拆成
     * 两份，SHADOW 观测到的「在飞/被拒」就和真实下发对不上。
     */
    fun queryPortForOrchestration(): PumpCommandPort = queryPort

    /**
     * 批量下发错峰。
     *
     * 原来是一次 forEach 连着 `pump.command()`，16 条首页批请求会在几十毫秒内全塞进
     * blessed 的写队列。实测写失败 10s 内累计 3 次就会剪连接，而链路在距离极限时
     * 恰恰是最容易连续失败的场景——所以批量本身要按 200ms 节拍铺开，而不是靠
     * 事后重试。
     *
     * 第一条立即发（交互请求不能凭空慢 200ms），其余的按序号延迟；每次执行前重新
     * 判一遍断连/bolus 守卫，因为排到队尾时连接状态可能已经变了。
     */
    private fun dispatchBulkPaced(
        messages: List<com.jwoglom.pumpx2.pump.messages.Message>,
        prepare: (com.jwoglom.pumpx2.pump.messages.Message) -> Unit = {},
    ) {
        messages.forEachIndexed { index, message ->
            val action = Runnable {
                try {
                    if (isBolusCommand(message)) {
                        Timber.e("bulk dispatch blocked bolus command: $message")
                        return@Runnable
                    }
                    if (!pumpConnectedPrecondition()) return@Runnable
                    prepare(message)
                    // 走端口而不是直发：批量错峰只解决「一次塞太密」，挡不住两条批量链各发一遍。
                    // 端口负责在飞上限与窗口去重，这里只负责让节奏慢下来。
                    val result = queryPort.request(
                        message = message,
                        timeoutMs = QUERY_IN_FLIGHT_TIMEOUT_MS,
                    )
                    if (result.sent) {
                        Timber.i("pumpCommHandler send command (paced): $message")
                    } else {
                        Timber.d("paced query suppressed: op=${result.opCode} reason=${result.reject}")
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "bulk dispatch failed: $message")
                }
            }
            val delayMs = index * BULK_SEND_GAP_MS
            if (delayMs <= 0L) action.run() else postDelayed(action, delayMs)
        }
    }

    private var historyLogFetcher: PumpHistoryLogFetcher? = null
    private var historyLogSyncWorker: PumpHistoryLogSyncWorker? = null

    fun getPumpSid(): Int? {
        return if (this::pump.isInitialized) pump.pumpSid else null
    }

    fun isPumpReadyForHistoryFetch(): Boolean {
        return currentSession?.isActive == true
    }

    /**
     * Tear down any live BLE pump connection, blocking up to [timeoutMs] for
     * the GATT-level disconnect callback to fire.
     *
     * Used by the graceful path in [com.jwoglom.controlx2.shared.util.AppReload]
     * so that callers which kill the process (toggling the device role,
     * Nightscout config, etc.) don't leave the pump in a stale "peer vanished"
     * state — the next reconnect would otherwise renegotiate to a high-latency
     * link and silently drop the JPAKE3 message, manifesting as a spurious
     * "pairing code was invalid" error after the very next pair.
     *
     * Returns `true` if a disconnect callback fired in time, `false` if there
     * was nothing to disconnect or the timeout elapsed first.
     */
    fun shutdownAndAwaitDisconnect(timeoutMs: Long): Boolean {
        if (!this::pump.isInitialized) return false
        val peripheral = pump.lastPeripheral ?: return false
        Timber.i("PumpCommHandler.shutdownAndAwaitDisconnect: cancelling ${peripheral.name}")
        val latch = CountDownLatch(1)
        pump.shutdownLatch = latch
        try {
            peripheral.cancelConnection()
        } catch (t: Throwable) {
            Timber.w(t, "shutdownAndAwaitDisconnect: cancelConnection threw")
            pump.shutdownLatch = null
            return false
        }
        return try {
            val ok = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) Timber.w("shutdownAndAwaitDisconnect: timed out after ${timeoutMs}ms")
            ok
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pump.shutdownLatch = null
        }
    }

    @VisibleForTesting
    fun simulateConnectedPump(peripheral: BluetoothPeripheral) {
        pump.initializeConnection(peripheral)
    }

    fun stopHistoryLogSyncWorker() {
        historyLogSyncWorker?.stop()
        historyLogSyncWorker = null
    }

    fun refreshHistoryLogSyncWorker(triggerImmediateSync: Boolean = false) {
        val worker = historyLogSyncWorker
        if (worker == null) {
            Timber.i("refreshHistoryLogSyncWorker skipped: worker not initialized")
            return
        }

        if (!callbacks.prefAutoFetchHistoryLogs()) {
            Timber.i("refreshHistoryLogSyncWorker stopping worker")
            worker.stop()
            return
        }

        Timber.i("refreshHistoryLogSyncWorker starting worker")
        worker.start()
        if (triggerImmediateSync) {
            worker.triggerImmediateSync()
        }
    }

    private fun requestHistoryLogStatusUpdate() {
        if (!callbacks.prefAutoFetchHistoryLogs()) {
            return
        }
        callbacks.sendPumpCommMessages(
            PumpMessageSerializer.toBulkBytes(
                listOf(com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest())
            )
        )
    }

    private fun ensurePumpUnbondedForFreshInit(filterToBluetoothMac: String?): Boolean {
        val ctx = callbacks.getApplicationContext()
        val targetMac = (filterToBluetoothMac ?: callbacks.prefPumpFinderPumpMac().orEmpty())
            .trim()
            .uppercase()
        val scheduledUnbondMac = callbacks.prefUnbondOnNextCommInitMac().orEmpty().trim().uppercase()

        if (scheduledUnbondMac.isEmpty()) {
            Timber.i("init_pump_comm: skipping unbond (not scheduled for this init)")
            return true
        }

        if (targetMac.isEmpty()) {
            Timber.i("init_pump_comm: skipping unbond (no target MAC)")
            return true
        }

        if (scheduledUnbondMac != targetMac) {
            Timber.i("init_pump_comm: skipping unbond for $targetMac (scheduled for $scheduledUnbondMac)")
            return true
        }

        callbacks.prefSetUnbondOnNextCommInitMac(null)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Timber.w("init_pump_comm: cannot unbond $targetMac because BluetoothAdapter is null")
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Timber.w("init_pump_comm: cannot unbond $targetMac without BLUETOOTH_CONNECT permission")
            return true
        }

        val bondedDevice = adapter.bondedDevices
            ?.firstOrNull { it.address.equals(targetMac, ignoreCase = true) }
        if (bondedDevice == null) {
            Timber.i("init_pump_comm: target $targetMac not currently bonded")
            return true
        }

        try {
            val removeBondMethod = bondedDevice.javaClass.getMethod("removeBond")
            val didRequestUnbond = removeBondMethod.invoke(bondedDevice) as? Boolean ?: false
            Timber.i("init_pump_comm: requested unbond for ${bondedDevice.name} ($targetMac), result=$didRequestUnbond")
        } catch (e: SecurityException) {
            Timber.w(e, "init_pump_comm: missing permission while requesting unbond for $targetMac")
        } catch (e: Exception) {
            Timber.w(e, "init_pump_comm: failed unbond request for $targetMac")
        }

        Thread.sleep(500)
        val stillBonded = adapter.bondedDevices
            ?.any { it.address.equals(targetMac, ignoreCase = true) } == true
        if (!stillBonded) {
            Timber.i("init_pump_comm: target $targetMac unbonded successfully")
            return true
        }

        val details = "${bondedDevice.name ?: "Unknown Tandem device"}=$targetMac"
        Timber.w("init_pump_comm: target is still bonded after unbond attempt: $details")
        callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_BONDED_NEEDS_MANUAL_UNBOND, details.toByteArray())
        callbacks.showToast(
            callbacks.getApplicationContext()
                .getString(R.string.pump_err_bonded_manual_unbond),
            Toast.LENGTH_LONG
        )
        return false
    }

    private inner class Pump(var tandemConfig: TandemConfig) : TandemPump(callbacks.getApplicationContext(), tandemConfig) {
        private val scope = CoroutineScope(SupervisorJob(parent = callbacks.supervisorJob) + Dispatchers.IO)
        var lastPeripheral: BluetoothPeripheral? = null
        var isConnected = false
        var pumpSid: Int? = null
        /** 写失败分级：按 uuid 记录最近失败时刻（handler 线程独占访问，无需加锁）。 */
        private val writeFailureTimes = ArrayDeque<Pair<String, Long>>()
        /** 只读查询的写冷却截止时刻（elapsedRealtime）；控制类命令不受影响。 */
        @Volatile private var readQueryCooldownUntil = 0L
        // Set by [shutdownAndAwaitDisconnect] so that the next disconnect
        // callback can wake the waiter on the calling thread (typically the
        // main thread during triggerAppReload's shutdown hook).
        @Volatile var shutdownLatch: CountDownLatch? = null

        init {
            // 连接参数复位：泵连上约 15s 后降到省电档（timeout 2s），不复位就会出现
            // 「连上 15–17s 必掉」的 code 8 簇。驱动与周期由 ble-link-policy.json 决定。
            val policy = linkPolicy
            DiagMonitor.setLinkPolicy(BleLinkPolicy.snapshot(policy)) // DIAG-TEMP
            when {
                !policy.reassertEnabled ->
                    Timber.i("Connection priority re-assert disabled by ble-link-policy")

                policy.driver == BleLinkPolicy.Driver.APP -> Timber.i(
                    "Connection priority re-assert: app driver %dms (skipWhenBusy=%b); " +
                        "library timer NOT enabled, so no event-driven recheck",
                    policy.intervalMs, policy.skipWhenBusy,
                )

                else -> {
                    enablePeriodicConnectionPriorityReassert()
                    Timber.i(
                        "Connection priority re-assert: library driver (10s period, " +
                            "event-driven recheck + busy skip included)"
                    )
                }
            }
            if (FeatureFlags.ConnectionSharing && callbacks.prefConnectionSharingEnabled()) {
                enableTconnectAppConnectionSharing()
                enableSendSharedConnectionResponseMessages()
            }
            if (callbacks.prefOnlySnoopBluetoothEnabled()) {
                Timber.i("ONLY SNOOP BLUETOOTH ENABLED")
                onlySnoopBluetoothAndBlockAllPumpX2Functionality()
            }

            if (callbacks.prefInsulinDeliveryActions()) {
                Timber.i("ACTIONS AFFECTING INSULIN DELIVERY ENABLED")
                enableActionsAffectingInsulinDelivery()
            } else {
                Timber.i("Actions affecting insulin delivery are disabled")
            }
            Timber.i("Pump init")
        }

        /**
         * 记录一次非授权特征写失败，返回该 uuid 在 [windowMs] 窗口内的累计次数（含本次）。
         * 仅在 handler 线程调用（WRITE_CHARACTERISTIC_FAILED_CALLBACK 分支），无需加锁。
         */
        fun noteWriteFailure(uuid: String, nowElapsed: Long, windowMs: Long): Int {
            while (writeFailureTimes.isNotEmpty() &&
                nowElapsed - writeFailureTimes.first().second > windowMs
            ) {
                writeFailureTimes.removeFirst()
            }
            writeFailureTimes.addLast(Pair(uuid, nowElapsed))
            return writeFailureTimes.count { it.first == uuid }
        }

        /** 写失败后的只读查询冷却窗口；控制 / 签名 / 取消类命令不经过这里。 */
        fun startReadQueryCooldown(durationMs: Long) {
            readQueryCooldownUntil = SystemClock.elapsedRealtime() + durationMs
        }

        fun isReadQueryCooling(): Boolean = SystemClock.elapsedRealtime() < readQueryCooldownUntil

        // ------------------------------------------------------------------
        // 连接参数复位 · App 驱动（仅 driver=app 时启动；默认走库实现）
        // ------------------------------------------------------------------

        private val appReassertHandler = Handler(Looper.getMainLooper())
        private var appReassertTask: Runnable? = null

        /**
         * 自建周期复位：周期由 `ble-link-policy.json` 的 `reassert.intervalMs` 决定。
         *
         * 与库实现的差别（写在这里，免得以后当成 bug 排查）：
         * - 拿不到库的「历史下载 / 装药流程」忙判据，只按大剂量标志判断；
         * - 不启用库定时器，因此也失去 `onConnectionUpdated` 里那次事件驱动复检
         *   （参数被改坏后最多等一个周期才拉回）。
         * 因此这里只作为调参/取证用的可选项，默认仍是 `driver=library`。
         */
        private fun startAppReassertIfNeeded() {
            val policy = linkPolicy
            if (!policy.reassertEnabled || policy.driver != BleLinkPolicy.Driver.APP) return
            stopAppReassert()
            val task = object : Runnable {
                override fun run() {
                    val peripheral = lastPeripheral
                    if (!isConnected || peripheral == null) return
                    val busy = policy.skipWhenBusy && runCatching {
                        PumpStateSupplier.inProgressBolusId?.get() != null
                    }.getOrDefault(false)
                    if (busy) {
                        Timber.d("AppReassert: skipping, bolus in progress")
                    } else {
                        runCatching { peripheral.requestConnectionPriority(ConnectionPriority.HIGH) }
                            .onFailure { Timber.w(it, "AppReassert: requestConnectionPriority failed") }
                    }
                    appReassertHandler.postDelayed(this, policy.intervalMs)
                }
            }
            appReassertTask = task
            appReassertHandler.postDelayed(task, policy.intervalMs)
        }

        private fun stopAppReassert() {
            appReassertTask?.let { appReassertHandler.removeCallbacks(it) }
            appReassertTask = null
        }

        override fun onReceiveMessage(
            peripheral: BluetoothPeripheral?,
            message: com.jwoglom.pumpx2.pump.messages.Message?
        ) {
            message?.let {
                DiagMonitor.onRx(it.javaClass.simpleName) // DIAG-TEMP
                // 查询层收敛：响应到达 → 清除对应请求 opCode 的在飞状态
                queryPort.onMessage(it)
                // 查询层收敛 · 第 0 步：只读计数
                PumpQueryService.noteLegacyReply(
                    topic = PumpTopic.fromResponseSimpleName(it.javaClass.simpleName),
                    opCode = it.opCode().toInt(),
                )
                callbacks.pumpCommState.lastResponseMessage.put(Pair(it.characteristic, it.opCode()), Pair(it, Instant.now()))
                val source = if (callbacks.pumpCommState.consumeDebugPromptResponse(it.characteristic, it.opCode())) {
                    com.jwoglom.controlx2.shared.util.SendType.DEBUG_PROMPT
                } else {
                    com.jwoglom.controlx2.shared.util.SendType.STANDARD
                }
                callbacks.onPumpMessageReceived(it, source = source)
            }
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_RECEIVE_MESSAGE, PumpMessageSerializer.toBytes(message))

            message?.let {
                if (it is CurrentBatteryAbstractResponse ||
                    it is ControlIQIOBResponse ||
                    it is CurrentEGVGuiDataResponse
                ) {
                    callbacks.sendWearCommMessage(MessagePaths.TO_CLIENT_SERVICE_RECEIVE_MESSAGE, PumpMessageSerializer.toBytes(message))
                }
            }

            when (message) {
                is TimeSinceResetResponse -> onReceiveTimeSinceResetResponse(message)
                is InitiateBolusResponse -> onReceiveInitiateBolusResponse(message)
                is CurrentBolusStatusResponse -> onReceiveCurrentBolusStatusResponse(message)
                is BolusPermissionResponse -> {
                    PumpStateSupplier.inProgressBolusId = Supplier { message.bolusId }
                }
                is CurrentBatteryAbstractResponse -> callbacks.updateComplicationData("pumpBattery", "${message.batteryPercent}", Instant.now())
                is ControlIQIOBResponse -> callbacks.updateComplicationData("pumpIOB", "${InsulinUnit.from1000To1(message.pumpDisplayedIOB)}", Instant.now())
                is InsulinStatusResponse -> callbacks.updateComplicationData("pumpCartridgeUnits", "${message.currentInsulinAmount}", Instant.now())
                is CurrentBasalStatusResponse -> callbacks.updateComplicationData("pumpCurrentBasal", "${InsulinUnit.from1000To1(message.currentBasalRate)}", Instant.now())
                is CurrentEGVGuiDataResponse -> callbacks.updateComplicationData("cgmReading", "${message.cgmReading}", Instant.now())
                is HistoryLogStatusResponse -> {
                    Timber.i("HistoryLogStatusResponse: $message")
                    scope.launch {
                        Timber.i("HistoryLogStatusResponse: launching in scope $message")
                        historyLogFetcher?.onStatusResponse(message, scope)
                    }
                }
                is HistoryLogStreamResponse -> {
                    message.historyLogs.forEach {
                        Timber.i("HISTORY-LOG-MESSAGE(${it.sequenceNum}): $it")
                        callbacks.pumpCommState.historyLogCache[it.sequenceNum] = it
                        scope.launch {
                            historyLogFetcher?.onStreamResponse(it)
                        }
                    }
                }
            }

            message?.let { callbacks.dispatchExternalMessage(it) }
            message?.let { callbacks.updateNotificationWithPumpData(it) }
        }

        override fun onReceiveQualifyingEvent(
            peripheral: BluetoothPeripheral?,
            events: MutableSet<QualifyingEvent>?
        ) {
            Timber.i("onReceiveQualifyingEvent: $events")
            if (callbacks.prefQualifyingEventToastsEnabled()) {
                callbacks.showToast("Events: $events", Toast.LENGTH_SHORT)
            }
            if (events != null && QualifyingEvent.PUMP_COMMUNICATIONS_SUSPENDED in events) {
                Timber.w("onReceiveQualifyingEvent: PUMP_COMMUNICATIONS_SUSPENDED — pausing sends")
                queryPort.markLinkDown(recordFlap = false)
                currentSession?.pauseSends(currentSession?.rateLimitConfig?.commSuspendedPauseMs ?: 5_000)
            }
            // S5：边界抖动期事件补查一律丢弃，不排队、不延后。
            // 排队只会在回稳瞬间倾泻一串请求，正好撞上库的初始化序列；
            // 事件本身仍转发给手机/手表（UI 需要知道发生了什么），只是不再追问泵。
            val edge = queryPort.linkState() == PumpCommandPort.LinkState.EDGE
            if (edge) {
                Timber.i("onReceiveQualifyingEvent: EDGE，跳过补查（events=%s）", events)
            } else {
                events?.forEach { event ->
                    event.suggestedHandlers.forEach { handler ->
                        Timber.i("onReceiveQualifyingEvent: running handler for $event message: ${handler.get()}")
                        handler.get()?.let { command(it) }
                    }
                }
            }
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_RECEIVE_QUALIFYING_EVENT, com.jwoglom.controlx2.shared.PumpQualifyingEventsSerializer.toBytes(events))
        }

        var pairingCodeCentralChallenge: AbstractCentralChallengeResponse? = null
        override fun onWaitingForPairingCode(
            peripheral: BluetoothPeripheral?,
            centralChallengeResponse: AbstractCentralChallengeResponse?
        ) {
            pairingCodeCentralChallenge = centralChallengeResponse
            DiagMonitor.onAwaitUser("waiting_pairing_code") // DIAG-TEMP
            val hasSavedPairingCode = !PumpState.getPairingCode(context).isNullOrBlank()
            if (!hasSavedPairingCode) {
                val challengeBytes = if (centralChallengeResponse != null) {
                    PumpMessageSerializer.toBytes(centralChallengeResponse)
                } else {
                    byteArrayOf()
                }
                Timber.i("onWaitingForPairingCode: no saved pairing code, waiting for user input")
                callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_MISSING_PAIRING_CODE, challengeBytes)
                return
            }
            Timber.i("onWaitingForPairingCode: saved pairing code found, auto-pairing")
            performPairing(peripheral, centralChallengeResponse, false)
        }

        override fun onInvalidPairingCode(
            peripheral: BluetoothPeripheral?,
            resp: AbstractPumpChallengeResponse?
        ) {
            DiagMonitor.onAwaitUser("invalid_pairing_code") // DIAG-TEMP
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_INVALID_PAIRING_CODE, "".toByteArray())
            super.onInvalidPairingCode(peripheral, resp)
        }

        fun performPairing(
            peripheral: BluetoothPeripheral?,
            centralChallengeResponse: AbstractCentralChallengeResponse?,
            manuallyTriggered: Boolean
        ) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            }
            Security.addProvider(BouncyCastleProvider())

            Timber.i("performPairing manuallyTriggered=$manuallyTriggered")
            var challengeBytes = byteArrayOf()
            if (centralChallengeResponse != null) {
                challengeBytes = PumpMessageSerializer.toBytes(centralChallengeResponse)
            }
            PumpState.getPairingCode(context)?.let {
                Timber.i("Pairing with saved code: $it centralChallenge: $centralChallengeResponse")
                pair(peripheral, centralChallengeResponse, it)
                callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_ENTERED_PAIRING_CODE, challengeBytes)
            } ?: run {
                Timber.i("Pairing without saved code: centralChallenge: $centralChallengeResponse")
                callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_MISSING_PAIRING_CODE, challengeBytes)
            }
        }

        override fun onPumpDiscovered(
            peripheral: BluetoothPeripheral?,
            scanResult: ScanResult?,
            readyState: PumpReadyState
        ): Boolean {
            callbacks.sendWearCommMessage(
                MessagePaths.FROM_PUMP_PUMP_DISCOVERED,
                "${peripheral?.name.orEmpty()};;${readyState.name}".toByteArray()
            )
            DiagMonitor.onConnectStart("discovered:${readyState.name}") // DIAG-TEMP
            return super.onPumpDiscovered(peripheral, scanResult, readyState)
        }

        override fun onInitialPumpConnection(peripheral: BluetoothPeripheral?) {
            DiagMonitor.onConnectStart("initial") // DIAG-TEMP
            lastPeripheral = peripheral
            val wait = (500..1000).random()
            Timber.i("Waiting to pair onInitialPumpConnection to avoid race condition with tconnect app for ${wait}ms")
            Thread.sleep(wait.toLong())

            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_INITIAL_PUMP_CONNECTION, "${peripheral?.name}".toByteArray())

            if (Packetize.txId.get() > 0) {
                Timber.w("Not pairing in onInitialPumpConnection because it looks like the tconnect app has already paired with txId=${Packetize.txId.get()}")
                return
            }
            super.onInitialPumpConnection(peripheral)
        }

        @VisibleForTesting
        fun initializeConnection(peripheral: BluetoothPeripheral) {
            lastPeripheral = peripheral

            extractPumpSid(peripheral.name ?: "")?.let {
                pumpSid = it
                callbacks.prefSetCurrentPumpSid(it)
            }

            val session = PumpSession.open(this, peripheral)
            this@PumpCommHandler.currentSession = session

            isConnected = true
            Timber.i("service initializeConnection: $this")
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_CONNECTED,
                peripheral.name!!.toByteArray()
            )
            val glucoseUnit = callbacks.prefGlucoseUnit()
            if (glucoseUnit != null) {
                callbacks.sendWearCommMessage(MessagePaths.TO_CLIENT_GLUCOSE_UNIT, glucoseUnit.name.toByteArray())
            }
            callbacks.markConnectionTime()
        }

        private fun initializeHistoryAndSync() {
            historyLogFetcher = callbacks.createHistoryLogFetcher(
                pumpSid = pumpSid!!,
                pumpSession = this@PumpCommHandler.currentSession!!,
                autoFetchEnabled = { callbacks.prefAutoFetchHistoryLogs() },
            )
            Timber.i("HistoryLogFetcher initialized")

            historyLogSyncWorker = callbacks.createHistoryLogSyncWorker(requestSync = { requestHistoryLogStatusUpdate() })
            refreshHistoryLogSyncWorker(triggerImmediateSync = true)
            callbacks.onPumpConnectedSync(pumpSid!!)
        }

        private fun waitForResponseStabilization() {
            var numResponses = -99999
            while (PumpState.processedResponseMessages != numResponses) {
                numResponses = PumpState.processedResponseMessages
                val wait = (250..500).random()
                Timber.i("service onPumpConnected -- waiting for ${wait}ms to avoid race conditions: (processedResponseMessages: ${PumpState.processedResponseMessages})")
                Thread.sleep(wait.toLong())
            }
        }

        private fun sendBaseCommandsIfNeeded(peripheral: BluetoothPeripheral) {
            Timber.i("service onPumpConnected -- checking for base messages (processedResponseMessages: ${PumpState.processedResponseMessages})")
            if (!callbacks.pumpCommState.lastResponseMessage.containsKey(Pair(Characteristic.CURRENT_STATUS, ApiVersionRequest().opCode()))) {
                Timber.i("service onPumpConnected -- sending ApiVersionRequest")
                this.sendCommand(peripheral, ApiVersionRequest())
            }

            if (!callbacks.pumpCommState.lastResponseMessage.containsKey(Pair(Characteristic.CURRENT_STATUS, TimeSinceResetResponse().opCode()))) {
                Timber.i("service onPumpConnected -- sending TimeSinceResetResponse")
                this.sendCommand(peripheral, TimeSinceResetRequest())
            }
            Thread.sleep(250)
        }

        override fun onPumpConnected(peripheral: BluetoothPeripheral?) {
            DiagMonitor.onConnected("onPumpConnected") // DIAG-TEMP
            // 新链路：先解除端口断开态并进入静默窗，握手与基线请求期间不放行业务查询。
            // 静默窗时长取 states.connectedSettleMs（当前 8s）；库的 initializeConnection /
            // sendBaseCommands 本身就在占用链路，此时补查是纯叠加。
            queryPort.markLinkUp(quietMs = queryConnectSettleMs)
            // 新链路：清空上一轮的写失败计数与冷却，避免旧账影响本次判定。
            writeFailureTimes.clear()
            readQueryCooldownUntil = 0L
            initializeConnection(peripheral!!)
            initializeHistoryAndSync()
            waitForResponseStabilization()
            sendBaseCommandsIfNeeded(peripheral)
            startAppReassertIfNeeded()
            callbacks.updateNotification(callbacks.getApplicationContext()
            .getString(R.string.comm_connected_to_pump))
        }

        override fun onPumpModel(peripheral: BluetoothPeripheral?, model: KnownDeviceModel?) {
            super.onPumpModel(peripheral, model)
            Timber.i("service onPumpModel")
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_MODEL,
                model!!.name.toByteArray()
            )

            val modelName = when (model) {
                KnownDeviceModel.TSLIM_X2 -> "t:slim X2"
                KnownDeviceModel.MOBI -> "Tandem Mobi"
                else -> "Tandem Pump"
            }
            callbacks.prefSetPumpModelName(modelName)
        }

        override fun onPumpDisconnected(
            peripheral: BluetoothPeripheral?,
            status: HciStatus?
        ): Boolean {
            DiagMonitor.onDisconnected(status?.name, "callback", "peripheral=${peripheral?.name}") // DIAG-TEMP
            Timber.i("service onPumpDisconnected: isConnected=false status=$status") // DIAG-TEMP
            // 断开的**最早可靠信号**：别等静默超时，立刻让查询侧停表。
            // 依实测，App 层「多久没回包」不能用来判断链路生死，连接回调才是依据。
            queryPort.markLinkDown()
            stopAppReassert()
            currentSession?.close()
            currentSession = null
            lastPeripheral = null
            callbacks.pumpCommState.lastResponseMessage.clear()
            historyLogFetcher?.cancel()
            historyLogFetcher = null
            historyLogSyncWorker?.stop()
            historyLogSyncWorker = null
            callbacks.pumpCommState.lastTimeSinceReset = null
            callbacks.pumpCommState.debugPromptResponseCounts.clear()
            isConnected = false
            // pumppan: peripheral/name 在启动阶段或已释放后可能为 null；
            // 原来用 `peripheral?.name!!` 会抛 NPE，导致紧随其后的通知更新与 Toast 一起被跳过。
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_DISCONNECTED,
                (peripheral?.name ?: "").toByteArray()
            )
            callbacks.markConnectionTime()
            callbacks.updateNotification(callbacks.getApplicationContext()
            .getString(R.string.comm_disconnected_from_pump))
            callbacks.showToast("Pump disconnected: $status", Toast.LENGTH_SHORT)
            shutdownLatch?.countDown()
            return super.onPumpDisconnected(peripheral, status)
        }

        override fun onPumpCriticalError(peripheral: BluetoothPeripheral?, reason: TandemError?) {
            super.onPumpCriticalError(peripheral, reason)
            Timber.w("onPumpCriticalError $reason")
            // 诊断补强：把 uuid / opCode / 状态原文一起落盘，下一轮不必再从 writeFailures 反推。
            val initiating = reason?.initiatingMessage
            val uuidName = initiating?.let {
                runCatching { CharacteristicUUID.determine(it).toString() }.getOrNull()
            }
            val opCode = initiating?.let {
                runCatching { it.opCode().toInt() }.getOrNull()
            }
            DiagMonitor.onCriticalError(
                reason = reason?.name ?: "null",
                uuid = uuidName,
                opCode = opCode,
                detail = reason?.extra,
            ) // DIAG-TEMP
            val source = reason?.initiatingMessage?.let {
                if (callbacks.pumpCommState.consumeDebugPromptResponse(it.characteristic, it.responseOpCode)) {
                    com.jwoglom.controlx2.shared.util.SendType.DEBUG_PROMPT
                } else {
                    com.jwoglom.controlx2.shared.util.SendType.STANDARD
                }
            } ?: com.jwoglom.controlx2.shared.util.SendType.STANDARD
            reason?.let { callbacks.onPumpCriticalError(it, source = source) }
            // No toast: pump-critical-errors are surfaced inline by the setup screen, classified
            // into TRANSIENT/ACTIONABLE/FATAL tiers — see PumpCriticalErrorClassifier.
            reason?.let { err ->
                val presentation = PumpCriticalErrorClassifier.classify(err, callbacks.getApplicationContext())
                callbacks.sendWearCommMessage(
                    MessagePaths.FROM_PUMP_PUMP_CRITICAL_ERROR,
                    presentation.toJson().toByteArray()
                )
            }
        }

        @Synchronized
        fun command(message: com.jwoglom.pumpx2.pump.messages.Message?, bypassDedup: Boolean = false) {
            if (message == null) {
                Timber.w("Not sending null message")
                return
            }

            // 查询层收敛 · 第 0 步：只读计数（不改发送行为）
            PumpQueryService.noteLegacyRequest(
                topic = PumpTopic.fromRequestSimpleName(message.javaClass.simpleName),
                source = "PumpCommHandler.command",
                connected = isConnected,
                willSend = lastPeripheral != null && isConnected,
            )
            if (lastPeripheral == null) {
                Timber.w("Not sending message because no saved peripheral yet: $message")
                return
            }

            if (!isConnected) {
                Timber.w("Not sending message because no onConnected event yet: $message")
                return
            }

            val session = currentSession
            if (session == null) {
                Timber.w("Not sending message because no active session: $message")
                return
            }

            // 只读查询：收进唯一出口（同 opCode 单飞去重）。
            // 控制 / 签名 / 取消类仍直发，豁免清单见 PumpReadOnlyQueries。
            if (!bypassDedup && PumpReadOnlyQueries.isReadOnly(message.javaClass.simpleName)) {
                // 写失败后的冷却：只挡只读查询，避免把写队列继续填满；控制类走下面直发。
                if (isReadQueryCooling()) {
                    Timber.d("query suppressed: write cooldown active")
                    return
                }
                val result = queryPort.request(
                    message = message,
                    minIntervalMs = 0L,   // 页面查询不节流，避免挡住用户主动刷新；节流只给大剂量调度器
                    timeoutMs = QUERY_IN_FLIGHT_TIMEOUT_MS,
                )
                if (!result.sent) {
                    Timber.d("query suppressed: op=${result.opCode} reason=${result.reject}")
                }
                return
            }

            // 第 1 步观测：走到这里才是真实下发（前面三个 return 属于空尝试，不计 opCode）
            dispatchDirect(message)
        }

        override fun toString(): String {
            return "Pump(isConnected=$isConnected, lastPeripheral=$lastPeripheral)"
        }
    }

    private fun onReceiveInitiateBolusResponse(response: InitiateBolusResponse?) {
        callbacks.broadcastBolusInitiateResponse(PumpMessageSerializer.toBytes(response))
    }

    private var lastBolusStatusId: Int? = null

    private fun onReceiveCurrentBolusStatusResponse(response: CurrentBolusStatusResponse?) {
        if (response != null) {
            val shouldBroadcast = response.bolusId != 0 ||
                (response.bolusId == 0 && lastBolusStatusId != null && lastBolusStatusId != 0)

            if (shouldBroadcast) {
                callbacks.broadcastBolusStatusUpdate(PumpMessageSerializer.toBytes(response))
            }

            lastBolusStatusId = response.bolusId

            if (response.bolusId == 0) {
                this.postDelayed({
                    if (lastBolusStatusId == 0) {
                        lastBolusStatusId = null
                    }
                }, 2000)
            }
        }
    }

    private fun onReceiveTimeSinceResetResponse(response: TimeSinceResetResponse?) {
        Timber.i("pumpCommState.lastTimeSinceReset = $response")
        callbacks.pumpCommState.lastTimeSinceReset = response
    }

    private fun pumpConnectedPrecondition(checkConnected: Boolean = true): Boolean {
        if (!this::pump.isInitialized) {
            Timber.e("pumpConnectedPrecondition: pump not initialized")
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_NOT_CONNECTED, "not_initialized".toByteArray())
        } else if (pump.lastPeripheral == null) {
            Timber.e("pumpConnectedPrecondition: pump not saved peripheral")
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_NOT_CONNECTED, "null_peripheral".toByteArray())
        } else if (checkConnected && !pump.isConnected) {
            Timber.e("pumpConnectedPrecondition: pump not connected")
            callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_NOT_CONNECTED, "not_connected".toByteArray())
        } else {
            return true
        }
        return false
    }

    private fun isBolusCommand(message: com.jwoglom.pumpx2.pump.messages.Message): Boolean {
        return (message is InitiateBolusRequest) || (message.opCode() == InitiateBolusRequest().opCode() && message.characteristic == Characteristic.CONTROL)
    }

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            CommServiceCodes.INIT_PUMP_COMM.ordinal -> {
                if (pumpConnectedPrecondition(checkConnected = false)) {
                    Timber.w("pumpCommHandler: init_pump_comm already run, ignoring")
                    return
                }
                try {
                    var pairingCodeType: PairingCodeType? = null
                    var filterToBluetoothMac: String? = null
                    if (msg.obj != null && msg.obj != "") {
                        var parts = (msg.obj as String).split(" ")
                        pairingCodeType = PairingCodeType.fromLabel(parts[0])
                        if (parts.size == 2) {
                            filterToBluetoothMac = parts[1]
                        }
                    }

                    if (!ensurePumpUnbondedForFreshInit(filterToBluetoothMac)) {
                        return
                    }
                    Timber.i("pumpCommHandler: init_pump_comm: msg.obj=${msg.obj as String} pairingCodeType=$pairingCodeType filterToBluetoothMac=$filterToBluetoothMac")
                    val cfg = TandemConfig()
                        .withFilterToBluetoothMac(filterToBluetoothMac)
                        .withPairingCodeType(pairingCodeType)

                    pump = Pump(cfg)
                    tandemBTHandler =
                        TandemBluetoothHandler.getInstance(callbacks.getApplicationContext(), pump, null)
                } catch (e: SecurityException) {
                    Timber.e("pumpCommHandler: SecurityException starting pump $e")
                }
                while (true) {
                    try {
                        Timber.i("pumpCommHandler: Starting scan...")
                        tandemBTHandler.startScan()
                        break
                    } catch (e: SecurityException) {
                        Timber.e("pumpCommHandler: Waiting for BT permissions $e")
                        Thread.sleep(500)
                    }
                }
            }
            CommServiceCodes.STOP_PUMP_COMM.ordinal -> {
                tandemBTHandler.stop()
            }
            CommServiceCodes.CHECK_PUMP_CONNECTED.ordinal -> {
                if (pumpConnectedPrecondition()) {
                    // pumppan: 与 onPumpDisconnected 同因，lastPeripheral/name 可能为 null，避免 NPE
                    callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_PUMP_CONNECTED,
                        (pump.lastPeripheral?.name ?: "").toByteArray()
                    )
                }
            }
            CommServiceCodes.SEND_PUMP_PAIRING_MESSAGE.ordinal -> {
                if (pumpConnectedPrecondition(checkConnected = false)) {
                    if (pump.lastPeripheral != null && pump.pairingCodeCentralChallenge != null) {
                        Timber.i("sendPumpPairingMessage: running performPairing")
                        pump.performPairing(
                            pump.lastPeripheral,
                            pump.pairingCodeCentralChallenge,
                            true
                        )
                    } else {
                        Timber.w("sendPumpPairingMessage: cannot send pump pairing message: lastPeripheral=${pump.lastPeripheral} pairingCodeCentralChallenge=${pump.pairingCodeCentralChallenge}")
                    }
                }
            }
            CommServiceCodes.SEND_PUMP_COMMAND.ordinal -> {
                Timber.i("pumpCommHandler send command raw: ${String(msg.obj as ByteArray)}")
                val pumpMsg = PumpMessageSerializer.fromBytes(msg.obj as ByteArray)
                if (isBolusCommand(pumpMsg)) {
                    Timber.e("SEND_PUMP_COMMAND blocked bolus command")
                } else if (pumpConnectedPrecondition()) {
                    Timber.i("pumpCommHandler send command: $pumpMsg")
                    pump.command(pumpMsg)
                }
            }
            CommServiceCodes.SEND_PUMP_COMMANDS_BULK.ordinal -> {
                Timber.i("pumpCommHandler send commands raw: ${String(msg.obj as ByteArray)}")
                dispatchBulkPaced(PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray))
            }
            CommServiceCodes.SEND_PUMP_COMMANDS_BUST_CACHE_BULK.ordinal -> {
                Timber.i("pumpCommHandler send commands bust cache raw: ${String(msg.obj as ByteArray)}")
                dispatchBulkPaced(PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray)) { it ->
                    if (callbacks.pumpCommState.lastResponseMessage.containsKey(Pair(it.characteristic, it.responseOpCode))) {
                        Timber.i("pumpCommHandler busted cache: $it")
                        callbacks.pumpCommState.lastResponseMessage.remove(Pair(it.characteristic, it.responseOpCode))
                    }
                }
            }
            CommServiceCodes.CACHED_PUMP_COMMANDS_BULK.ordinal -> {
                Timber.i("pumpCommHandler cached pump commands raw: ${String(msg.obj as ByteArray)}")
                val toSend = mutableListOf<com.jwoglom.pumpx2.pump.messages.Message>()
                PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray).forEach {
                    val cacheKey = Pair(it.characteristic, it.responseOpCode)
                    val response = callbacks.pumpCommState.lastResponseMessage[cacheKey]
                    if (response != null && !isBolusCommand(it)) {
                        val ageSeconds = Duration.between(response.second, Instant.now()).seconds
                        if (ageSeconds <= CacheSeconds) {
                            // 命中缓存不占蓝牙带宽，立即回给 UI，不参与错峰。
                            Timber.i("pumpCommHandler cached hit: $response")
                            callbacks.sendWearCommMessage(
                                MessagePaths.FROM_PUMP_RECEIVE_CACHED_MESSAGE,
                                PumpMessageSerializer.toBytes(response.first)
                            )
                        } else {
                            Timber.i("pumpCommHandler expired cache hit $ageSeconds sec: $response")
                            toSend += it
                        }
                    } else if (isBolusCommand(it)) {
                        Timber.e("CACHED_PUMP_COMMANDS_BULK blocked bolus command")
                    } else {
                        Timber.i("pumpCommHandler cached miss: $it")
                        toSend += it
                    }
                }
                dispatchBulkPaced(toSend)
            }
            CommServiceCodes.SEND_PUMP_COMMAND_RAW_UNSAFE.ordinal -> {
                // Debug-only path used by POST /api/pump/raw-command. Unlike
                // SEND_PUMP_COMMAND / SEND_PUMP_COMMANDS_BULK this does not drop
                // bolus messages via isBolusCommand, so classes hidden from the
                // debug list (authentication.*, Nonexistent*, FactoryReset*) and
                // -98 could be delivered. Signature/encryption still happen in
                // pump.command(); the connection precondition is kept.
                val rawMsg = msg.obj as? com.jwoglom.pumpx2.pump.messages.Message
                if (rawMsg == null) {
                    Timber.e("SEND_PUMP_COMMAND_RAW_UNSAFE received non-Message payload")
                } else if (pumpConnectedPrecondition()) {
                    Timber.i("pumpCommHandler send raw unsafe: $rawMsg")
                    // 调试口（POST /api/pump/raw-command）不做查询去重：本来就是手动逐条诊断
                    pump.command(rawMsg, bypassDedup = true)
                }
            }
            CommServiceCodes.SEND_PUMP_COMMAND_BOLUS.ordinal -> {
                Timber.i("pumpCommHandler send bolus raw: ${String(msg.obj as ByteArray)}")
                val secretKey = callbacks.getWearPrefs()?.getString("initiateBolusSecret", "") ?: ""
                val confirmedBolus =
                    InitiateConfirmedBolusSerializer.fromBytes(secretKey, msg.obj as ByteArray)

                val messageOk = confirmedBolus.left
                val pumpMsg = confirmedBolus.right
                if (!messageOk) {
                    Timber.w("pumpCommHandler bolus invalid signature")
                    callbacks.sendWearCommMessage(MessagePaths.TO_CLIENT_BOLUS_BLOCKED_SIGNATURE, "WearCommHandler".toByteArray())
                } else if (!isBolusCommand(pumpMsg)) {
                    Timber.e("SEND_PUMP_COMMAND_BOLUS not a bolus command: $pumpMsg")
                } else if (pumpConnectedPrecondition()) {
                    Timber.i("pumpCommHandler send bolus command with valid signature: $pumpMsg")
                    if (!callbacks.prefInsulinDeliveryActions()) {
                        Timber.e("No insulin delivery messages enabled -- blocking bolus command $pumpMsg")
                        callbacks.sendWearCommMessage(MessagePaths.TO_CLIENT_BOLUS_NOT_ENABLED, "from_self".toByteArray())
                        return
                    }
                    try {
                        pump.command(pumpMsg as InitiateBolusRequest)
                    } catch (e: Packetize.ActionsAffectingInsulinDeliveryNotEnabledInPumpX2Exception) {
                        Timber.e(e)
                        callbacks.sendWearCommMessage(MessagePaths.TO_CLIENT_BOLUS_NOT_ENABLED, "from_pumpx2_lib".toByteArray())
                    }
                }
            }
            CommServiceCodes.DEBUG_WRITE_BT_CHARACTERISTIC.ordinal -> {
                Timber.i("pumpCommHandler debug_write_bt_characteristic: ${String(msg.obj as ByteArray)}")
                try {
                    val contents = JSONObject(String(msg.obj as ByteArray));
                    val uuidStr = contents.getString("characteristicUuid")
                    val uuid = UUID.fromString(uuidStr)

                    val valuesStr = contents.getJSONArray("valuesHex")
                    var valuesHex = ArrayList<ByteArray>()
                    for (i in 0..valuesStr.length()) {
                        valuesHex.add(Hex.decodeHex(valuesStr.getString(i)))
                    }

                    if (pumpConnectedPrecondition()) {
                        if (!callbacks.prefInsulinDeliveryActions()) {
                            return
                        }
                        valuesHex.forEach {
                            Packetize.txId.increment()
                            pump.lastPeripheral?.writeCharacteristic(
                                ServiceUUID.PUMP_SERVICE_UUID,
                                uuid,
                                it,
                                WriteType.WITH_RESPONSE
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            CommServiceCodes.WRITE_CHARACTERISTIC_FAILED_CALLBACK.ordinal -> {
                val uuidStr = msg.obj as String
                if (pumpConnectedPrecondition(checkConnected = false)) {
                    if (uuidStr == "${CharacteristicUUID.AUTHORIZATION_CHARACTERISTICS}") {
                        Timber.w("writeCharacteristicFailedCallback: calling onPumpConnected pump=$pump from authorization error")
                        pump.onPumpConnected(pump.lastPeripheral)
                    } else {
                        // 写失败分级：单次失败多半是泵忙 / 队列拥塞（泵回了 ERROR_RESPONSE），
                        // 此时剪线只会换来一轮 250ms 重连 + 配对码握手，反而更久不可用。
                        // 只有同一 uuid 在窗口内连续达到阈值，才判定为链路故障并剪线。
                        val policy = linkPolicy
                        val count = pump.noteWriteFailure(uuidStr, SystemClock.elapsedRealtime(), policy.windowMs)
                        DiagMonitor.onWriteFailed(uuidStr, "窗口内第 ${count} 次") // DIAG-TEMP
                        if (count >= policy.cutThreshold) {
                            DiagMonitor.onWriteCut(uuidStr, "连续 ${count} 次") // DIAG-TEMP
                            Timber.w("writeCharacteristicFailedCallback: $uuidStr failed ${count}x in ${policy.windowMs}ms, cutting link")
                            pump.lastPeripheral?.cancelConnection();
                            pump.lastPeripheral?.let { pump.onPumpDisconnected(it, null) }
                        } else {
                            pump.startReadQueryCooldown(policy.readCooldownMs)
                            Timber.w("writeCharacteristicFailedCallback: $uuidStr transient failure ${count}/${policy.cutThreshold}, keeping link")
                        }
                    }
                }
            }
            CommServiceCodes.DEBUG_GET_MESSAGE_CACHE.ordinal -> {
                Timber.i("pumpCommHandler debug get message cache: ${callbacks.pumpCommState}.lastResponseMessage")
                callbacks.sendWearCommMessage(MessagePaths.FROM_PUMP_DEBUG_MESSAGE_CACHE, PumpMessageSerializer.toDebugMessageCacheBytes(callbacks.pumpCommState.lastResponseMessage.values))
            }
            CommServiceCodes.DEBUG_GET_HISTORYLOG_CACHE.ordinal -> {
                Timber.i("pumpCommHandler debug get historylog cache: ${callbacks.pumpCommState}.historyLogCache")
                if (callbacks.pumpCommState.historyLogCache.size <= 100) {
                    callbacks.sendWearCommMessage(
                        MessagePaths.FROM_PUMP_DEBUG_HISTORYLOG_CACHE,
                        PumpMessageSerializer.toDebugHistoryLogCacheBytes(callbacks.pumpCommState.historyLogCache)
                    )
                } else {
                    callbacks.pumpCommState.historyLogCache.entries.toList().chunked(100).forEach {
                        callbacks.sendWearCommMessage(
                            MessagePaths.FROM_PUMP_DEBUG_HISTORYLOG_CACHE,
                            PumpMessageSerializer.toDebugHistoryLogCacheBytes(it.associate {
                                Pair(it.key, it.value)
                            })
                        )
                    }
                }
            }
            CommServiceCodes.DEBUG_CLEAR_HISTORYLOG_CACHE.ordinal -> {
                Timber.i("pumpCommHandler debug clear historylog cache")
                callbacks.pumpCommState.clearHistoryLogCache()
                historyLogFetcher?.reset()
            }
        }
    }
}
