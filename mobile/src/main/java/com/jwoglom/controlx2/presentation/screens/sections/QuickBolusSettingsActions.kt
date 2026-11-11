@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.LoadSpinner
import com.jwoglom.controlx2.presentation.screens.LandingSection
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.canControlPump
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.pumppan.controlx2.pumpModelDisplayName
import com.pumppan.controlx2.query.QueryCoordinatorHolder

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.SetQuickBolusSettingsRequest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun QuickBolusSettingsActions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()
    // X2 只能远程操作大剂量，快速大剂量设置无法写入
    val canControl = canControlPump(determinePumpModel(deviceName.value ?: ""))

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, soundSettingsActionsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = soundSettingsActionsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("QuickBolusSettingsActions loading: remaining ${nullFields.size}: ${soundSettingsActionsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("QuickBolusSettingsActions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("QuickBolusSettingsActions loading done: ${soundSettingsActionsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading QuickBolusSettingsActions with force")
        refreshing = true

        soundSettingsActionsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    // 页面离开组合时退订。导航库的 onStop 在 back stack 场景不一定触发，
    // 只靠 LifecycleStateObserver 会留下「页面关了、协调器还在轮询」的泄漏。
    DisposableEffect(Unit) {
        onDispose { QueryCoordinatorHolder.setVisible("PUMP_GLOBALS", false) }
    }

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        QueryCoordinatorHolder.setVisible("PUMP_GLOBALS", false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading QuickBolusSettingsActions from onStart lifecyclestate")
        QueryCoordinatorHolder.setVisible("PUMP_GLOBALS", true)
        fetchDataStoreFields(SendType.STANDARD)
    }

    // 周期刷新交给 QueryCoordinator（PUMP_GLOBALS 域）；页面只保留进页面首刷与下拉刷新。

    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    // Quick Bolus Settings state
    var showQuickBolusDialog by remember { mutableStateOf(false) }
    var quickBolusEnabled by remember { mutableStateOf(false) }
    var quickBolusIncrement by remember { mutableStateOf(SetQuickBolusSettingsRequest.QuickBolusIncrement.DISABLED) }

    // 泵上的当前值从 PumpGlobalsResponse 回填（本页已经通过 soundSettingsActionsCommands 拉了这条消息）。
    // 弹窗打开时不回填，避免覆盖用户正在改的值。
    val pumpGlobals = ds.pumpGlobalsResponse.observeAsState()
    LaunchedEffect(pumpGlobals.value) {
        val globals = pumpGlobals.value ?: return@LaunchedEffect
        if (showQuickBolusDialog) return@LaunchedEffect
        quickBolusEnabled = globals.isQuickBolusEnabled
        quickBolusIncrement = globals.quickBolusIncrement
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

                    HeaderLine(stringResource(R.string.sound_quick_bolus_settings))
                    Divider()
                }

                if (refreshing) {
                    item {
                        LoadSpinner(stringResource(R.string.sound_loading_quick_bolus_settings))
                    }
                }

                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.sound_quick_bolus)) },
                        supportingContent = {
                            Text(stringResource(R.string.sound_config_quick_bolus_and_increment))
                        },
                        leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showQuickBolusDialog = true
                        }
                    )
                    Divider()
                }

                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.app_back)) },
                        leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = navigateBack),
                    )
                }
            }
        )
    }

    // Quick Bolus Settings Dialog
    if (showQuickBolusDialog) {
        var expanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showQuickBolusDialog = false },
            title = { Text(stringResource(R.string.sound_quick_bolus_settings)) },
            text = {
                // AlertDialog 的 text 槽内部是 Box：多个根节点会层叠（原因文案会压住下面的开关行），
                // 统一收进单个 Column，高度随内容自适应
                Column {
                    if (!canControl) {
                        val reasonModel = determinePumpModel(deviceName.value ?: "")
                        if (reasonModel != null) {
                            Text(
                                stringResource(R.string.pumppan_model_unsupported_setting, pumpModelDisplayName(reasonModel)),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sound_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = quickBolusEnabled,
                            onCheckedChange = { quickBolusEnabled = it }
                        )
                    }
                    Text(stringResource(R.string.sound_increment), modifier = Modifier.padding(top = 8.dp))
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = quickBolusIncrement.name,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            SetQuickBolusSettingsRequest.QuickBolusIncrement.values().forEach { increment ->
                                DropdownMenuItem(
                                    text = { Text(increment.name) },
                                    onClick = {
                                        quickBolusIncrement = increment
                                        quickBolusEnabled = increment.enabled
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canControl,
                    onClick = {
                    val message = SetQuickBolusSettingsRequest(quickBolusIncrement)
                    sendPumpCommands(SendType.STANDARD, listOf(message))
                    showQuickBolusDialog = false
                    refreshScope.launch {
                        delay(500)
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.sound_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickBolusDialog = false }) {
                    Text(stringResource(R.string.actions_cancel))
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun QuickBolusSettingsActionsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            QuickBolusSettingsActions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                navigateBack = {},
            )
        }
    }
}
