@file:OptIn(ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.pumppan.controlx2.query.QueryDomainRequests
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.builders.ControlIQInfoRequestBuilder
import com.jwoglom.pumpx2.pump.messages.builders.CurrentBatteryRequestBuilder
import com.jwoglom.pumpx2.pump.messages.builders.LastBolusStatusRequestBuilder
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownApiVersion
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.GlobalMaxBolusSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.InsulinStatusRequest
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
import com.pumppan.controlx2.query.PumpQueryService
import com.pumppan.controlx2.query.PumpTopic
import com.jwoglom.controlx2.db.historylog.HistoryLogViewModel
import com.jwoglom.controlx2.presentation.components.HistoryLogSyncProgressBar
import com.jwoglom.controlx2.presentation.components.LastConnectionUpdatedTimestamp
import com.jwoglom.controlx2.presentation.components.PumpSetupStageDescription
import com.jwoglom.controlx2.presentation.components.PumpSetupStageProgress
import com.jwoglom.controlx2.presentation.components.ServiceDisabledMessage
import com.jwoglom.controlx2.presentation.screens.sections.components.cards.ActiveTherapyCardFromDataStore
import com.jwoglom.controlx2.presentation.screens.sections.components.cards.GlucoseHeroCard
import com.jwoglom.controlx2.presentation.screens.sections.components.PumpStatusBar
import com.jwoglom.controlx2.presentation.screens.sections.components.cards.SensorInfoCardFromDataStore
import com.jwoglom.controlx2.presentation.screens.sections.components.cards.TherapyMetricsCardFromDataStore
import com.jwoglom.controlx2.presentation.screens.sections.components.VicoCgmChartCard
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.presentation.util.cgmQueryAllowed
import com.jwoglom.controlx2.presentation.util.isCgmQuery
import com.jwoglom.controlx2.shared.FeatureFlag
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.models.NotificationBundle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

// 首页补发通知包的最小间隔：连接抖动/生命周期反复触发时的下限。
private const val NOTIFICATION_FETCH_MIN_INTERVAL_MS = 30_000L

// 首页「等字段填满」的兜底补拉节奏与上限：只影响 UI 首屏的缓存重读，不参与泵侧
// 查询节奏，因此不进策略配置。4 次 × 2.5s ≈ 10s 足够覆盖正常回包；仍为 null 的
// 字段（多为泵侧没有该能力，如未接 CGM）交回协调器周期域，页面用占位文案顶住。
private const val WAIT_FOR_LOADED_RETRY_INTERVAL_MS = 2_500L
private const val WAIT_FOR_LOADED_MAX_RETRIES = 4

@Composable
fun Dashboard(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    historyLogViewModel: HistoryLogViewModel? = null,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val pumpConnected = ds.pumpConnected.observeAsState()

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }
    // 「等字段填满」的触发计数：只在 进页首帧 / 连接建立 / 下拉刷新 / 回到前台 时 +1。
    // 不能用 refreshing 当 key——waitForLoaded 结束会把 refreshing 置回 false，
    // 那样 effect 会因为 false 再次重启，形成每 10s 一轮的自我重触发。
    var loadTrigger by remember { mutableStateOf(0) }
    // 通知包节流：连接状态抖动会让 LaunchedEffect(pumpConnected) 反复触发，
    // 若每次都带 7 条通知查询就退化回"高频整包"。30s 内只补一次。
    var lastNotificationFetchAt by remember { mutableStateOf(0L) }

    fun fetchDataStoreFields(
        type: SendType,
        source: String = "Dashboard.other",
        includeNotifications: Boolean = false
    ) {
        if (pumpConnected.value != true) {
            Timber.d("Dashboard skipping fetch while pump disconnected")
            refreshing = false
            return
        }
        // 第 0′ 步：只登记「谁在问」，不影响下发内容与顺序
        PumpQueryService.notePageRequest(PumpTopic.DASHBOARD_BATCH, source)
        // 查询层收敛：通知详情不再随 60s 周期发。周期只问首页关键集；
        // 通知包只在首帧、下拉刷新、连接建立时补一次（30s 节流），之后靠
        // QualifyingEvent.suggestedHandlers（ALERT/ALARM/REMINDER/CGM_ALERT/MALFUNCTION）驱动补查。
        val now = System.currentTimeMillis()
        val withNotifications =
            includeNotifications && (now - lastNotificationFetchAt) >= NOTIFICATION_FETCH_MIN_INTERVAL_MS
        if (withNotifications) {
            lastNotificationFetchAt = now
        }
        sendPumpCommands(
            type,
            if (withNotifications) dashboardCommands + dashboardNotificationCommands else dashboardCommands
        )
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        var retries = 0
        while (true) {
            val nullFields = dashboardFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }
            // 兜底补拉只服务「首屏尽快填满」：CACHED 批走缓存、不打泵，但字段可能因为
            // 泵没有该能力（例如未接 CGM 的 cgmReading / cgmDeltaArrow）永远为 null，
            // 原实现会每 2.5s 重发整批直到页面销毁。这里给重试次数设上限，超限即交回
            // 协调器的周期域（PUMP_VITALS / IOB / DASHBOARD_DETAIL 等）继续填，
            // 页面按既有占位文案渲染，不再空转。
            if (retries >= WAIT_FOR_LOADED_MAX_RETRIES) {
                Timber.w("Dashboard.waitForLoaded gave up after $retries retries, still null: ${nullFields.size}/${dashboardFields.size}")
                break
            }
            if (sinceLastFetchTime >= WAIT_FOR_LOADED_RETRY_INTERVAL_MS) {
                //Timber.i("Dashboard loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED, "Dashboard.waitForLoaded")
                sinceLastFetchTime = 0
                retries++
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("Dashboard loading done: ${dashboardFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading Dashboard with force")
        refreshing = true

        dashboardFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE, "Dashboard.pullRefresh", includeNotifications = true)
        loadTrigger++
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    // 首页两档一起订阅 / 一起退订：界面没变，变的是两档的到期周期（120s / 300s）。
    // 60s 那一档（电量/储药/IOB）已由保底域常驻驱动，页面不再单独订阅。
    val dashboardDomains = listOf(
        QueryDomainRequests.DASHBOARD_DETAIL,
        QueryDomainRequests.DASHBOARD_CGM,
    )
    fun subscribeDashboard(visible: Boolean) =
        dashboardDomains.forEach { QueryCoordinatorHolder.setVisible(it, visible) }

    // 页面离开组合时退订（导航 back stack 场景下 onStop 不一定触发）。
    DisposableEffect(Unit) {
        onDispose { subscribeDashboard(false) }
    }

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        subscribeDashboard(false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading Dashboard from onStart lifecyclestate")
        subscribeDashboard(true)
        fetchDataStoreFields(SendType.STANDARD, "Dashboard.onStart", includeNotifications = true)
        loadTrigger++
    }

    // 周期刷新已交给 QueryCoordinator（三档各自的周期）；页面只保留首帧、下拉刷新
    // 与连接建立三类补拉——首帧仍发一次全量，保证进页面第一眼就是齐的。

    LaunchedEffect(loadTrigger) {
        // 链路没起来时补拉没有意义（CACHED 也读不到），等连接建立那一次触发；
        // 这里只消费计数，不读 refreshing，避免 waitForLoaded 收尾时自我重触发。
        if (pumpConnected.value == true) waitForLoaded()
    }

    LaunchedEffect(pumpConnected.value) {
        if (pumpConnected.value != true) {
            refreshing = false
        } else {
            // 连接建立后补一次（含通知包）：onStart 可能在未连接状态下被跳过。
            fetchDataStoreFields(SendType.STANDARD, "Dashboard.connectionChange", includeNotifications = true)
            loadTrigger++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(state)
    ) {
        PullRefreshIndicator(
            refreshing, state,
            Modifier
                .align(Alignment.TopCenter)
                .zIndex(10f)
        )
        LazyColumn(
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            content = {
                item {
                    val pumpLastConnectionTimestamp = ds.pumpLastConnectionTimestamp.observeAsState()

                    ServiceDisabledMessage(sendMessage = sendMessage)
                    PumpSetupStageProgress(initialSetup = false)
                    PumpSetupStageDescription(initialSetup = false, sendMessage = sendMessage)

                    LaunchedEffect(pumpLastConnectionTimestamp.value) {
                        Timber.d("pumpLastConnectionTimestamp effect: ${pumpLastConnectionTimestamp.value}")
                        if (pumpConnected.value == true) {
                            PumpQueryService.notePageRequest(PumpTopic.DASHBOARD_BATCH, "Dashboard.connectionChange")
                            sendPumpCommands(SendType.CACHED, dashboardCommands)
                        }
                    }
                }

                // Pump Status Bar with battery, connection, and cartridge
                item {
                    PumpStatusBar(middleContent = {
                        LastConnectionUpdatedTimestamp()
                    })
                }

                item {
                    val cgmReading = ds.cgmReading.observeAsState()
                    val cgmDeltaArrow = ds.cgmDeltaArrow.observeAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        // Hero card showing current glucose reading
                        GlucoseHeroCard(
                            glucoseValue = cgmReading.value,
                            deltaArrow = cgmDeltaArrow.value,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )

                        // Therapy Metrics Card - IOB, COB
                        TherapyMetricsCardFromDataStore(
                            historyLogViewModel = historyLogViewModel,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }


                // CGM Chart with Vico - shows glucose, boluses, basal, carbs
                if (FeatureFlag.cgmChartVisibleState.value) {
                    item {
                        VicoCgmChartCard(
                            historyLogViewModel = historyLogViewModel
                        )
                    }
                }

                // Active Therapy Card - Basal, Last Bolus, Mode
                item {
                    ActiveTherapyCardFromDataStore()
                }

                // Sensor Info Card - Sensor expiration, Transmitter status
                if (FeatureFlag.sensorInfoCardVisibleState.value) {
                    item {
                        SensorInfoCardFromDataStore()
                    }
                }

                // History Log Sync Progress
                // 开关打开即常显：不传 replaceWithPaddingWhenComplete，避免同步完成后
                // 组件因 isSynced 提前 return 导致进度条只在刷新瞬间闪现。
                if (FeatureFlag.historyLogSyncBarVisibleState.value) {
                    historyLogViewModel?.let {
                        item {
                            HistoryLogSyncProgressBar(
                                historyLogViewModel = it
                            )
                        }
                    }
                }
            }
        )
    }
}

fun apiVersion(): ApiVersion {
    var apiVersion = PumpState.getPumpAPIVersion()
    if (apiVersion == null) {
        apiVersion = KnownApiVersion.API_V2_5.get()
    }
    return apiVersion
}

// 第 0′ 步：改为 getter。若仍是 val，listOf 会在首次加载时求值一次，
// CGM 门控会被冻结在那一刻的状态，之后 STOPPED 也不会生效。
val dashboardCommands: List<Message>
    get() = listOf(
    CurrentBatteryRequestBuilder.create(apiVersion()),
    ControlIQIOBRequest(),
    InsulinStatusRequest(),
    LastBolusStatusRequestBuilder.create(apiVersion()),
    HomeScreenMirrorRequest(),
    ControlIQInfoRequestBuilder.create(apiVersion()),
    CurrentBasalStatusRequest(),
    CGMStatusRequest(),
    CurrentEGVGuiDataRequest(),
    GlobalMaxBolusSettingsRequest()
    // 2026-11-12：历史日志状态已由协调器 `HISTORY` 域（300s）独占下发。
    // 这里再带一条会在每次进页 / 连接建立 / 下拉刷新时抢先发一遍，把协调器那一拍
    // 挤成 THROTTLED，表现为「每 5s 计划一次、每 5s 被拒一次」的空转（真机复现见 §7.14）。
    ).filter { msg ->
        !msg.props().minApi.get().greaterThan(apiVersion()) &&
            // G6/G7 明确「未启动」时不再追问 CGM 两条；null（未知）与在用状态照常发。
            (cgmQueryAllowed() || !msg.isCgmQuery())
    }

// 通知详情只问一次：首帧 / 下拉刷新 / 连接建立。周期轮询不再携带，
// 之后由泵主动事件（ALERT/ALARM/REMINDER/CGM_ALERT/MALFUNCTION）经
// QualifyingEvent.suggestedHandlers 触发最小补查，避免每分钟无差别刷 7 条。
val dashboardNotificationCommands: List<Message>
    get() = NotificationBundle.allRequests().filter { msg ->
        !msg.props().minApi.get().greaterThan(apiVersion()) &&
            (cgmQueryAllowed() || !msg.isCgmQuery())
    }

// ── 首页按「常显 / 次常显 / 慢变」的频率交给协调器各自的周期 ────────────────────
// 界面排版不动（仍是原来那张卡片布局），只是查询不再一次性整批打出去。
//
// 2026-11-12：电量/储药/IOB 已由 `PUMP_VITALS` / `IOB` 保底域按 60s 接管，
// 基础率与上次大剂量已由 `BASAL` / `BOLUS_LAST` 保底域按 120s 接管，
// CGM 会话/CIQ 状态/HistoryLogStatus 分别由 `CGM` / `CONTROL_IQ` / `HISTORY` 按 300s 接管。
// 首页这里只留保底域没覆盖的两条，避免同一 opCode 每拍发两遍。

/**
 * 第二档（120s）：底栏状态 / 最大大剂量。
 * 都是「一条事件才变一次」的量，降到 2 分钟一次不会影响可读性。
 */
val dashboardTier2Commands: List<Message>
    get() = listOf(
        HomeScreenMirrorRequest(),
        GlobalMaxBolusSettingsRequest(),
    ).filter { msg -> !msg.props().minApi.get().greaterThan(apiVersion()) }

/**
 * 第三档（300s）：血糖图形值。
 * EGV 只在要画图时才需要，所以留在「页面可见」的域里；
 * 会话状态、闭环状态、历史日志状态都已由 300s 保底域覆盖。
 */
val dashboardTier3Commands: List<Message>
    get() = listOf(
        CurrentEGVGuiDataRequest(),
    ).filter { msg ->
        !msg.props().minApi.get().greaterThan(apiVersion()) &&
            (cgmQueryAllowed() || !msg.isCgmQuery())
    }

val dashboardFields = listOf(
    dataStore.batteryPercent,
    dataStore.iobUnits,
    dataStore.cartridgeRemainingUnits,
    dataStore.lastBolusStatus,
    dataStore.controlIQStatus,
    dataStore.controlIQMode,
    dataStore.basalRate,
    dataStore.cgmSessionState,
    dataStore.cgmTransmitterStatus,
    dataStore.cgmReading,
    dataStore.cgmDeltaArrow,
    dataStore.notificationBundle
)

@Preview(showBackground = true)
@Composable
internal fun DashboardDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Dashboard(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
            )
        }
    }
}
