@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.jwoglom.controlx2.shared.capability.Capability
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.canControlPump
import com.pumppan.controlx2.ui.NavigationRow
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.pumppan.controlx2.pumpModelDisplayName

import com.pumppan.controlx2.query.QueryCoordinatorHolder
import com.pumppan.controlx2.query.QueryDomainRequests
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.builders.ControlIQInfoRequestBuilder
import com.jwoglom.pumpx2.pump.messages.models.MultiDay
import com.jwoglom.pumpx2.pump.messages.request.control.ChangeControlIQSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetSleepScheduleRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQSleepScheduleRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQSleepScheduleResponse
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun ControlIQSettingsActions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val deviceName = ds.setupDeviceName.observeAsState()
    // X2 只能远程操作大剂量，Control-IQ 设置与睡眠时段无法写入
    val canControl = canControlPump(determinePumpModel(deviceName.value ?: ""), Capability.SET_CONTROL_IQ)

    val controlIQEnabled = ds.controlIQEnabled.observeAsState()
    val controlIQWeight = ds.controlIQWeight.observeAsState()
    val controlIQWeightUnit = ds.controlIQWeightUnit.observeAsState()
    val controlIQTotalDailyInsulin = ds.controlIQTotalDailyInsulin.observeAsState()
    val sleepSchedule = ds.controlIQSleepScheduleResponse.observeAsState()

    val controlIQSummaryText = if (controlIQEnabled.value == null) {
        stringResource(R.string.ciq_loading)
    } else {
        val enabledText = stringResource(
            if (controlIQEnabled.value == true) R.string.ciq_enabled else R.string.ciq_disabled
        )
        val weightUnitText = when (controlIQWeightUnit.value?.lowercase()) {
            "lbs" -> stringResource(R.string.ciq_unit_lbs)
            "kg" -> stringResource(R.string.ciq_unit_kg)
            else -> controlIQWeightUnit.value ?: ""
        }
        val weightText = listOfNotNull(
            controlIQWeight.value?.toString(),
            weightUnitText.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "?" }
        listOf(
            stringResource(R.string.ciq_summary_status, enabledText),
            stringResource(R.string.ciq_summary_weight, weightText),
            stringResource(R.string.ciq_summary_tdi, controlIQTotalDailyInsulin.value?.toString() ?: "?"),
        ).joinToString("\n")
    }

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, controlIQSettingsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = controlIQSettingsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("ControlIQSettingsActions loading: remaining ${nullFields.size}: ${controlIQSettingsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("ControlIQSettingsActions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("ControlIQSettingsActions loading done: ${controlIQSettingsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading ControlIQSettingsActions with force")
        refreshing = true

        controlIQSettingsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    // 可见性订阅：周期由查询器按该域统一节拍，页面只保留首帧/下拉/连接补拉。
    DisposableEffect(Unit) {
        onDispose { QueryCoordinatorHolder.setVisible(QueryDomainRequests.CONTROL_IQ_SETTINGS, false) }
    }

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        QueryCoordinatorHolder.setVisible(QueryDomainRequests.CONTROL_IQ_SETTINGS, false)
        refreshScope.cancel()
    }) {
        Timber.i("reloading ControlIQSettingsActions from onStart lifecyclestate")
        QueryCoordinatorHolder.setVisible(QueryDomainRequests.CONTROL_IQ_SETTINGS, true)
        fetchDataStoreFields(SendType.STANDARD)
    }


    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    // Control-IQ settings state
    var showChangeControlIQDialog by remember { mutableStateOf(false) }
    var ciqEnabled by remember { mutableStateOf(false) }
    var weightText by remember { mutableStateOf("") }
    var tdiText by remember { mutableStateOf("") }

    // Sleep schedule edit state
    var showEditSleepScheduleDialog by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf(0) }
    var sleepEnabled by remember { mutableStateOf(false) }
    var sleepStartHour by remember { mutableStateOf("") }
    var sleepStartMin by remember { mutableStateOf("") }
    var sleepEndHour by remember { mutableStateOf("") }
    var sleepEndMin by remember { mutableStateOf("") }
    var sleepDays by remember { mutableStateOf("") }

    // Populate from current values
    LaunchedEffect(controlIQEnabled.value, controlIQWeight.value, controlIQTotalDailyInsulin.value) {
        controlIQEnabled.value?.let { ciqEnabled = it }
        controlIQWeight.value?.let { weightText = it.toString() }
        controlIQTotalDailyInsulin.value?.let { tdiText = it.toString() }
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

                    HeaderLine(stringResource(R.string.ciq_settings))
                    Divider()
                }

                if (refreshing) {
                    item {
                        LoadSpinner(stringResource(R.string.ciq_loading_ciq_settings))
                    }
                }

                // Control-IQ enabled status display
                item {
                    NavigationRow(
                        title = "Control-IQ",
                        subtitle = controlIQSummaryText,
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        readOnly = !canControl,
                        onClick = { showChangeControlIQDialog = true },
                    )
                    Divider()
                }

                // Sleep Schedule section header
                item {
                    HeaderLine(stringResource(R.string.ciq_sleep_schedule))
                    Divider()
                }

                // Sleep schedule slots
                val schedule = sleepSchedule.value
                if (schedule != null) {
                    val schedules = listOf(
                        schedule.schedule0,
                        schedule.schedule1,
                        schedule.schedule2,
                        schedule.schedule3
                    )
                    schedules.forEachIndexed { index, slot ->
                        item {
                            val enabled = slot.enabled != 0
                            NavigationRow(
                                title = context.getString(R.string.ciq_schedule, index + 1),
                                subtitle = if (enabled) {
                                    val days = slot.activeDays().map { dayLabel(it) }.joinToString(", ")
                                    stringResource(R.string.ciq_enabled) + "\n" +
                                        "${String.format("%02d:%02d", slot.startTime().hour(), slot.startTime().min())} - " +
                                        "${String.format("%02d:%02d", slot.endTime().hour(), slot.endTime().min())}\n" +
                                        stringResource(R.string.ciq_days_days, days)
                                } else {
                                    context.getString(R.string.ciq_disabled)
                                },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                readOnly = !canControl,
                                onClick = {
                                    editingSlot = index
                                    sleepEnabled = enabled
                                    if (enabled) {
                                        sleepStartHour = slot.startTime().hour().toString()
                                        sleepStartMin = slot.startTime().min().toString()
                                        sleepEndHour = slot.endTime().hour().toString()
                                        sleepEndMin = slot.endTime().min().toString()
                                        sleepDays = slot.activeDays().sumOf { it.id() }.toString()
                                    } else {
                                        sleepStartHour = "22"
                                        sleepStartMin = "0"
                                        sleepEndHour = "6"
                                        sleepEndMin = "0"
                                        sleepDays = "127"
                                    }
                                    showEditSleepScheduleDialog = true
                                },
                            )
                            Divider()
                        }
                    }
                } else if (!refreshing) {
                    item {
                        Line(stringResource(R.string.ciq_sleep_sched_data_not_available))
                    }
                }

                item {
                    NavigationRow(
                        title = context.getString(R.string.ciq_back),
                        leadingIcon = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                        onClick = navigateBack,
                    )
                }
            }
        )
    }

    // Change Control-IQ Settings Dialog
    if (showChangeControlIQDialog) {
        AlertDialog(
            onDismissRequest = { showChangeControlIQDialog = false },
            title = { Text(stringResource(R.string.ciq_change_ciq_settings)) },
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
                        Text(stringResource(R.string.ciq_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = ciqEnabled,
                            onCheckedChange = { ciqEnabled = it }
                        )
                    }
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it },
                        label = { Text(stringResource(R.string.ciq_weight_lbs)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tdiText,
                        onValueChange = { tdiText = it },
                        label = { Text(stringResource(R.string.ciq_total_daily_insulin_units_2)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canControl,
                    onClick = {
                    val weight = weightText.toIntOrNull()
                    val tdi = tdiText.toIntOrNull()
                    if (weight != null && tdi != null) {
                        sendPumpCommands(
                            SendType.STANDARD,
                            listOf(ChangeControlIQSettingsRequest(ciqEnabled, weight, tdi))
                        )
                        showChangeControlIQDialog = false
                        refreshScope.launch {
                            delay(500)
                            refresh()
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.ciq_enter_valid_numbers), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.ciq_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeControlIQDialog = false }) {
                    Text(stringResource(R.string.ciq_cancel))
                }
            }
        )
    }

    // Edit Sleep Schedule Dialog
    if (showEditSleepScheduleDialog) {
        AlertDialog(
            onDismissRequest = { showEditSleepScheduleDialog = false },
            title = { Text(stringResource(R.string.ciq_sleep_schedule_next, editingSlot + 1)) },
            text = {
                // 内容可滚动：长文案（原因说明/原始值）与星期选择器在窄屏上会超出弹窗，
                // AlertDialog 的 text 槽本身不滚动，不处理会把标题与按钮挤出可视区。
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                        Text(stringResource(R.string.ciq_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = sleepEnabled,
                            onCheckedChange = { sleepEnabled = it }
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = sleepStartHour,
                            onValueChange = { sleepStartHour = it },
                            label = { Text(stringResource(R.string.ciq_start_hour)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = sleepStartMin,
                            onValueChange = { sleepStartMin = it },
                            label = { Text(stringResource(R.string.ciq_start_min)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = sleepEndHour,
                            onValueChange = { sleepEndHour = it },
                            label = { Text(stringResource(R.string.ciq_end_hour)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = sleepEndMin,
                            onValueChange = { sleepEndMin = it },
                            label = { Text(stringResource(R.string.ciq_end_min)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SleepDaysSelector(
                        maskText = sleepDays,
                        onMaskChange = { sleepDays = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canControl,
                    onClick = {
                    val startH = sleepStartHour.toIntOrNull() ?: 0
                    val startM = sleepStartMin.toIntOrNull() ?: 0
                    val endH = sleepEndHour.toIntOrNull() ?: 0
                    val endM = sleepEndMin.toIntOrNull() ?: 0
                    // 星期掩码语义（Mon=1…Sun=64，全选 127）来自泵端逆向推断，待真机验证
                    val daysBitmask = sleepDays.toIntOrNull()?.coerceIn(0, 127) ?: 0

                    val startTime = startH * 60 + startM
                    val endTime = endH * 60 + endM

                    val sleepScheduleObj = ControlIQSleepScheduleResponse.SleepSchedule(
                        if (sleepEnabled) 1 else 0,
                        daysBitmask,
                        startTime,
                        endTime
                    )
                    sendPumpCommands(
                        SendType.STANDARD,
                        listOf(SetSleepScheduleRequest(editingSlot, sleepScheduleObj, 0))
                    )
                    showEditSleepScheduleDialog = false
                    refreshScope.launch {
                        delay(500)
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.ciq_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditSleepScheduleDialog = false }) {
                    Text(stringResource(R.string.ciq_cancel))
                }
            }
        )
    }
}

/**
 * 星期选择器：显示顺序固定 Sun→Sat，但写回的位序严格按 MultiDay（Mon=1 … Sun=64）。
 * 注意不要使用 MultiDay.fromBitmask：它用 `bitmask % day.id() == 0` 判位，非 2 的幂组合会误判。
 * 语义为逆向推断，0 值与保留位的真实含义待真机验证。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepDaysSelector(
    maskText: String,
    onMaskChange: (String) -> Unit,
) {
    val parsedMask = maskText.toIntOrNull()
    val safeMask = parsedMask?.coerceIn(0, 127) ?: 0
    val outOfRange = parsedMask != null && (parsedMask < 0 || parsedMask > 127)
    val displayDays = listOf(
        MultiDay.SUNDAY, MultiDay.MONDAY, MultiDay.TUESDAY, MultiDay.WEDNESDAY,
        MultiDay.THURSDAY, MultiDay.FRIDAY, MultiDay.SATURDAY,
    )

    Column {
        Text(stringResource(R.string.ciq_active_days_bitmask))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            displayDays.forEach { day ->
                val selected = safeMask and day.id() != 0
                FilterChip(
                    selected = selected,
                    onClick = {
                        val newMask = if (selected) {
                            safeMask and day.id().inv()
                        } else {
                            safeMask or day.id()
                        }
                        onMaskChange(newMask.toString())
                    },
                    label = { Text(dayLabel(day)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onMaskChange("127") },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.ciq_days_all))
            }
            TextButton(
                onClick = { onMaskChange("0") },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.ciq_days_clear))
            }
        }
        Text(
            "${stringResource(R.string.ciq_days_raw)}: ${parsedMask ?: safeMask}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (outOfRange) {
            Text(
                stringResource(R.string.ciq_days_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun dayLabel(day: MultiDay): String {
    return when (day) {
        MultiDay.SUNDAY -> stringResource(R.string.ciq_day_sun)
        MultiDay.MONDAY -> stringResource(R.string.ciq_day_mon)
        MultiDay.TUESDAY -> stringResource(R.string.ciq_day_tue)
        MultiDay.WEDNESDAY -> stringResource(R.string.ciq_day_wed)
        MultiDay.THURSDAY -> stringResource(R.string.ciq_day_thu)
        MultiDay.FRIDAY -> stringResource(R.string.ciq_day_fri)
        MultiDay.SATURDAY -> stringResource(R.string.ciq_day_sat)
    }
}

val controlIQSettingsCommands = listOf(
    ControlIQInfoRequestBuilder.create(apiVersion()),
    ControlIQSleepScheduleRequest(),
)

/**
 * 周期域：与页面同一份清单，但每次调用都重新求值 `apiVersion()`，
 * 避免类加载时泵版本还没读到、把请求冻结在错误 API 上。
 */
val controlIQSettingsCycleCommands: List<Message>
    get() = listOf(
        ControlIQInfoRequestBuilder.create(apiVersion()),
        ControlIQSleepScheduleRequest(),
    )

val controlIQSettingsFields = listOf(
    dataStore.controlIQEnabled,
    dataStore.controlIQSleepScheduleResponse,
)

@Preview(showBackground = true)
@Composable
internal fun ControlIQSettingsActionsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            val ds = LocalDataStore.current
            setUpPreviewState(ds)
            ds.controlIQEnabled.value = true
            ds.controlIQWeight.value = 165
            ds.controlIQWeightUnit.value = "lbs"
            ds.controlIQTotalDailyInsulin.value = 45
            ds.controlIQSleepScheduleResponse.value = ControlIQSleepScheduleResponse(
                ControlIQSleepScheduleResponse.SleepSchedule(1, 127, 22 * 60, 6 * 60),
                ControlIQSleepScheduleResponse.SleepSchedule(0, 0, 0, 0),
                ControlIQSleepScheduleResponse.SleepSchedule(0, 0, 0, 0),
                ControlIQSleepScheduleResponse.SleepSchedule(0, 0, 0, 0),
            )
            ControlIQSettingsActions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                navigateBack = {},
            )
        }
    }
}
