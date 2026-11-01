@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class,
    ExperimentalMaterialApi::class
)

package com.jwoglom.controlx2.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.BottomSheetScaffold
import androidx.compose.material.BottomSheetValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.rememberBottomSheetState
import androidx.compose.material.rememberBottomSheetScaffoldState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.calculator.BolusCalcUnits
import com.jwoglom.pumpx2.pump.messages.calculator.BolusParameters
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.BolusCalcDataSnapshotResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.TimeSinceResetResponse
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.db.historylog.HistoryLogViewModel
import com.jwoglom.controlx2.presentation.DataStore
import com.jwoglom.controlx2.presentation.navigation.BolusInputPrefill
import com.jwoglom.controlx2.presentation.screens.sections.Actions
import com.jwoglom.controlx2.presentation.screens.sections.BolusWindow
import com.jwoglom.controlx2.presentation.screens.sections.CGMActions
import com.jwoglom.controlx2.presentation.screens.sections.CartridgeActions
import com.jwoglom.controlx2.presentation.screens.sections.ControlIQSettingsActions
import com.jwoglom.controlx2.presentation.screens.sections.FeatureFlags
import com.jwoglom.controlx2.presentation.screens.sections.Dashboard
import com.jwoglom.controlx2.presentation.screens.sections.Debug
import com.jwoglom.controlx2.presentation.screens.sections.Notifications
import com.jwoglom.controlx2.presentation.screens.sections.ProfileActions
import com.jwoglom.controlx2.presentation.screens.sections.QuickBolusSettingsActions
import com.jwoglom.controlx2.presentation.screens.sections.SafetyLimitsActions
import com.jwoglom.controlx2.presentation.screens.sections.Settings
import com.jwoglom.controlx2.presentation.screens.sections.TempRateWindow
import com.jwoglom.controlx2.presentation.screens.sections.SoundSettingsActions
import com.jwoglom.controlx2.presentation.screens.sections.NightscoutSettings
import com.jwoglom.controlx2.presentation.screens.sections.XdripSettings
import com.jwoglom.controlx2.presentation.screens.sections.dashboardCommands
import com.jwoglom.controlx2.presentation.screens.sections.dashboardFields
import com.jwoglom.controlx2.presentation.screens.sections.resetBolusDataStoreState
import com.jwoglom.controlx2.presentation.screens.sections.resetTempRateDataStoreState
import com.jwoglom.controlx2.presentation.navigation.SheetLaunchRequest
import com.jwoglom.controlx2.presentation.navigation.SheetLaunchTarget
import com.jwoglom.controlx2.presentation.navigation.TempRateInputPrefill
import com.jwoglom.controlx2.presentation.theme.Colors
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.enums.BasalStatus
import com.jwoglom.controlx2.shared.enums.UserMode
import com.jwoglom.controlx2.shared.util.SendType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

@Composable
fun Landing(
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    sendServiceBolusRequest: (Int, BolusParameters, BolusCalcUnits, BolusCalcDataSnapshotResponse, TimeSinceResetResponse) -> Unit,
    sendServiceBolusCancel: () -> Unit,
    sectionState: LandingSection = LandingSection.DASHBOARD,
    bottomScaffoldDisplayState: BottomSheetValue = BottomSheetValue.Collapsed,
    _bottomScaffoldState: BottomScaffoldState = BottomScaffoldState.NONE,
    historyLogViewModel: HistoryLogViewModel? = null,
    sheetLaunchRequest: SheetLaunchRequest? = null,
    onSheetLaunchHandled: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val coroutineScope = rememberCoroutineScope()

    val pumpConnected = ds.pumpConnected.observeAsState()
    val deviceName = ds.setupDeviceName.observeAsState()
    val notificationBundle = ds.notificationBundle.observeAsState()


    // Local state owns user-driven tab changes, but must reset when parent route changes.
    var selectedItem by remember(sectionState) { mutableStateOf(sectionState) }
    val bottomSheetState = rememberBottomSheetState(initialValue = bottomScaffoldDisplayState)
    val displayBottomScaffold = rememberBottomSheetScaffoldState(
        bottomSheetState = bottomSheetState
    )
    // Local state owns in-screen sheet toggles, while parent can still force a new initial sheet.
    var bottomScaffoldState by remember(_bottomScaffoldState) { mutableStateOf(_bottomScaffoldState) }
    var bolusPrefill by remember { mutableStateOf<BolusInputPrefill?>(null) }
    var tempRatePrefill by remember { mutableStateOf<TempRateInputPrefill?>(null) }

    // 子菜单不是 NavHost 目的地，系统返回键默认会直接退出 App：先收起弹窗，再回上一级，
    // 顶部导航页则回首页；只有已在首页时才交给系统退出。
    val parentSection = selectedItem.parentSection()
    BackHandler(enabled = parentSection != null || bottomScaffoldState != BottomScaffoldState.NONE ||
        selectedItem != LandingSection.DASHBOARD) {
        if (bottomScaffoldState != BottomScaffoldState.NONE) {
            coroutineScope.launch { displayBottomScaffold.bottomSheetState.collapse() }
            bottomScaffoldState = BottomScaffoldState.NONE
        } else {
            selectedItem = parentSection ?: LandingSection.DASHBOARD
        }
    }

    LaunchedEffect(bottomScaffoldState) {
        if (bottomScaffoldState != BottomScaffoldState.BOLUS_WINDOW) {
            resetBolusDataStoreState(ds)
        }
        if (bottomScaffoldState != BottomScaffoldState.TEMP_RATE_WINDOW) {
            resetTempRateDataStoreState(ds)
        }
    }

    LaunchedEffect(
        bottomScaffoldState,
        displayBottomScaffold.bottomSheetState.currentValue,
        displayBottomScaffold.bottomSheetState.targetValue
    ) {
        Timber.i(
            "Landing: sheet state update current=%s target=%s local=%s expanded=%s",
            displayBottomScaffold.bottomSheetState.currentValue,
            displayBottomScaffold.bottomSheetState.targetValue,
            bottomScaffoldState,
            displayBottomScaffold.bottomSheetState.isExpanded
        )
    }

    LaunchedEffect(sheetLaunchRequest?.requestId) {
        val request = sheetLaunchRequest ?: return@LaunchedEffect
        suspend fun expandSheetWithRetry() {
            runCatching {
                displayBottomScaffold.bottomSheetState.expand()
            }.onFailure {
                Timber.w(it, "Landing: initial bottom sheet expand failed")
            }
            if (!displayBottomScaffold.bottomSheetState.isExpanded) {
                delay(150)
                runCatching {
                    displayBottomScaffold.bottomSheetState.expand()
                }.onFailure {
                    Timber.w(it, "Landing: retry bottom sheet expand failed")
                }
            }
        }

        Timber.i("Landing: handling sheet launch request id=%s target=%s", request.requestId, request.target)
        when (val target = request.target) {
            is SheetLaunchTarget.Bolus -> {
                selectedItem = LandingSection.DASHBOARD
                bolusPrefill = target.prefill
                tempRatePrefill = null
                bottomScaffoldState = BottomScaffoldState.BOLUS_WINDOW
                expandSheetWithRetry()
            }
            is SheetLaunchTarget.TempRate -> {
                selectedItem = LandingSection.ACTIONS
                tempRatePrefill = target.prefill
                bolusPrefill = null
                bottomScaffoldState = BottomScaffoldState.TEMP_RATE_WINDOW
                expandSheetWithRetry()
            }
        }
        Timber.i(
            "Landing: sheet launch handled id=%s expanded=%s state=%s",
            request.requestId,
            displayBottomScaffold.bottomSheetState.isExpanded,
            bottomScaffoldState
        )
        onSheetLaunchHandled(request.requestId)
    }

    fun showBottomScaffold(): Boolean {
        return displayBottomScaffold.bottomSheetState.isExpanded ||
            _bottomScaffoldState != BottomScaffoldState.NONE
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (pumpConnected.value != true) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.dashboard_disconnected),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            when {
                                Prefs(context).serviceEnabled() -> stringResource(R.string.dashboard_disconnected_reconnecting)
                                else -> stringResource(R.string.dashboard_service_disabled)
                             },
                            modifier = Modifier.padding(start = 36.dp)
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.pump),
                            tint = Color.Unspecified,
                            contentDescription = stringResource(R.string.dashboard_pump_icon),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "${deviceName.value}",
                            modifier = Modifier.padding(start = 36.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        dashboardFields.forEach { it.value = null }
                        sendPumpCommands(SendType.BUST_CACHE, dashboardCommands)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.dashboard_refresh)
                        )
                    }
                },
                colors = if (isSystemInDarkTheme()) TopAppBarDefaults.topAppBarColors()
                else TopAppBarDefaults.topAppBarColors(containerColor = Colors.primary),
            )
        },
        content = { innerPadding ->
            BottomSheetScaffold(
                scaffoldState = displayBottomScaffold,
                sheetContent = {
                    LazyColumn(
                        contentPadding = PaddingValues(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.7F),
                        content = {
                            item {
                                // Fix for Android Studio preview which renders the scaffold at the
                                // top of the screen instead of half-way.
                                if (bottomScaffoldDisplayState == BottomSheetValue.Expanded) {
                                    Spacer(Modifier.size(100.dp))
                                }
                                if (bottomScaffoldState == BottomScaffoldState.BOLUS_WINDOW) {
                                    BolusWindow(
                                        sendPumpCommands = sendPumpCommands,
                                        sendServiceBolusRequest = sendServiceBolusRequest,
                                        sendServiceBolusCancel = sendServiceBolusCancel,
                                        prefill = bolusPrefill,
                                        onPrefillConsumed = { bolusPrefill = null },
                                        closeWindow = {
                                            coroutineScope.launch {
                                                displayBottomScaffold.bottomSheetState.collapse()
                                                resetBolusDataStoreState(ds)
                                                bottomScaffoldState = BottomScaffoldState.NONE
                                            }
                                        }
                                    )
                                }
                                if (bottomScaffoldState == BottomScaffoldState.TEMP_RATE_WINDOW) {
                                    TempRateWindow(
                                        sendPumpCommands = sendPumpCommands,
                                        prefill = tempRatePrefill,
                                        onPrefillConsumed = { tempRatePrefill = null },
                                        closeWindow = {
                                            coroutineScope.launch {
                                                displayBottomScaffold.bottomSheetState.collapse()
                                                bottomScaffoldState = BottomScaffoldState.NONE
                                                resetTempRateDataStoreState(ds)
                                            }
                                        }
                                    )
                                }
                            }
                            item {
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                )
                            }
                        }
                    )
                },
                sheetPeekHeight = 0.dp,
                sheetBackgroundColor = MaterialTheme.colorScheme.surface,
                sheetContentColor = MaterialTheme.colorScheme.onSurface,
                backgroundColor = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxHeight()) {
                    when (selectedItem) {
                        LandingSection.DASHBOARD -> {
                            Dashboard(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                            )
                        }

                        LandingSection.NOTIFICATIONS -> {
                            Notifications(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                            )
                        }

                        LandingSection.ACTIONS -> {
                            Actions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                                openTempRateWindow = {
                                    coroutineScope.launch {
                                        tempRatePrefill = null
                                        bottomScaffoldState = BottomScaffoldState.TEMP_RATE_WINDOW
                                        displayBottomScaffold.bottomSheetState.expand()
                                    }
                                },
                                navigateToSection = { section -> selectedItem = section }
                            )
                        }

                        LandingSection.CGM_ACTIONS -> {
                            CGMActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }

                        LandingSection.CARTRIDGE_ACTIONS -> {
                            CartridgeActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }

                        LandingSection.PROFILE_ACTIONS -> {
                            ProfileActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }
                        LandingSection.SOUND_SETTINGS_ACTIONS -> {
                            SoundSettingsActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }
                        LandingSection.QUICK_BOLUS_SETTINGS_ACTIONS -> {
                            QuickBolusSettingsActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }
                        LandingSection.CONTROLIQ_SETTINGS_ACTIONS -> {
                            ControlIQSettingsActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }
                        LandingSection.SAFETY_LIMITS_ACTIONS -> {
                            SafetyLimitsActions(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                navigateBack = {
                                    selectedItem = LandingSection.ACTIONS
                                },
                            )
                        }
                        LandingSection.DEBUG -> {
                            Debug(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                historyLogViewModel = historyLogViewModel,
                                navigateToFeatureFlags = {
                                    selectedItem = LandingSection.FEATURE_FLAGS
                                },
                                navigateBack = { selectedItem = LandingSection.SETTINGS },
                            )
                        }
                        LandingSection.FEATURE_FLAGS -> {
                            FeatureFlags(
                                innerPadding = innerPadding,
                                navController = navController,
                                navigateBack = {
                                    selectedItem = LandingSection.DEBUG
                                },
                            )
                        }
                        LandingSection.SETTINGS -> {
                            Settings(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                sendPumpCommands = sendPumpCommands,
                                navigateToDebugOptions = {
                                    selectedItem = LandingSection.DEBUG
                                },
                                navigateToNightscoutSettings = {
                                    selectedItem = LandingSection.NIGHTSCOUT_SETTINGS
                                },
                                navigateToXdripSettings = {
                                    selectedItem = LandingSection.XDRIP_SETTINGS
                                }
                            )
                        }
                        LandingSection.NIGHTSCOUT_SETTINGS -> {
                            NightscoutSettings(
                                innerPadding = innerPadding,
                                navController = navController,
                                pumpSid = ds.pumpSid.observeAsState().value ?: 0,
                                navigateBack = { selectedItem = LandingSection.SETTINGS }
                            )
                        }
                        LandingSection.XDRIP_SETTINGS -> {
                            XdripSettings(
                                innerPadding = innerPadding,
                                navController = navController,
                                sendMessage = sendMessage,
                                navigateBack = { selectedItem = LandingSection.SETTINGS }
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            when (selectedItem) {
                LandingSection.DASHBOARD ->
                        ExtendedFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (displayBottomScaffold.bottomSheetState.isCollapsed) {
                                        bolusPrefill = null
                                        bottomScaffoldState = BottomScaffoldState.BOLUS_WINDOW
                                        displayBottomScaffold.bottomSheetState.expand()

                                    } else {
                                        displayBottomScaffold.bottomSheetState.collapse()
                                        bottomScaffoldState = BottomScaffoldState.NONE
                                    }
                                }
                            },
                            icon = {
                                if (!showBottomScaffold() || bottomScaffoldState != BottomScaffoldState.BOLUS_WINDOW) {
                                    Image(
                                        if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon)
                                        else painterResource(R.drawable.bolus_icon_secondary),
                                        stringResource(R.string.dashboard_bolus_icon),
                                        Modifier.size(24.dp)
                                    )
                                } else {
                                    Image(
                                        if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_x)
                                        else painterResource(R.drawable.bolus_x_secondary),
                                        stringResource(R.string.dashboard_cancel_bolus_icon),
                                        Modifier.size(24.dp)
                                    )
                                }
                            },
                            text = {
                                Text(
                                    if (!showBottomScaffold() || bottomScaffoldState != BottomScaffoldState.BOLUS_WINDOW) stringResource(R.string.dashboard_bolus) else stringResource(R.string.dashboard_cancel),
                                    color = if (isSystemInDarkTheme()) Colors.primary
                                    else Colors.onPrimary
                                )
                            }
                        )
                else -> {}
            }
        },
        bottomBar = {
            NavigationBar {
                LandingSection.values()
                    .filter { item -> item.showInNav }
                    .forEach { item ->
                        NavigationBarItem(
                            icon = {
                                BadgedBox(
                                    badge = {
                                        notificationBundle.value?.get()?.count()?.let { count ->
                                            if (item == LandingSection.NOTIFICATIONS && count > 0) {
                                                Badge(
                                                    containerColor = Color.Red,
                                                    contentColor = Color.White
                                                ) {
                                                    Text("$count")
                                                }
                                            }
                                        }
                                    },
                                    content = {
                                        Icon(item.icon, contentDescription = stringResource(item.labelRes))
                                    }
                                )
                           },
                            label = { Text(stringResource(item.labelRes)) },
                            selected = selectedItem == item,
                            onClick = { selectedItem = item }
                        )
                    }
            }
        }
    )
}

// HACK: subpages should have the same label as an item appearing in the nav
// so that item appears as selected when it is navigated to within the app
enum class LandingSection(@StringRes val labelRes: Int, val icon: ImageVector, val showInNav: Boolean) {
    DASHBOARD(R.string.dashboard_dashboard, Icons.Filled.Info, true),

    NOTIFICATIONS(R.string.dashboard_notifications, Icons.Filled.Notifications, true),

    ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, true),
    CGM_ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, false),
    CARTRIDGE_ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, false),
    PROFILE_ACTIONS(R.string.dashboard_profiles, Icons.Filled.Create, false),
    SOUND_SETTINGS_ACTIONS(R.string.dashboard_profiles, Icons.Filled.Create, false),
    QUICK_BOLUS_SETTINGS_ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, false),
    CONTROLIQ_SETTINGS_ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, false),
    SAFETY_LIMITS_ACTIONS(R.string.dashboard_actions, Icons.Filled.Create, false),

    SETTINGS(R.string.dashboard_settings, Icons.Filled.Settings, true),
    DEBUG(R.string.dashboard_settings, Icons.Filled.Settings, false),
    NIGHTSCOUT_SETTINGS(R.string.dashboard_settings, Icons.Filled.Settings, false),
    XDRIP_SETTINGS(R.string.dashboard_settings, Icons.Filled.Settings, false),
    FEATURE_FLAGS(R.string.dashboard_settings, Icons.Filled.Settings, false),
    ;
}

/** 子菜单的上一级；顶部导航页返回 null（交给系统退出 App）。 */
fun LandingSection.parentSection(): LandingSection? = when (this) {
    LandingSection.DASHBOARD,
    LandingSection.NOTIFICATIONS,
    LandingSection.ACTIONS,
    LandingSection.SETTINGS -> null
    LandingSection.CGM_ACTIONS,
    LandingSection.CARTRIDGE_ACTIONS,
    LandingSection.PROFILE_ACTIONS,
    LandingSection.SOUND_SETTINGS_ACTIONS,
    LandingSection.QUICK_BOLUS_SETTINGS_ACTIONS,
    LandingSection.CONTROLIQ_SETTINGS_ACTIONS,
    LandingSection.SAFETY_LIMITS_ACTIONS -> LandingSection.ACTIONS
    LandingSection.DEBUG,
    LandingSection.NIGHTSCOUT_SETTINGS,
    LandingSection.XDRIP_SETTINGS -> LandingSection.SETTINGS
    LandingSection.FEATURE_FLAGS -> LandingSection.DEBUG
}

enum class BottomScaffoldState {
    NONE,
    BOLUS_WINDOW,
    TEMP_RATE_WINDOW
}

fun setUpPreviewState(ds: DataStore) {
    ds.setupDeviceName.value = "tslim X2 ***789"
    ds.setupDeviceModel.value = "X2"
    ds.pumpConnected.value = true
    ds.pumpLastConnectionTimestamp.value = Instant.now().minusSeconds(120)
    ds.cgmReading.value = 123
    ds.cgmDeltaArrow.value = "⬈"
    ds.batteryPercent.value = 50
    ds.iobUnits.value = 0.5
    ds.cartridgeRemainingUnits.value = 100
    ds.basalStatus.value = BasalStatus.ON
    ds.controlIQMode.value = UserMode.EXERCISE
    // Clear any leftover error state from an earlier preview in the same JVM run.
    // The DataStore is a module-level singleton; without this reset, an error-state
    // preview can leak its tiered card into subsequent happy-path previews.
    ds.pumpCriticalError.value = null
}

@Preview(showBackground = true)
@Composable
internal fun LandingDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Landing(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                sendServiceBolusRequest = { _, _, _, _, _ -> },
                sendServiceBolusCancel = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BolusPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Landing(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                sendServiceBolusRequest = { _, _, _, _, _ -> },
                sendServiceBolusCancel = {},
                bottomScaffoldDisplayState = BottomSheetValue.Expanded,
                _bottomScaffoldState = BottomScaffoldState.BOLUS_WINDOW
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TempRatePreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Landing(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                sendServiceBolusRequest = { _, _, _, _, _ -> },
                sendServiceBolusCancel = {},
                bottomScaffoldDisplayState = BottomSheetValue.Expanded,
                _bottomScaffoldState = BottomScaffoldState.TEMP_RATE_WINDOW,
                sectionState = LandingSection.ACTIONS,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun LandingDebugPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Landing(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                sendServiceBolusRequest = { _, _, _, _, _ -> },
                sendServiceBolusCancel = {},
                sectionState = LandingSection.DEBUG,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun LandingSettingsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            Landing(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                sendServiceBolusRequest = { _, _, _, _, _ -> },
                sendServiceBolusCancel = {},
                sectionState = LandingSection.SETTINGS,
            )
        }
    }
}
