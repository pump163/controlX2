@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
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
import com.jwoglom.pumpx2.pump.messages.request.control.SetMaxBasalLimitRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetMaxBolusLimitRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BasalLimitSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.GlobalMaxBolusSettingsRequest
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun SafetyLimitsActions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()
    // X2 只能远程操作大剂量，安全限制无法写入（数值仍照常显示）
    val canControl = canControlPump(determinePumpModel(deviceName.value ?: ""))

    val maxBolusSettings = ds.globalMaxBolusSettingsResponse.observeAsState()
    val basalLimitSettings = ds.basalLimitSettingsResponse.observeAsState()

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, safetyLimitsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = safetyLimitsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("SafetyLimitsActions loading: remaining ${nullFields.size}: ${safetyLimitsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("SafetyLimitsActions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("SafetyLimitsActions loading done: ${safetyLimitsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading SafetyLimitsActions with force")
        refreshing = true

        safetyLimitsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    // 页面离开组合时退订。导航库的 onStop 在 back stack 场景不一定触发，
    // 只靠 LifecycleStateObserver 会留下「页面关了、协调器还在轮询」的泄漏。
    DisposableEffect(Unit) {
        onDispose { QueryCoordinatorHolder.setVisible("SAFETY_LIMITS", false) }
    }

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        QueryCoordinatorHolder.setVisible("SAFETY_LIMITS", false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading SafetyLimitsActions from onStart lifecyclestate")
        QueryCoordinatorHolder.setVisible("SAFETY_LIMITS", true)
        fetchDataStoreFields(SendType.STANDARD)
    }

    // 周期刷新交给 QueryCoordinator（SAFETY_LIMITS 域）；页面只保留进页面首刷与下拉刷新。

    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    var showMaxBolusDialog by remember { mutableStateOf(false) }
    var showMaxBasalDialog by remember { mutableStateOf(false) }
    var maxBolusText by remember { mutableStateOf("") }
    var maxBasalText by remember { mutableStateOf("") }

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

                    HeaderLine(stringResource(R.string.safety_limits_safety_limits))
                    Divider()
                }

                if (refreshing) {
                    item {
                        LoadSpinner(stringResource(R.string.safety_limits_loading_safety_limits))
                    }
                }

                // Max Bolus Limit
                item {
                    val bolus = maxBolusSettings.value
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.safety_limits_maximum_bolus)) },
                        supportingContent = {
                            if (bolus != null) {
                                Text(
                                    context.getString(R.string.safety_limits_current_units, InsulinUnit.from1000To1(bolus.maxBolus.toLong())) +
                                    context.getString(R.string.safety_limits_default_units, InsulinUnit.from1000To1(bolus.maxBolusDefault.toLong())) +
                                    context.getString(R.string.safety_limits_range_1_25_units)
                                )
                            } else {
                                Text(context.getString(R.string.safety_limits_loading))
                            }
                        },
                        leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        modifier = Modifier.clickable {
                            maxBolusText = if (bolus != null) {
                                InsulinUnit.from1000To1(bolus.maxBolus.toLong()).toString()
                            } else ""
                            showMaxBolusDialog = true
                        }
                    )
                    Divider()
                }

                // Max Basal Rate Limit
                item {
                    val basal = basalLimitSettings.value
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.safety_limits_maximum_basal_rate)) },
                        supportingContent = {
                            if (basal != null) {
                                Text(
                                    context.getString(R.string.safety_limits_current_u_hr, InsulinUnit.from1000To1(basal.basalLimit)) +
                                    context.getString(R.string.safety_limits_default_u_hr, InsulinUnit.from1000To1(basal.basalLimitDefault)) +
                                    context.getString(R.string.safety_limits_range_1_15_u_hr)
                                )
                            } else {
                                Text(context.getString(R.string.safety_limits_loading))
                            }
                        },
                        leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        modifier = Modifier.clickable {
                            maxBasalText = if (basal != null) {
                                InsulinUnit.from1000To1(basal.basalLimit).toString()
                            } else ""
                            showMaxBasalDialog = true
                        }
                    )
                    Divider()
                }

                item {
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.safety_limits_back)) },
                        leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = navigateBack),
                    )
                }
            }
        )
    }

    // Max Bolus Dialog
    if (showMaxBolusDialog) {
        AlertDialog(
            onDismissRequest = { showMaxBolusDialog = false },
            title = { Text(stringResource(R.string.safety_limits_set_maximum_bolus)) },
            text = {
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
                    OutlinedTextField(
                        value = maxBolusText,
                        onValueChange = { maxBolusText = it },
                        label = { Text(stringResource(R.string.safety_limits_max_bolus_units_1_25)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canControl,
                    onClick = {
                    val units = maxBolusText.toDoubleOrNull()
                    if (units != null) {
                        val milliunits = (units * 1000).toInt()
                        if (milliunits in SetMaxBolusLimitRequest.MIN_BOLUS_LIMIT_MILLIUNITS..SetMaxBolusLimitRequest.MAX_BOLUS_LIMIT_MILLIUNITS) {
                            sendPumpCommands(
                                SendType.STANDARD,
                                listOf(SetMaxBolusLimitRequest(milliunits))
                            )
                            showMaxBolusDialog = false
                            refreshScope.launch {
                                delay(500)
                                refresh()
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.safety_limits_must_be_between_1_and_25_units), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.safety_limits_enter_valid_number), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.safety_limits_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaxBolusDialog = false }) {
                    Text(stringResource(R.string.safety_limits_cancel))
                }
            }
        )
    }

    // Max Basal Dialog
    if (showMaxBasalDialog) {
        AlertDialog(
            onDismissRequest = { showMaxBasalDialog = false },
            title = { Text(stringResource(R.string.safety_limits_set_maximum_basal_rate)) },
            text = {
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
                    OutlinedTextField(
                        value = maxBasalText,
                        onValueChange = { maxBasalText = it },
                        label = { Text(stringResource(R.string.safety_limits_max_basal_rate_u_hr_1_15)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canControl,
                    onClick = {
                    val units = maxBasalText.toDoubleOrNull()
                    if (units != null) {
                        val milliunits = (units * 1000).toInt()
                        if (milliunits in SetMaxBasalLimitRequest.MIN_BASAL_LIMIT_MILLIUNITS..SetMaxBasalLimitRequest.MAX_BASAL_LIMIT_MILLIUNITS) {
                            sendPumpCommands(
                                SendType.STANDARD,
                                listOf(SetMaxBasalLimitRequest(milliunits.toLong()))
                            )
                            showMaxBasalDialog = false
                            refreshScope.launch {
                                delay(500)
                                refresh()
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.safety_limits_must_be_between_1_and_15_u_hr), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.safety_limits_enter_valid_number), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.safety_limits_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaxBasalDialog = false }) {
                    Text(stringResource(R.string.safety_limits_cancel))
                }
            }
        )
    }
}

val safetyLimitsCommands = listOf(
    GlobalMaxBolusSettingsRequest(),
    BasalLimitSettingsRequest(),
)

val safetyLimitsFields = listOf(
    dataStore.globalMaxBolusSettingsResponse,
    dataStore.basalLimitSettingsResponse,
)

@Preview(showBackground = true)
@Composable
internal fun SafetyLimitsActionsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            SafetyLimitsActions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                navigateBack = {},
            )
        }
    }
}
