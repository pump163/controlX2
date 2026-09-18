@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class
)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.db.historylog.HistoryLogViewModel
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.Line
import com.jwoglom.controlx2.presentation.screens.LandingSection
import com.jwoglom.controlx2.presentation.screens.TempRatePreview
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.shared.enums.BasalStatus
import com.jwoglom.controlx2.shared.enums.UserMode
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.builders.ControlIQInfoRequestBuilder
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.request.control.ResumePumpingRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetModesRequest
import com.jwoglom.pumpx2.pump.messages.request.control.StopTempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SuspendPumpingRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.LoadStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TempRateRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LoadStatusResponse
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun Actions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    historyLogViewModel: HistoryLogViewModel? = null,
    _resumeInsulinMenuState: Boolean = false,
    _suspendInsulinMenuState: Boolean = false,
    _stopTempRateMenuState: Boolean = false,
    openTempRateWindow: () -> Unit,
    navigateToSection: (section: LandingSection) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var showResumeInsulinMenu by remember { mutableStateOf(_resumeInsulinMenuState) }
    var showSuspendInsulinMenu by remember { mutableStateOf(_suspendInsulinMenuState) }
    var showStopTempRateMenu by remember { mutableStateOf(_stopTempRateMenuState) }

    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }
    var checkingResumeLoadStatus by remember { mutableStateOf(false) }

    val loadStatusResponse = ds.loadStatusResponse.observeAsState()
    val resumeGuidance = remember(loadStatusResponse.value, checkingResumeLoadStatus) {
        resolveResumeInsulinGuidance(loadStatusResponse.value, checkingResumeLoadStatus)
    }

    fun requestResumeLoadStatusCheck() {
        checkingResumeLoadStatus = true
        ds.loadStatusResponse.value = null
        sendPumpCommands(SendType.BUST_CACHE, listOf(LoadStatusRequest()))
    }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, actionsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = actionsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("Actions loading: remaining ${nullFields.size}: ${actionsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("Actions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("Actions loading done: ${actionsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading Actions with force")
        refreshing = true

        actionsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        refreshScope.cancel()
    }) {
        Timber.i("reloading Actions from onStart lifecyclestate")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(intervalOf(60)) {
        Timber.i("reloading Actions from interval")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    LaunchedEffect(loadStatusResponse.value) {
        if (loadStatusResponse.value != null) {
            checkingResumeLoadStatus = false
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
                .padding(horizontal = 0.dp),
            content = {
                item {
                    HeaderLine("控制")
                    Divider()

                    val model = determinePumpModel(deviceName.value ?: "")
                    if (model == KnownDeviceModel.TSLIM_X2) {
                        Line("此型号的设备不支持部分控制（${model}），仅支持远程大剂量。")
                        Line("")
                    }
                }
                item {
                    val basalStatus = ds.basalStatus.observeAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                when (basalStatus.value) {
                                    BasalStatus.UNKNOWN, null -> "停止/启动胰岛素"
                                    BasalStatus.PUMP_SUSPENDED -> "启动胰岛素"
                                    else -> "停止胰岛素"
                                }
                            )},
                            supportingContent = { Text(
                                when (basalStatus.value) {
                                    BasalStatus.UNKNOWN, null -> "停止或恢复胰岛素输注"
                                    BasalStatus.PUMP_SUSPENDED -> "恢复胰岛素输注"
                                    else -> "停止胰岛素输注"
                                }
                            ) },
                            leadingContent = {
                                Icon(
                                    when (basalStatus.value) {
                                        BasalStatus.UNKNOWN, null -> Icons.Filled.Close
                                        BasalStatus.PUMP_SUSPENDED -> Icons.Filled.PlayArrow
                                        else -> Icons.Filled.Close
                                    },
                                    contentDescription = null,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = when (basalStatus.value) {
                                    BasalStatus.UNKNOWN, null -> ListItemDefaults.containerColor
                                    BasalStatus.PUMP_SUSPENDED -> Color.Green.copy(alpha = 0.5F)
                                    else -> Color.Red.copy(alpha = 0.5F)
                                }
                            ),
                            modifier = Modifier.clickable {
                                when (basalStatus.value) {
                                    BasalStatus.UNKNOWN, null -> {}
                                    BasalStatus.PUMP_SUSPENDED -> {
                                        requestResumeLoadStatusCheck()
                                        showResumeInsulinMenu = true
                                    }
                                    else -> {showSuspendInsulinMenu = true}
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showResumeInsulinMenu,
                            onDismissRequest = { showResumeInsulinMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {

                            AlertDialog(
                                onDismissRequest = {},
                                title = {
                                    Text("恢复胰岛素")
                                },
                                text = {
                                    Text(resumeGuidance.message)
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showResumeInsulinMenu = false
                                            checkingResumeLoadStatus = false
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("取消")
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = resumeGuidance.canResume,
                                        onClick = {
                                            showResumeInsulinMenu = false
                                            checkingResumeLoadStatus = false
                                            if (resumeGuidance.canResume) {
                                                sendPumpCommands(SendType.BUST_CACHE, listOf(ResumePumpingRequest()))
                                                refreshScope.launch {
                                                    repeat(5) {
                                                        delay(1000)
                                                        sendPumpCommands(
                                                            SendType.BUST_CACHE,
                                                            listOf(HomeScreenMirrorRequest())
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("恢复胰岛素")
                                    }
                                }
                            )

                        }

                        DropdownMenu(
                            expanded = showSuspendInsulinMenu,
                            onDismissRequest = { showSuspendInsulinMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {

                            AlertDialog(
                                onDismissRequest = {},
                                title = {
                                    Text("停止胰岛素")
                                },
                                text = {
                                    Text("暂停所有胰岛素输注？")
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showSuspendInsulinMenu = false
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("取消")
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showSuspendInsulinMenu = false
                                            sendPumpCommands(SendType.BUST_CACHE, listOf(SuspendPumpingRequest()))
                                            refreshScope.launch {
                                                repeat(5) {
                                                    delay(1000)
                                                    sendPumpCommands(
                                                        SendType.BUST_CACHE,
                                                        listOf(HomeScreenMirrorRequest())
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("停止胰岛素")
                                    }
                                }
                            )

                        }

                    }
                }

                item {
                    Line("\n")
                }


                item {
                    val controlIQMode = ds.controlIQMode.observeAsState()
                    val isMobi = determinePumpModel(deviceName.value ?: "") == KnownDeviceModel.MOBI
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                when (controlIQMode.value) {
                                    UserMode.EXERCISE -> "关闭运动模式"
                                    else -> "开启运动模式"
                                }
                            )},
                            leadingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = controlIQMode.value == UserMode.EXERCISE,
                                    enabled = isMobi,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            // User toggling to ON
                                            if (controlIQMode.value == UserMode.NONE) {
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(
                                                        SetModesRequest(SetModesRequest.ModeCommand.EXERCISE_MODE_ON)
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "无法开启运动模式，因为其他用户模式正在运行",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } else {
                                            // User toggling to OFF
                                            if (controlIQMode.value == UserMode.EXERCISE) {
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(
                                                        SetModesRequest(SetModesRequest.ModeCommand.EXERCISE_MODE_OFF)
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "运动模式当前未运行，无法关闭",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }

                                        // Refresh logic
                                        refreshScope.launch {
                                            repeat(5) {
                                                delay(1000)
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(ControlIQInfoRequestBuilder.create(apiVersion()))
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }

                item {
                    Divider()
                }


                item {
                    val controlIQMode = ds.controlIQMode.observeAsState()
                    val isMobi = determinePumpModel(deviceName.value ?: "") == KnownDeviceModel.MOBI
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                when (controlIQMode.value) {
                                    UserMode.SLEEP -> "关闭睡眠模式"
                                    else -> "开启睡眠模式"
                                }
                            )},
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Bedtime,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = controlIQMode.value == UserMode.SLEEP,
                                    enabled = isMobi,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            // User toggling to ON
                                            if (controlIQMode.value == UserMode.NONE) {
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(
                                                        SetModesRequest(SetModesRequest.ModeCommand.SLEEP_MODE_ON)
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "无法开启睡眠模式，因为其他用户模式正在运行",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } else {
                                            // User toggling to OFF
                                            if (controlIQMode.value == UserMode.SLEEP) {
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(
                                                        SetModesRequest(SetModesRequest.ModeCommand.SLEEP_MODE_OFF)
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "睡眠模式当前未运行，无法关闭",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }

                                        // Refresh logic
                                        refreshScope.launch {
                                            delay(250)
                                            sendPumpCommands(
                                                SendType.BUST_CACHE,
                                                listOf(ControlIQInfoRequestBuilder.create(apiVersion()))
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }

                item {
                    Line("\n")
                }


                item {
                    val tempRateActive = ds.tempRateActive.observeAsState()
                    val tempRateDetails = ds.tempRateDetails.observeAsState()
                    val isMobi = determinePumpModel(deviceName.value ?: "") == KnownDeviceModel.MOBI
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        fun prettyDuration(minutes: Long?): String {
                            return "${minutes?.div(60)}h${minutes?.rem(60)}m"
                        }

                        ListItem(
                            headlineContent = { Text(
                                when (tempRateActive.value) {
                                    true -> "停止临时基础率"
                                    else -> "启动临时基础率"
                                }
                            )},
                            supportingContent = { 
                                when (tempRateActive.value) {
                                    true -> Text("进行中：${tempRateDetails.value?.percentage}%，持续 ${prettyDuration(tempRateDetails.value?.duration?.div(60))}，开始于 ${tempRateDetails.value?.startTimeInstant}")
                                    else -> null
                                }
                            },
                            leadingContent = {
                                Icon(
                                    when (tempRateActive.value) {
                                        true -> Icons.Filled.Cancel
                                        else -> Icons.Filled.EditNote
                                    },
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable(enabled = isMobi) {
                                when (tempRateActive.value) {
                                    true -> { showStopTempRateMenu = true }
                                    false -> { openTempRateWindow() }
                                    else -> {}
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showStopTempRateMenu,
                            onDismissRequest = { showStopTempRateMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {

                            AlertDialog(
                                onDismissRequest = { showStopTempRateMenu = false },
                                title = {
                                    Text("停止临时基础率")
                                },
                                text = {
                                    Text("停止进行中的临时基础率：${tempRateDetails.value?.percentage}%，持续 ${prettyDuration(tempRateDetails.value?.duration?.div(60))}，开始于 ${tempRateDetails.value?.startTimeInstant}")
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showStopTempRateMenu = false
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("取消")
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            refreshScope.launch {
                                                showStopTempRateMenu = false
                                                sendPumpCommands(SendType.BUST_CACHE, listOf(StopTempRateRequest()))
                                                delay(250)
                                                sendPumpCommands(
                                                    SendType.BUST_CACHE,
                                                    listOf(TempRateRequest())
                                                )
                                            }
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text("停止临时基础率")
                                    }
                                }
                            )

                        }
                    }
                }

                item {
                    Line("\n")
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "储药器设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Settings, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.CARTRIDGE_ACTIONS)
                            }
                        )
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "CGM 设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.DevicesOther, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.CGM_ACTIONS)
                            }
                        )
                    }
                }


                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "配置文件设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Create, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.PROFILE_ACTIONS)
                            }
                        )
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "快捷大剂量设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Bolt, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.QUICK_BOLUS_SETTINGS_ACTIONS)
                            }
                        )
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "声音设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.SOUND_SETTINGS_ACTIONS)
                            }
                        )
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "Control-IQ 设置"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Settings, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.CONTROLIQ_SETTINGS_ACTIONS)
                            }
                        )
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ListItem(
                            headlineContent = { Text(
                                "安全限制"
                            )},
                            supportingContent = {
                            },
                            leadingContent = {
                                Icon(Icons.Filled.HealthAndSafety, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                navigateToSection(LandingSection.SAFETY_LIMITS_ACTIONS)
                            }
                        )
                    }
                }
            }
        )
    }
}

private data class ResumeInsulinGuidance(
    val canResume: Boolean,
    val message: String,
)

private fun resolveResumeInsulinGuidance(
    loadStatus: LoadStatusResponse?,
    checking: Boolean,
): ResumeInsulinGuidance {
    if (checking && loadStatus == null) {
        return ResumeInsulinGuidance(
            canResume = false,
            message = "正在检查胰岛素泵加载状态...",
        )
    }
    if (loadStatus == null) {
        return ResumeInsulinGuidance(
            canResume = false,
            message = "无法读取胰岛素泵加载状态，请重试。",
        )
    }

    return when (loadStatus.getLoadState()) {
        LoadStatusResponse.LoadState.CHANGE_CARTRIDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = "请先完成更换储药器。",
        )
        LoadStatusResponse.LoadState.LOAD_CARTRIDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = "请先完成充盈导管，然后再试恢复胰岛素。",
        )
        LoadStatusResponse.LoadState.PRIME_TUBING -> {
            val nextAction = when (loadStatus.getPrimeTubingStatus()) {
                LoadStatusResponse.PrimeTubingStatus.ENTERED_CANNOT_EXIT -> "长按胰岛素泵按钮充盈导管，然后退出充盈导管模式。"
                LoadStatusResponse.PrimeTubingStatus.ENTERED_CAN_EXIT -> "在胰岛素泵上退出充盈导管模式。"
                LoadStatusResponse.PrimeTubingStatus.SUSPENDED -> "恢复并完成充盈导管，然后退出该模式。"
                else -> "完成充盈导管并退出充盈导管模式。"
            }
            ResumeInsulinGuidance(
                canResume = false,
                message = "$nextAction 然后再试恢复胰岛素。",
            )
        }
        LoadStatusResponse.LoadState.PRIME_NUDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = "请先充盈插管，然后再试恢复胰岛素。",
        )
        LoadStatusResponse.LoadState.PRIME_CANNULA -> {
            if (loadStatus.getIsLoadingActive()) {
                ResumeInsulinGuidance(
                    canResume = false,
                    message = "请先完成充盈插管，然后再试恢复胰岛素。",
                )
            } else {
                ResumeInsulinGuidance(
                    canResume = true,
                    message = "恢复所有胰岛素输注？",
                )
            }
        }
        LoadStatusResponse.LoadState.INVALID -> ResumeInsulinGuidance(
            canResume = false,
            message = "胰岛素泵加载状态无效。请先在胰岛素泵上完成储药器加载步骤。",
        )
        LoadStatusResponse.LoadState.UNKNOWN -> ResumeInsulinGuidance(
            canResume = false,
            message = "胰岛素泵加载状态未知。请先在胰岛素泵上完成所有待处理的加载步骤。",
        )
    }
}

val actionsCommands = listOf(
    HomeScreenMirrorRequest(),
    ControlIQInfoRequestBuilder.create(apiVersion()),
    TempRateRequest()
)

val actionsFields = listOf(
    dataStore.basalStatus,
    dataStore.controlIQMode,
    dataStore.tempRateActive,
    dataStore.tempRateDetails,
)

@Preview(showBackground = true)
@Composable
internal fun ActionsDefaultPreviewInsulinActive() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Actions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                openTempRateWindow = {},
                navigateToSection = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun ActionsDefaultPreviewInsulinActive_StopMenuOpen() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Actions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                _suspendInsulinMenuState = true,
                openTempRateWindow = {},
                navigateToSection = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun ActionsDefaultPreviewInsulinSuspended() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            LocalDataStore.current.basalStatus.value = BasalStatus.PUMP_SUSPENDED
            Actions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                openTempRateWindow = {},
                navigateToSection = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun ActionsDefaultPreviewInsulinSuspended_ResumeMenuOpen() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            LocalDataStore.current.basalStatus.value = BasalStatus.PUMP_SUSPENDED
            Actions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                _resumeInsulinMenuState = true,
                openTempRateWindow = {},
                navigateToSection = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun ActionsDefaultPreview_StartTempRate() {
    TempRatePreview()
}
