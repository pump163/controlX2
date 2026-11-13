@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class
)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Observer
import androidx.navigation.NavHostController
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.ledger.PumppanBolusLedgerCard
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.db.historylog.HistoryLogViewModel
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.Line
import com.jwoglom.controlx2.presentation.components.LoadSpinner
import com.jwoglom.controlx2.presentation.screens.sections.components.NotificationItem
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.shared.capability.Capability
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.pumppan.controlx2.pumpModelDisplayName
import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.jwoglom.controlx2.shared.util.canControlPump

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.models.NotificationBundle
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun Notifications(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    historyLogViewModel: HistoryLogViewModel? = null
) {
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()
    // X2 只能远程操作大剂量，无法远程消除泵上通知（真机验证）
    val canDismissNotifications = canControlPump(determinePumpModel(deviceName.value ?: ""), Capability.NOTIFICATION_DISMISS)

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, notificationsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = notificationsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("Notifications loading: remaining ${nullFields.size}: ${notificationsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("Notifications loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("Notifications loading done: ${notificationsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading Notifications with force")
        refreshing = true

        notificationsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        // 退订：页面不可见时协调器不再每 60s 查通知包（策略 NOTIFICATIONS.onlyWhenVisible）。
        QueryCoordinatorHolder.setVisible("NOTIFICATIONS", false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading Notifications from onStart lifecyclestate")
        QueryCoordinatorHolder.setVisible("NOTIFICATIONS", true)
        fetchDataStoreFields(SendType.STANDARD)
    }

    // 页面真正离开组合时才退订。导航库的 onStop 在这里并不总触发（详情页返回后
    // 上一页仍留在 back stack），只靠 LifecycleStateObserver 会留下「页面已经关了、
    // 协调器还以为你在看」的泄漏。
    DisposableEffect(Unit) {
        onDispose { QueryCoordinatorHolder.setVisible("NOTIFICATIONS", false) }
    }

    // 周期刷新已交给 QueryCoordinator（NOTIFICATIONS 域，5s 节拍按 60s 到期判定）；
    // 页面只保留进页面首刷与下拉刷新两条直发路径。
    LaunchedEffect(refreshing) {
        waitForLoaded()
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

        val notifications = remember { mutableStateListOf<Any>()}
        ds.notificationBundle.observe(LocalLifecycleOwner.current, Observer {
            ds.notificationBundle.value?.let {
                notifications.clear()
                notifications.addAll(it.get().toTypedArray())
            }
        })

        val deviceModel = determinePumpModel(deviceName.value ?: "")

        LazyColumn(
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp),
            content = {
                item {
                    HeaderLine(stringResource(R.string.notifications_notifications))
                    Divider()

                    if (deviceModel == KnownDeviceModel.TSLIM_X2) {
                        Line(stringResource(R.string.notifications_cannot_dismiss_on_model, pumpModelDisplayName(deviceModel)))
                        Line("")
                    }
                }

                // 大剂量流水卡：报警区之后、通知列表之前；仅 X2 + 开关开启时出现（设计 §6 / §19.3）。
                // 数据只来自进程内 BolusLedger，无历史库依赖，也不发任何泵请求。
                if (deviceModel == KnownDeviceModel.TSLIM_X2 && PumppanPrefs.ledgerCard(context)) {
                    item { PumppanBolusLedgerCard() }
                }

                Timber.i("Notifications fetched: ${notifications}")
                if (refreshing) {
                    item {
                        LoadSpinner(stringResource(R.string.notifications_loading_notifications))
                    }
                } else if (notifications.isEmpty()) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(64.dp))
                            Line(stringResource(R.string.notifications_no_notifications), style = TextStyle(textAlign = TextAlign.Center))
                        }
                    }
                }
                notifications.forEach {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(Alignment.TopStart)
                        ) {
                            NotificationItem(it, sendPumpCommands=sendPumpCommands, ::refresh,
                                dismissAllowed = canDismissNotifications)
                        }
                    }
                }
            }
        )
    }
}

fun notificationsApiVersion(): ApiVersion {
    var apiVersion = PumpState.getPumpAPIVersion()
    if (apiVersion == null) {
        apiVersion = KnownApiVersion.API_V2_5.get()
    }
    return apiVersion
}

val notificationsCommands = listOf(
    HomeScreenMirrorRequest(),
    *NotificationBundle.allRequests().toTypedArray()
).filter { msg ->
    !msg.props().minApi.get().greaterThan(notificationsApiVersion())
}

val notificationsFields = listOf(
    dataStore.notificationBundle
)

@Preview(showBackground = true)
@Composable
internal fun NotificationsDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Notifications(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun NotificationsDefaultPreview_WithNotifications() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            LocalDataStore.current.cgmSetupG6TxId.value = "ABC123"
            LocalDataStore.current.cgmSetupG6SensorCode.value = "1234"
            Notifications(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
            )
        }
    }
}