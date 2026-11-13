@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class
)

package com.jwoglom.controlx2.presentation.screens.sections

import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.scheduled.ScheduledBolusChain
import com.pumppan.controlx2.scheduled.ScheduledBolusRuntime
import com.pumppan.controlx2.ui.ActionRow
import com.pumppan.controlx2.ui.DangerousRowMinHeight
import com.pumppan.controlx2.ui.NavigationRow
import com.pumppan.controlx2.ui.ToggleRow

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.DisposableEffect
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
import com.pumppan.controlx2.pumpModelDisplayName
import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.jwoglom.controlx2.shared.util.canControlPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.builders.ControlIQInfoRequestBuilder
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.request.control.ChangeTimeDateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.PlaySoundRequest
import com.jwoglom.pumpx2.pump.messages.request.control.ResumePumpingRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetModesRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetTempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.StopTempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SuspendPumpingRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.LoadStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TempRateRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LoadStatusResponse
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

/** 分组边界的实线。 */
@Composable
private fun GroupBoundary() = HorizontalDivider(
    thickness = 1.5.dp,
    color = MaterialTheme.colorScheme.outline,
)

/** 组内各功能之间的灰色细分隔线。 */
@Composable
private fun GroupDivider() = HorizontalDivider(
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant,
)

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
    var showSyncTimeDialog by remember { mutableStateOf(false) }
    var showPlaySoundDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()
    val pumpModel = determinePumpModel(deviceName.value ?: "")

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }
    var checkingResumeLoadStatus by remember { mutableStateOf(false) }

    val loadStatusResponse = ds.loadStatusResponse.observeAsState()
    val resumeGuidance = remember(loadStatusResponse.value, checkingResumeLoadStatus) {
        resolveResumeInsulinGuidance(context, loadStatusResponse.value, checkingResumeLoadStatus)
    }

    fun requestResumeLoadStatusCheck() {
        // 0x14 是 Mobi 专有：X2 会回 ErrorResponse 并被泵主动断链（2026-11-13 logcat 取证）。
        // 该行在 X2 上本已 readOnly，这里再挡一层，防止误触或以后放开。
        if (!canControlPump(pumpModel, ResumePumpingRequest::class.java)) return
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

    // 页面离开组合时退订。导航库的 onStop 在 back stack 场景不一定触发，
    // 只靠 LifecycleStateObserver 会留下「页面关了、协调器还在轮询」的泄漏。
    DisposableEffect(Unit) {
        onDispose { QueryCoordinatorHolder.setVisible("PUMP_STATE", false) }
    }

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        QueryCoordinatorHolder.setVisible("PUMP_STATE", false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading Actions from onStart lifecyclestate")
        QueryCoordinatorHolder.setVisible("PUMP_STATE", true)
        fetchDataStoreFields(SendType.STANDARD)
    }

    // 周期刷新交给 QueryCoordinator（PUMP_STATE 域，HomeMirror + CIQInfo + TempRate 与本页
    // 原批次完全一致）；页面只保留进页面首刷与下拉刷新。

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
                    HeaderLine(stringResource(R.string.actions_actions))
                    Divider()

                    val model = determinePumpModel(deviceName.value ?: "")
                    if (model == KnownDeviceModel.TSLIM_X2) {
                        Line(stringResource(R.string.actions_not_supported_remote_bolus_only, pumpModelDisplayName(model)))
                        Line("")
                    }
                }
                item {
                    com.pumppan.controlx2.PumppanModelOverrideBanner()
                }
                item { GroupBoundary() }
                // 定时大剂量总开关：控制「大剂量 V2」里 [定时] 标签的可见性。
                // 关掉即立刻作废正在跑的链（用户口径：标签隐藏 = 链关闭）。

                item {
                    val basalStatus = ds.basalStatus.observeAsState()
                    val canPumpControl = canControlPump(determinePumpModel(deviceName.value ?: ""), SuspendPumpingRequest::class.java)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ActionRow(
                            title = when (basalStatus.value) {
                                BasalStatus.UNKNOWN, null -> context.getString(R.string.actions_stop_start_insulin)
                                BasalStatus.PUMP_SUSPENDED -> context.getString(R.string.actions_start_insulin)
                                else -> context.getString(R.string.actions_stop_insulin)
                            },
                            subtitle = when (basalStatus.value) {
                                BasalStatus.UNKNOWN, null -> context.getString(R.string.actions_stop_or_resume_insulin_deliveries)
                                BasalStatus.PUMP_SUSPENDED -> context.getString(R.string.actions_resume_insulin_deliveries)
                                else -> context.getString(R.string.actions_stop_insulin_deliveries)
                            },
                            leadingIcon = {
                                Icon(
                                    when (basalStatus.value) {
                                        BasalStatus.UNKNOWN, null -> Icons.Filled.Close
                                        BasalStatus.PUMP_SUSPENDED -> Icons.Filled.PlayArrow
                                        else -> Icons.Filled.Close
                                    },
                                    contentDescription = null,
                                )
                            },
                            emphasized = basalStatus.value == BasalStatus.PUMP_SUSPENDED,
                            readOnly = !canPumpControl,
                                modifier = Modifier.heightIn(min = DangerousRowMinHeight),
                            onClick = {
                                when (basalStatus.value) {
                                    BasalStatus.UNKNOWN, null -> {}
                                    BasalStatus.PUMP_SUSPENDED -> {
                                        requestResumeLoadStatusCheck()
                                        showResumeInsulinMenu = true
                                    }
                                    else -> { showSuspendInsulinMenu = true }
                                }
                            },
                        )
                        DropdownMenu(
                            expanded = showResumeInsulinMenu,
                            onDismissRequest = { showResumeInsulinMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {

                            AlertDialog(
                                onDismissRequest = {},
                                title = {
                                    Text(stringResource(R.string.actions_resume_insulin))
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
                                        Text(stringResource(R.string.actions_cancel))
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
                                        Text(stringResource(R.string.actions_resume_insulin))
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
                                    Text(stringResource(R.string.actions_stop_insulin_2))
                                },
                                text = {
                                    Text(stringResource(R.string.actions_suspend_all_insulin_deliveries_query))
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showSuspendInsulinMenu = false
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text(stringResource(R.string.actions_cancel))
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
                                        Text(stringResource(R.string.actions_stop_insulin_2))
                                    }
                                }
                            )

                        }

                    }
                }

                item { GroupDivider() }


                item {
                    val controlIQMode = ds.controlIQMode.observeAsState()
                    val canSetModes = canControlPump(determinePumpModel(deviceName.value ?: ""), SetModesRequest::class.java)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ToggleRow(
                            title = when (controlIQMode.value) {
                                UserMode.EXERCISE -> context.getString(R.string.actions_disable_exercise_mode)
                                else -> context.getString(R.string.actions_enable_exercise_mode)
                            },
                            checked = controlIQMode.value == UserMode.EXERCISE,
                            emphasized = controlIQMode.value == UserMode.EXERCISE,
                            readOnly = !canSetModes,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = null,
                                )
                            },
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
                                                    context.getString(R.string.actions_exercise_mode_blocked_other_active),
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
                                                    context.getString(R.string.actions_cannot_disable_exercise_mode_inactive),
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
                }

                item { GroupDivider() }


                item {
                    val controlIQMode = ds.controlIQMode.observeAsState()
                    val canSetModes = canControlPump(determinePumpModel(deviceName.value ?: ""), SetModesRequest::class.java)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        ToggleRow(
                            title = when (controlIQMode.value) {
                                UserMode.SLEEP -> context.getString(R.string.actions_disable_sleep_mode)
                                else -> context.getString(R.string.actions_enable_sleep_mode)
                            },
                            checked = controlIQMode.value == UserMode.SLEEP,
                            emphasized = controlIQMode.value == UserMode.SLEEP,
                            readOnly = !canSetModes,
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Bedtime,
                                    contentDescription = null,
                                )
                            },
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
                                                    context.getString(R.string.actions_sleep_mode_blocked_other_active),
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
                                                    context.getString(R.string.actions_cannot_disable_sleep_mode_inactive),
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
                }

                item { GroupDivider() }


                item {
                    val tempRateActive = ds.tempRateActive.observeAsState()
                    val tempRateDetails = ds.tempRateDetails.observeAsState()
                    val canTempRate = canControlPump(determinePumpModel(deviceName.value ?: ""), SetTempRateRequest::class.java)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        fun prettyDuration(minutes: Long?): String {
                            return "${minutes?.div(60)}h${minutes?.rem(60)}m"
                        }

                        ActionRow(
                            title = when (tempRateActive.value) {
                                true -> context.getString(R.string.actions_stop_temp_rate)
                                else -> context.getString(R.string.actions_start_temp_rate)
                            },
                            subtitle = when (tempRateActive.value) {
                                true -> context.getString(R.string.actions_active_for_at, (tempRateDetails.value?.percentage ?: ""), (prettyDuration(tempRateDetails.value?.duration?.div(60)) ?: ""), (tempRateDetails.value?.startTimeInstant ?: ""))
                                else -> null
                            },
                            leadingIcon = {
                                Icon(
                                    when (tempRateActive.value) {
                                        true -> Icons.Filled.Cancel
                                        else -> Icons.Filled.EditNote
                                    },
                                    contentDescription = null,
                                )
                            },
                            emphasized = tempRateActive.value == true,
                            readOnly = !canTempRate,
                                modifier = Modifier.heightIn(min = DangerousRowMinHeight),
                            onClick = {
                                when (tempRateActive.value) {
                                    true -> { showStopTempRateMenu = true }
                                    false -> { openTempRateWindow() }
                                    else -> {}
                                }
                            },
                        )
                        DropdownMenu(
                            expanded = showStopTempRateMenu,
                            onDismissRequest = { showStopTempRateMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {

                            AlertDialog(
                                onDismissRequest = { showStopTempRateMenu = false },
                                title = {
                                    Text(stringResource(R.string.actions_stop_temp_rate))
                                },
                                text = {
                                    Text(stringResource(R.string.actions_stop_active_temp_rate_for_beginning, (tempRateDetails.value?.percentage ?: ""), (prettyDuration(tempRateDetails.value?.duration?.div(60)) ?: ""), (tempRateDetails.value?.startTimeInstant ?: "")))
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showStopTempRateMenu = false
                                        },
                                        modifier = Modifier.padding(top = 16.dp)
                                    ) {
                                        Text(stringResource(R.string.actions_cancel))
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
                                        Text(stringResource(R.string.actions_stop_temp_rate_2))
                                    }
                                }
                            )

                        }
                    }
                }

                item { GroupBoundary() }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_cartridge_settings),
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { navigateToSection(LandingSection.CARTRIDGE_ACTIONS) },
                        )
                    }
                }
                item { GroupDivider() }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_cgm_settings),
                            leadingIcon = { Icon(Icons.Filled.DevicesOther, contentDescription = null) },
                            onClick = { navigateToSection(LandingSection.CGM_ACTIONS) },
                        )
                    }
                }
                item { GroupDivider() }


                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_profile_settings),
                            leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                            onClick = { navigateToSection(LandingSection.PROFILE_ACTIONS) },
                        )
                    }
                }
                item { GroupDivider() }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = stringResource(R.string.sound_quick_bolus_settings),
                            leadingIcon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                            onClick = { navigateToSection(LandingSection.QUICK_BOLUS_SETTINGS_ACTIONS) },
                        )
                    }
                }
                item { GroupDivider() }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_sound_settings),
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            },
                            onClick = { navigateToSection(LandingSection.SOUND_SETTINGS_ACTIONS) },
                        )
                    }
                }
                item { GroupDivider() }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_ciq_settings),
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { navigateToSection(LandingSection.CONTROLIQ_SETTINGS_ACTIONS) },
                        )
                    }
                }
                item { GroupBoundary() }

                // X2 只能远程操作大剂量，泵设置类动作（改时钟/让泵响）在 X2 上不可触发
                val canPumpSettings = canControlPump(determinePumpModel(deviceName.value ?: ""))

                item {
                    ActionRow(
                        title = context.getString(R.string.settings_sync_pump_time),
                        subtitle = context.getString(R.string.settings_set_pump_s_clock_to),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Timer,
                                contentDescription = context.getString(R.string.settings_sync_time_icon),
                            )
                        },
                        readOnly = !canPumpSettings,
                        onClick = { showSyncTimeDialog = true },
                    )
                    GroupDivider()
                }

                item {
                    ActionRow(
                        title = context.getString(R.string.settings_find_my_pump),
                        subtitle = context.getString(R.string.settings_play_sound_on_pump_to_help_locate),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = context.getString(R.string.settings_play_sound_icon),
                            )
                        },
                        readOnly = !canPumpSettings,
                        onClick = { showPlaySoundDialog = true },
                    )
                    GroupBoundary()
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        NavigationRow(
                            title = context.getString(R.string.actions_safety_limits),
                            leadingIcon = {
                                Icon(Icons.Filled.HealthAndSafety, contentDescription = null)
                            },
                            modifier = Modifier.heightIn(min = DangerousRowMinHeight),
                            onClick = { navigateToSection(LandingSection.SAFETY_LIMITS_ACTIONS) },
                        )
                    }
                }
                item { GroupBoundary() }
            }
        )
    }

    // Sync Time Dialog
    if (showSyncTimeDialog) {
        AlertDialog(
            onDismissRequest = { showSyncTimeDialog = false },
            title = { Text(context.getString(R.string.settings_sync_pump_time_2)) },
            text = { Text(context.getString(R.string.settings_set_pump_s_internal_clock_to_query)) },
            confirmButton = {
                TextButton(onClick = {
                    sendPumpCommands(
                        SendType.STANDARD,
                        listOf(ChangeTimeDateRequest(Instant.now()))
                    )
                    showSyncTimeDialog = false
                    Toast.makeText(context, context.getString(R.string.settings_pump_time_sync_sent), Toast.LENGTH_SHORT).show()
                }) {
                    Text(context.getString(R.string.settings_sync))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncTimeDialog = false }) {
                    Text(context.getString(R.string.settings_cancel))
                }
            }
        )
    }

    // Play Sound Dialog
    if (showPlaySoundDialog) {
        AlertDialog(
            onDismissRequest = { showPlaySoundDialog = false },
            title = { Text(context.getString(R.string.settings_find_my_pump_2)) },
            text = { Text(context.getString(R.string.settings_play_sound_on_pump_query)) },
            confirmButton = {
                TextButton(onClick = {
                    sendPumpCommands(
                        SendType.STANDARD,
                        listOf(PlaySoundRequest())
                    )
                    showPlaySoundDialog = false
                    Toast.makeText(context, context.getString(R.string.settings_playing_sound_on_pump), Toast.LENGTH_SHORT).show()
                }) {
                    Text(context.getString(R.string.settings_play_sound))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlaySoundDialog = false }) {
                    Text(context.getString(R.string.settings_cancel))
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
    context: Context,
    loadStatus: LoadStatusResponse?,
    checking: Boolean,
): ResumeInsulinGuidance {
    if (checking && loadStatus == null) {
        return ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_checking_pump_load_state),
        )
    }
    if (loadStatus == null) {
        return ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_read_load_state_failed_try_again),
        )
    }

    return when (loadStatus.getLoadState()) {
        LoadStatusResponse.LoadState.CHANGE_CARTRIDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_finish_change_cartridge_first),
        )
        LoadStatusResponse.LoadState.LOAD_CARTRIDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_complete_fill_tubing_resume),
        )
        LoadStatusResponse.LoadState.PRIME_TUBING -> {
            val nextAction = when (loadStatus.getPrimeTubingStatus()) {
                LoadStatusResponse.PrimeTubingStatus.ENTERED_CANNOT_EXIT -> context.getString(R.string.actions_hold_pump_button_to_fill_tubing)
                LoadStatusResponse.PrimeTubingStatus.ENTERED_CAN_EXIT -> context.getString(R.string.actions_exit_fill_tubing_mode_on_pump)
                LoadStatusResponse.PrimeTubingStatus.SUSPENDED -> context.getString(R.string.actions_resume_and_complete_fill_tubingn_exit)
                else -> context.getString(R.string.actions_complete_fill_tubing_and_exit_mode)
            }
            ResumeInsulinGuidance(
                canResume = false,
                message = context.getString(R.string.actions_next_actionn_try_resume_insulin_again, nextAction),
            )
        }
        LoadStatusResponse.LoadState.PRIME_NUDGE -> ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_fill_cannula_first_try_resume),
        )
        LoadStatusResponse.LoadState.PRIME_CANNULA -> {
            if (loadStatus.getIsLoadingActive()) {
                ResumeInsulinGuidance(
                    canResume = false,
                    message = context.getString(R.string.actions_complete_fill_cannula_resume),
                )
            } else {
                ResumeInsulinGuidance(
                    canResume = true,
                    message = context.getString(R.string.actions_resume_all_insulin_deliveries_query),
                )
            }
        }
        LoadStatusResponse.LoadState.INVALID -> ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_load_state_invalid_finish_loading),
        )
        LoadStatusResponse.LoadState.UNKNOWN -> ResumeInsulinGuidance(
            canResume = false,
            message = context.getString(R.string.actions_load_state_unknown_finish_load_steps),
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
