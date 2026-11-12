@file:OptIn(ExperimentalMaterial3Api::class)

package com.pumppan.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.shared.util.SendType
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.scheduled.PlanIssue
import com.pumppan.controlx2.scheduled.ScheduledBolusChain
import com.pumppan.controlx2.scheduled.ScheduledBolusConfig
import com.pumppan.controlx2.scheduled.ScheduledBolusRuntime
import com.pumppan.controlx2.scheduled.ScheduledBolusSender
import com.pumppan.controlx2.scheduled.ScheduledPreset
import com.pumppan.controlx2.scheduled.ScheduledPresetsStore
import com.pumppan.controlx2.scheduled.ScheduledPlan
import com.pumppan.controlx2.scheduled.validate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.jwoglom.pumpx2.pump.messages.Message

/**
 * 「定时」标签页（用户 2026-11-12 拍板的最终形态）。
 *
 * 三段式：提醒文案 → 6 个预约槽（模板） → 执行区。
 * 流程：填表 → [下一步] 校验并列出每笔的 时隔/时刻/剂量 → [执行] 发首笔 + 起链。
 * 执行中逐笔置灰；下一笔剂量只在倒数期可改，进入 T−2min 提醒态即锁死。
 */
@Composable
fun ScheduledBolusTab(
    limits: BolusLimits,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val context = LocalContext.current
    val dataStore = LocalDataStore.current
    val config = remember { ScheduledBolusConfig.load(context) }
    val scope = rememberCoroutineScope()
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }

    // ---- 表单状态（三行，第一行只有剂量） ----
    val doses = remember { mutableStateListOf("", "", "") }
    val intervals = remember { mutableStateListOf("", "") }
    val times = remember { mutableStateListOf("", "") }
    var absoluteMode by rememberSaveable { mutableStateOf(false) }

    var confirming by remember { mutableStateOf(false) }
    var confirmPlan by remember { mutableStateOf<ScheduledPlan?>(null) }
    var issues by remember { mutableStateOf<List<PlanIssue>>(emptyList()) }
    var preflight by remember { mutableStateOf<ScheduledBolusSender.Preflight?>(null) }
    var askExtendedUnknown by remember { mutableStateOf(false) }
    var executing by remember { mutableStateOf(false) }
    var fatalText by remember { mutableStateOf<String?>(null) }

    // ---- 预约槽 ----
    val presets = remember { mutableStateListOf<ScheduledPreset>() }
    LaunchedEffect(Unit) {
        presets.clear()
        presets += ScheduledPresetsStore.load(context, config.maxPresetCount)
    }
    var sheetSlot by remember { mutableStateOf<Int?>(null) }
    var pendingApply by remember { mutableStateOf<Int?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }

    /** 套用槽位：只灌「时隔 + 剂量」，绝对时刻永不进模板。 */
    fun applyPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        doses[0] = ""
        doses[1] = uPlain(preset.doseMilliunits)
        doses[2] = ""
        intervals[0] = (preset.intervalAfterPreviousMs / 60_000L).toString()
        intervals[1] = ""
        toastText = context.getString(
            R.string.scheduled_preset_applied,
            index + 1,
            preset.intervalAfterPreviousMs / 60_000L,
            uText(preset.doseMilliunits),
        )
    }

    /** 用当前表单（优先第 2 行，其次第 3 行）生成一个模板。 */
    fun currentPresetFromForm(): ScheduledPreset? {
        val index = if (parseDose(doses[1]) != null) 0 else if (parseDose(doses[2]) != null) 1 else -1
        if (index < 0) return null
        val dose = parseDose(doses[index + 1]) ?: return null
        val minutes = intervals[index].trim().toLongOrNull() ?: return null
        return ScheduledPreset(intervalAfterPreviousMs = minutes * 60_000L, doseMilliunits = dose)
    }
    LaunchedEffect(toastText) {
        toastText?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastText = null
        }
    }

    // ---- 运行态轮询（10s 一拍太粗，界面 1s 刷新即可） ----
    var snapshot by remember { mutableStateOf(ScheduledBolusRuntime.currentSnapshot()) }
    val running = snapshot != null && !snapshot!!.isTerminal
    LaunchedEffect(running) {
        while (true) {
            ScheduledBolusRuntime.tick(context)
            snapshot = ScheduledBolusRuntime.currentSnapshot()
            delay(1_000L)
        }
    }

    fun buildPlan(now: Long): ScheduledPlan? {
        val firstDose = parseDose(doses[0]) ?: return null
        val links = mutableListOf<ScheduledBolusChain.Link>()
        for (i in 0 until 2) {
            val dose = parseDose(doses[i + 1])
            if (dose == null) {
                if (doses[i + 1].isNotBlank()) return null
                if (i == 0 && doses[2].isNotBlank()) return null // 中间空、第三行有值 → 不合法
                continue
            }
            if (absoluteMode) {
                val at = parseClock(times[i], now) ?: return null
                links += ScheduledBolusChain.Link(
                    doseMilliunits = dose,
                    delayAfterPreviousMs = 0L,
                    absoluteAtMillis = at,
                )
            } else {
                val minutes = intervals[i].trim().toLongOrNull() ?: return null
                links += ScheduledBolusChain.Link(
                    doseMilliunits = dose,
                    delayAfterPreviousMs = minutes * 60_000L,
                )
            }
        }
        return ScheduledPlan(firstAtMillis = now, firstDoseMilliunits = firstDose, links = links)
    }

    fun goNext() {
        val now = System.currentTimeMillis()
        val plan = buildPlan(now)
        if (plan == null) {
            fatalText = context.getString(R.string.scheduled_ui_incomplete)
            return
        }
        val found = plan.validate(config, limits, now)
        if (found.isNotEmpty()) {
            issues = found
            return
        }
        issues = emptyList()
        confirmPlan = plan
        confirming = true
    }

    fun startExecution(extendedUnknownAccepted: Boolean) {
        val plan = confirmPlan ?: return
        val now = System.currentTimeMillis()
        val refreshed = plan.copy(firstAtMillis = now)
        val found = refreshed.validate(config, limits, now)
        if (found.isNotEmpty()) {
            issues = found
            confirming = false
            return
        }
        executing = true
        scope.launch {
            val pf = ScheduledBolusSender.preflight(context, dataStore, sendPumpCommands)
            preflight = pf
            if (!pf.linkFresh) {
                fatalText = context.getString(R.string.scheduled_preflight_link)
                executing = false
                return@launch
            }
            if (pf.foreignBolus) {
                fatalText = context.getString(R.string.scheduled_preflight_foreign)
                executing = false
                return@launch
            }
            if (pf.extendedActive) {
                fatalText = context.getString(R.string.scheduled_preflight_extended)
                executing = false
                return@launch
            }
            if (pf.extendedUnknown && !extendedUnknownAccepted) {
                askExtendedUnknown = true
                executing = false
                return@launch
            }

            val armIssues = ScheduledBolusRuntime.arm(context, refreshed, now)
            if (armIssues.isNotEmpty()) {
                issues = armIssues
                confirming = false
                executing = false
                return@launch
            }

            when (val result = ScheduledBolusSender.sendBolus(
                context, dataStore, sendPumpCommands, sendMessage, refreshed.firstDoseMilliunits,
            )) {
                is ScheduledBolusSender.SendResult.Failed ->
                    fatalText = context.getString(result.reasonRes)

                is ScheduledBolusSender.SendResult.Sent -> {
                    ScheduledBolusRuntime.registerPermission(context, result.bolusId)
                    confirming = false
                    executing = false
                    snapshot = ScheduledBolusRuntime.currentSnapshot()
                    toastText = context.getString(R.string.scheduled_exec_started)
                }
            }
            if (fatalText != null) {
                ScheduledBolusRuntime.cancel(context, ScheduledBolusChain.AbortReason.SEND_FAILED)
                executing = false
            }
        }
    }

    /** 发当前这一笔（第 2/3 笔）：先确认状态已到 SENDING。 */
    suspend fun sendNext() {
        val snap = ScheduledBolusRuntime.currentSnapshot() ?: return
        if (snap.state != ScheduledBolusChain.State.SENDING) return
        when (val result = ScheduledBolusSender.sendBolus(
            context, dataStore, sendPumpCommands, sendMessage, snap.currentDoseMilliunits,
        )) {
            is ScheduledBolusSender.SendResult.Failed -> {
                fatalText = context.getString(result.reasonRes)
                ScheduledBolusRuntime.abort(context, ScheduledBolusChain.AbortReason.SEND_FAILED)
            }
            is ScheduledBolusSender.SendResult.Sent ->
                ScheduledBolusRuntime.registerPermission(context, result.bolusId)
        }
    }

    /** 「不确认即输注」：到点直接走两次内部确认并发送，不做界面等待。 */
    val autoSend = remember { PumppanPrefs.scheduledAutoSend(context) }
    LaunchedEffect(snapshot?.state, autoSend) {
        if (autoSend && snapshot?.state == ScheduledBolusChain.State.ALERTING) {
            ScheduledBolusRuntime.confirm(context)
            ScheduledBolusRuntime.secondConfirm(context)
            sendNext()
            snapshot = ScheduledBolusRuntime.currentSnapshot()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.scheduled_tab_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ---- 预约槽（短按套用，长按/管理进弹层） ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEachIndexed { index, preset ->
                    SlotChip(
                        label = "${index + 1} · +${preset.intervalAfterPreviousMs / 60_000L}m · ${uText(preset.doseMilliunits)}U",
                        onClick = {
                            if (intervals[0].isNotBlank() || intervals[1].isNotBlank() || doses.any { it.isNotBlank() }) {
                                fatalText = context.getString(R.string.scheduled_preset_overwrite_confirm)
                                pendingApply = index
                            } else {
                                applyPreset(index)
                            }
                        },
                        onLongClick = { sheetSlot = index },
                    )
                }
                if (presets.size < config.maxPresetCount) {
                    SlotChip(
                        label = stringResource(R.string.scheduled_preset_add),
                        onClick = { sheetSlot = -1 },
                        onLongClick = null,
                    )
                }
            }
        }

        // ---- 模式互锁 ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !absoluteMode,
                    onClick = { absoluteMode = false },
                    label = { Text(stringResource(R.string.scheduled_mode_relative)) },
                )
                FilterChip(
                    selected = absoluteMode,
                    onClick = { absoluteMode = true },
                    label = { Text(stringResource(R.string.scheduled_mode_absolute)) },
                )
            }
        }

        if (!running && !confirming) {
            item { PointRow(index = 0, dose = doses[0], onDose = { doses[0] = it }, timeLabel = null) }
            item {
                PointRow(
                    index = 1,
                    dose = doses[1],
                    onDose = { doses[1] = it },
                    interval = if (absoluteMode) null else intervals[0],
                    onInterval = { intervals[0] = it },
                    time = if (absoluteMode) times[0] else null,
                    onTime = { times[0] = it },
                )
            }
            item {
                PointRow(
                    index = 2,
                    dose = doses[2],
                    onDose = { doses[2] = it },
                    interval = if (absoluteMode) null else intervals[1],
                    onInterval = { intervals[1] = it },
                    time = if (absoluteMode) times[1] else null,
                    onTime = { times[1] = it },
                )
            }
            if (issues.isNotEmpty()) {
                item { IssueList(issues, limits) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { goNext() }) { Text(stringResource(R.string.scheduled_next)) }
                }
            }
        }

        // ---- 确认页 ----
        if (confirming && confirmPlan != null) {
            item {
                val plan = confirmPlan!!
                Text(stringResource(R.string.scheduled_confirm_title), style = MaterialTheme.typography.titleSmall)
                plan.timeline().forEachIndexed { index, point ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = stringResource(
                                R.string.scheduled_point_index,
                                index + 1,
                            ),
                            modifier = Modifier.weight(0.15f),
                        )
                        Text(
                            text = if (point.linkIndex < 0) "—" else intervalText(point.intervalFromPreviousMs),
                            modifier = Modifier.weight(0.3f),
                        )
                        Text(
                            text = Instant.ofEpochMilli(point.atMillis).atZone(ZoneId.systemDefault())
                                .format(clock),
                            modifier = Modifier.weight(0.3f),
                        )
                        Text(uText(point.doseMilliunits) + " U", modifier = Modifier.weight(0.25f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirming = false }) {
                        Text(stringResource(R.string.scheduled_back))
                    }
                    Button(onClick = { startExecution(false) }, enabled = !executing) {
                        Text(stringResource(R.string.scheduled_run))
                    }
                }
            }
        }

        // ---- 执行态 ----
        if (snapshot != null) {
            item {
                Divider()
                Text(
                    text = stringResource(
                        R.string.scheduled_run_status,
                        stateText(snapshot!!.state),
                        snapshot!!.runs.count { it.terminalStatusId != null } + 1,
                        snapshot!!.runs.size + 1,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                snapshot!!.runs.forEachIndexed { index, run ->
                    val done = run.terminalStatusId != null
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${index + 2}.", modifier = Modifier.weight(0.1f),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "${uText(run.link.doseMilliunits)} U",
                            modifier = Modifier.weight(0.4f),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (done) stringResource(R.string.scheduled_row_done)
                            else stringResource(R.string.scheduled_row_pending),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (!snapshot!!.isTerminal) {
                    Text(stringResource(R.string.scheduled_cancel_hint), fontSize = 12.sp)
                    // 到点后：两次确认 → 发当前一笔。自动模式下由下面的 LaunchedEffect 直接走到发送。
                    when (snapshot!!.state) {
                        ScheduledBolusChain.State.ALERTING -> Button(
                            onClick = { ScheduledBolusRuntime.confirm(context) },
                        ) { Text(stringResource(R.string.scheduled_confirm_once)) }
                        ScheduledBolusChain.State.WARNING -> Button(
                            onClick = {
                                ScheduledBolusRuntime.secondConfirm(context)
                                scope.launch { sendNext() }
                            },
                        ) { Text(stringResource(R.string.scheduled_confirm_twice)) }
                        else -> Unit
                    }
                    OutlinedButton(onClick = { ScheduledBolusRuntime.cancel(context) }) {
                        Text(stringResource(R.string.scheduled_ui_cancel))
                    }
                }
            }
        }
    }

    // 交互弹层与提示
    val slot = sheetSlot
    if (slot != null) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { sheetSlot = null },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scheduled_slot_manage))
                if (slot >= 0) {
                    TextButton(onClick = {
                        val preset = currentPresetFromForm() ?: return@TextButton
                        ScheduledPresetsStore.upsert(context, slot, preset)
                        reloadPresets(presets, context, config)
                        sheetSlot = null
                    }) { Text(stringResource(R.string.scheduled_slot_overwrite)) }
                    TextButton(onClick = {
                        ScheduledPresetsStore.remove(context, slot)
                        reloadPresets(presets, context, config)
                        sheetSlot = null
                    }) { Text(stringResource(R.string.scheduled_slot_delete)) }
                }
                TextButton(onClick = {
                    val preset = currentPresetFromForm() ?: return@TextButton
                    ScheduledPresetsStore.append(context, preset)
                    reloadPresets(presets, context, config)
                    sheetSlot = null
                }) { Text(stringResource(R.string.scheduled_slot_save_new)) }
                TextButton(onClick = { sheetSlot = null }) { Text(stringResource(R.string.scheduled_slot_cancel)) }
            }
        }
    }

    if (fatalText != null) {
        val pending = pendingApply
        AlertDialog(
            onDismissRequest = { fatalText = null; pendingApply = null },
            confirmButton = {
                TextButton(onClick = {
                    if (pending != null) {
                        applyPreset(pending)
                    }
                    fatalText = null
                    pendingApply = null
                }) { Text(stringResource(R.string.app_ok)) }
            },
            dismissButton = if (pending != null) {
                { TextButton(onClick = { fatalText = null; pendingApply = null }) { Text(stringResource(R.string.app_cancel)) } }
            } else null,
            text = { Text(fatalText!!) },
        )
    }

    if (askExtendedUnknown) {
        AlertDialog(
            onDismissRequest = { askExtendedUnknown = false },
            title = { Text(stringResource(R.string.scheduled_extended_unknown_title)) },
            text = { Text(stringResource(R.string.scheduled_extended_unknown_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askExtendedUnknown = false
                    startExecution(true)
                }) { Text(stringResource(R.string.scheduled_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { askExtendedUnknown = false }) {
                    Text(stringResource(R.string.scheduled_abort))
                }
            },
        )
    }
}

@Composable
private fun PointRow(
    index: Int,
    dose: String,
    onDose: (String) -> Unit,
    interval: String? = null,
    onInterval: ((String) -> Unit)? = null,
    time: String? = null,
    onTime: ((String) -> Unit)? = null,
    timeLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.scheduled_ui_point_n, index + 1),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (interval != null && onInterval != null) {
                OutlinedTextField(
                    value = interval,
                    onValueChange = { onInterval(it.filter { c -> c.isDigit() }.take(4)) },
                    label = { Text(stringResource(R.string.scheduled_field_interval)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (time != null && onTime != null) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { onTime(it.take(5)) },
                    label = { Text(stringResource(R.string.scheduled_field_time)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = dose,
                onValueChange = { onDose(it.filter { c -> c.isDigit() || c == '.' }.take(6)) },
                label = { Text(stringResource(R.string.scheduled_ui_dose_u)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotChip(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val modifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Text(
        text = label,
        fontSize = 12.sp,
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun uPlain(milliunits: Long): String =
    if (milliunits % 1000L == 0L) (milliunits / 1000L).toString()
    else String.format(java.util.Locale.US, "%.2f", milliunits / 1000.0)

@Composable
private fun IssueList(issues: List<PlanIssue>, limits: BolusLimits) {
    Column {
        issues.forEach { issue ->
            Text(
                text = issueText(issue, limits),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
    }
}

private fun reloadPresets(
    into: MutableList<ScheduledPreset>,
    context: android.content.Context,
    config: ScheduledBolusConfig,
) {
    into.clear()
    into += ScheduledPresetsStore.load(context, config.maxPresetCount)
}

private fun parseDose(text: String): Long? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

/** 把 `HH:mm` 落到「今天」；不跨天，所以不加日期修正。 */
private fun parseClock(text: String, now: Long): Long? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    return LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun uText(milliunits: Long): String =
    String.format(java.util.Locale.US, "%.2f", milliunits / 1000.0)

private fun intervalText(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes >= 60) "${minutes / 60}h${minutes % 60}m" else "+${minutes}m"
}

private fun stateText(state: ScheduledBolusChain.State): String = state.name

private fun issueText(issue: PlanIssue, limits: BolusLimits): String = when (issue) {
    is PlanIssue.TooFewLinks -> "至少 ${issue.min} 笔"
    is PlanIssue.TooManyLinks -> "最多 ${issue.max} 笔"
    is PlanIssue.NegativeInterval -> "第 ${issue.step} 笔时间早于上一笔"
    is PlanIssue.CrossesMidnight -> "整链越过了 24:00（第 ${issue.step} 笔）"
    is PlanIssue.DoseOutOfRange -> "第 ${issue.step} 笔剂量超出范围"
    is PlanIssue.DoseOffIncrement -> "第 ${issue.step} 笔剂量不是 ${issue.incrementMilliunits / 1000.0} U 的整数倍"
    is PlanIssue.IntervalTooShort -> "第 ${issue.step} 笔间隔少于 ${issue.minMs / 60_000} 分钟"
    is PlanIssue.IntervalTooLong -> "第 ${issue.step} 笔间隔多于 ${issue.maxMs / 60_000} 分钟"
    is PlanIssue.FirstTimeInPast -> "第一笔时刻已经过去"
    else -> ""
}
