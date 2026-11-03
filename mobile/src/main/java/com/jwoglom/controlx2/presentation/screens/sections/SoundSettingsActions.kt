@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.Line
import com.jwoglom.controlx2.presentation.components.LoadSpinner
import com.jwoglom.controlx2.presentation.screens.LandingSection
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.request.control.SetPumpAlertSnoozeRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetPumpSoundsRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpGlobalsRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.PumpGlobalsResponse
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun SoundSettingsActions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current

    val pumpGlobals = ds.pumpGlobalsResponse.observeAsState()
    val deviceName = ds.setupDeviceName.observeAsState()

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

            Timber.i("SoundSettingsActions loading: remaining ${nullFields.size}: ${soundSettingsActionsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("SoundSettingsActions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("SoundSettingsActions loading done: ${soundSettingsActionsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading SoundSettingsActions with force")
        refreshing = true

        soundSettingsActionsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        refreshScope.cancel()
    }) {
        Timber.i("reloading SoundSettingsActions from onStart lifecyclestate")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(intervalOf(60)) {
        Timber.i("reloading SoundSettingsActions from interval")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    // Alert Snooze state
    var showAlertSnoozeDialog by remember { mutableStateOf(false) }
    var alertSnoozeEnabled by remember { mutableStateOf(false) }
    var alertSnoozeDuration by remember { mutableStateOf(10) }

    var quickBolusText by remember { mutableStateOf("") }
    var generalText by remember { mutableStateOf("") }
    var reminderText by remember { mutableStateOf("") }
    var alertText by remember { mutableStateOf("") }
    var alarmText by remember { mutableStateOf("") }
    var cgmBText by remember { mutableStateOf("") }
    // CGM 警报 B 的当前值不在 PumpGlobals 中上报，故以「上次发送值」为基线判断是否改动。
    // 位定义与「改 CGM 需置位 64」均为逆向推断，待真机验证。
    var cgmBBaseline by remember { mutableStateOf("0") }

    // 「本次修改范围」由界面状态推导后只读展示，不提供手工输入，避免双写源。
    // 位定义（逆向推断，待真机验证）：2=快捷大剂量 4=常规 8=提醒 16=警告 32=警报 64=CGM 警报；
    // 0 表示没有任何改动；1 与 >=128 为保留/未定义位。
    val changeScopes = run {
        val scopes = mutableListOf<SetPumpSoundsRequest.ChangeBitmask>()
        pumpGlobals.value?.let { globals ->
            if (quickBolusText != globals.quickBolusAnnun.id().toString()) {
                scopes.add(SetPumpSoundsRequest.ChangeBitmask.QUICK_BOLUS)
            }
            if (generalText != globals.fillTubingAnnun.id().toString()) {
                scopes.add(SetPumpSoundsRequest.ChangeBitmask.GENERAL)
            }
            if (reminderText != globals.reminderAnnun.id().toString()) {
                scopes.add(SetPumpSoundsRequest.ChangeBitmask.REMINDER)
            }
            if (alertText != globals.alertAnnun.id().toString()) {
                scopes.add(SetPumpSoundsRequest.ChangeBitmask.ALERT)
            }
            if (alarmText != globals.alarmAnnun.id().toString()) {
                scopes.add(SetPumpSoundsRequest.ChangeBitmask.ALARM)
            }
        }
        if (cgmBText != cgmBBaseline) {
            scopes.add(SetPumpSoundsRequest.ChangeBitmask.CGM_ALERT)
        }
        scopes
    }
    val changeBitmask = SetPumpSoundsRequest.ChangeBitmask.toBitmask(*changeScopes.toTypedArray())

    // 发送前校验：提示音取值为 0..3，CGM 警报 B 仅允许 0（振动）/2（蜂鸣）/3（低血糖重复）
    val soundValuesValid = listOf(quickBolusText, generalText, reminderText, alertText, alarmText)
        .all { it.toIntOrNull()?.let { value -> value in 0..3 } == true } &&
        cgmBText.toIntOrNull()?.let { it == 0 || it == 2 || it == 3 } == true

    LaunchedEffect(pumpGlobals.value) {
        pumpGlobals.value?.let { globals ->
            quickBolusText = globals.quickBolusAnnun.id().toString()
            generalText = globals.fillTubingAnnun.id().toString()
            reminderText = globals.reminderAnnun.id().toString()
            alertText = globals.alertAnnun.id().toString()
            alarmText = globals.alarmAnnun.id().toString()
            if (cgmBText.isBlank()) {
                cgmBText = "0"
            }
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

                    HeaderLine(stringResource(R.string.sound_settings))
                    Divider()
                }

                if (refreshing) {
                    item {
                        LoadSpinner(stringResource(R.string.sound_loading_sound_settings) )
                    }
                }
                
                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_quick_bolus_annunciation),
                        value = quickBolusText,
                        onSelect = { quickBolusText = it }
                    )
                }

                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_general_annunciation),
                        value = generalText,
                        onSelect = { generalText = it }
                    )
                }

                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_remind_annunciation),
                        value = reminderText,
                        onSelect = { reminderText = it }
                    )
                }

                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_alert_annunciation),
                        value = alertText,
                        onSelect = { alertText = it }
                    )
                }

                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_alarm_annunciation),
                        value = alarmText,
                        onSelect = { alarmText = it }
                    )
                }

                item {
                    // CGM 警报 A 在泵接口中恒为 0，不提供编辑
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.sound_cgm_alert_annunciation)) },
                        supportingContent = { Text(stringResource(R.string.sound_cgm_a_fixed_value)) },
                        trailingContent = { Text("0") }
                    )
                }

                item {
                    SoundLevelField(
                        label = stringResource(R.string.sound_cgm_alert_annunciation_b),
                        value = cgmBText,
                        options = listOf(0, 2, 3),
                        optionLabel = { cgmAnnunciationOptionLabel(it) },
                        onSelect = { cgmBText = it }
                    )
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.sound_change_scope))
                        if (changeScopes.isEmpty()) {
                            Text(
                                stringResource(R.string.sound_scope_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            changeScopes.chunked(3).forEach { rowScopes ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    rowScopes.forEach { scope ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = { Text(scopeLabel(scope)) }
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "${stringResource(R.string.sound_raw_mask)}: $changeBitmask",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                item {
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.sound_apply_sound_settings)) },
                        supportingContent = { Text(context.getString(R.string.sound_send_set_pump_sounds_request_withse)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.sound_apply_sound_settings)
                            )
                        },
                        modifier = Modifier.clickable {
                            if (!soundValuesValid) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.sound_invalid_value),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@clickable
                            }
                            val message = SetPumpSoundsRequest(
                                quickBolusText.toInt(),
                                generalText.toInt(),
                                reminderText.toInt(),
                                alertText.toInt(),
                                alarmText.toInt(),
                                0, // CGM 警报 A 固定为 0（泵接口约定，逆向推断）
                                cgmBText.toInt(),
                                changeBitmask,
                            )
                            sendPumpCommands(SendType.STANDARD, listOf(message))
                            cgmBBaseline = cgmBText
                            Toast.makeText(
                                context,
                                context.getString(R.string.sound_apply_sent),
                                Toast.LENGTH_SHORT
                            ).show()

                            refreshScope.launch {
                                delay(500)
                                refresh()
                            }

                        }
                    )
                }

                // Alert Snooze section (Mobi only)
                val model = determinePumpModel(deviceName.value ?: "")
                if (model == KnownDeviceModel.MOBI) {
                    item {
                        Line("\n")
                        HeaderLine(stringResource(R.string.sound_alert_snooze))
                        Divider()
                    }

                    item {
                        ListItem(
                            headlineContent = { Text(context.getString(R.string.sound_button_snooze)) },
                            supportingContent = {
                                Text(context.getString(R.string.sound_config_triple_press_snooze))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = context.getString(R.string.sound_button_snooze)
                                )
                            },
                            modifier = Modifier.clickable {
                                showAlertSnoozeDialog = true
                            }
                        )
                        Divider()
                    }
                }

                item {
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.sound_back)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = context.getString(R.string.sound_back)
                            )
                        },
                        modifier = Modifier.clickable(onClick = navigateBack),
                    )
                }
            }
        )
    }

    // Alert Snooze Dialog
    if (showAlertSnoozeDialog) {
        AlertDialog(
            onDismissRequest = { showAlertSnoozeDialog = false },
            title = { Text(stringResource(R.string.sound_alert_snooze_settings)) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sound_snooze_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = alertSnoozeEnabled,
                            onCheckedChange = { alertSnoozeEnabled = it }
                        )
                    }
                    if (alertSnoozeEnabled) {
                        Text(stringResource(R.string.sound_duration), modifier = Modifier.padding(top = 8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            listOf(10, 20).forEach { mins ->
                                TextButton(
                                    onClick = { alertSnoozeDuration = mins },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.sound_min, mins),
                                        color = if (alertSnoozeDuration == mins) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val duration = if (alertSnoozeEnabled) alertSnoozeDuration else 0
                    val message = SetPumpAlertSnoozeRequest(alertSnoozeEnabled, duration)
                    sendPumpCommands(SendType.STANDARD, listOf(message))
                    showAlertSnoozeDialog = false
                    refreshScope.launch {
                        delay(500)
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.sound_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlertSnoozeDialog = false }) {
                    Text(stringResource(R.string.sound_cancel))
                }
            }
        )
    }
}

@Composable
private fun SoundLevelField(
    label: String,
    value: String,
    options: List<Int> = listOf(0, 1, 2, 3),
    optionLabel: @Composable (Int?) -> String = { annunciationOptionLabel(it) },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = optionLabel(value.toIntOrNull()),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = {
                                onSelect(option.toString())
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun annunciationOptionLabel(value: Int?): String {
    return when (value) {
        0 -> stringResource(R.string.sound_option_high)
        1 -> stringResource(R.string.sound_option_medium)
        2 -> stringResource(R.string.sound_option_low)
        3 -> stringResource(R.string.sound_option_vibrate)
        else -> stringResource(R.string.sound_option_unknown)
    }
}

@Composable
private fun cgmAnnunciationOptionLabel(value: Int?): String {
    return when (value) {
        0 -> stringResource(R.string.sound_option_vibrate)
        2 -> stringResource(R.string.sound_cgm_option_beep)
        3 -> stringResource(R.string.sound_cgm_option_hypo_repeat)
        else -> stringResource(R.string.sound_option_unknown)
    }
}

@Composable
private fun scopeLabel(scope: SetPumpSoundsRequest.ChangeBitmask): String {
    return when (scope) {
        SetPumpSoundsRequest.ChangeBitmask.QUICK_BOLUS -> stringResource(R.string.sound_scope_quick_bolus)
        SetPumpSoundsRequest.ChangeBitmask.GENERAL -> stringResource(R.string.sound_scope_general)
        SetPumpSoundsRequest.ChangeBitmask.REMINDER -> stringResource(R.string.sound_scope_reminder)
        SetPumpSoundsRequest.ChangeBitmask.ALERT -> stringResource(R.string.sound_scope_alert)
        SetPumpSoundsRequest.ChangeBitmask.ALARM -> stringResource(R.string.sound_scope_alarm)
        SetPumpSoundsRequest.ChangeBitmask.CGM_ALERT -> stringResource(R.string.sound_scope_cgm)
    }
}



val soundSettingsActionsCommands = listOf(
    PumpGlobalsRequest(),
)

val soundSettingsActionsFields = listOf(
    dataStore.pumpGlobalsResponse,
)

@Preview(showBackground = true)
@Composable
internal fun SoundSettingsActionsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            SoundSettingsActions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                navigateBack = {},
            )
        }
    }
}
