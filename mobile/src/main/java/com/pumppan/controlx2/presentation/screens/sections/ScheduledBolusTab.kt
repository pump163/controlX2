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
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.GlucoseConverter
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.bolus.BolusDeliveryEvidence
import com.pumppan.controlx2.bolus.bolusDeliveryEvidence
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.withPumpMaxBolus
import com.pumppan.controlx2.ledger.BolusLedger
import com.pumppan.controlx2.ledger.deliveryEvidence
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
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.GlobalMaxBolusSettingsRequest

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

    // ---- 泵侧 Max Bolus：真正的硬上限 ----
    // 能力档案里的 25U 只是机型兜底；泵自己的设置（如 20U）更严，必须取两者较小值，
    // 否则 20–25U 的剂量会一路通过校验，等到发不出去才被泵拒绝。
    val maxBolusState = dataStore.globalMaxBolusSettingsResponse.observeAsState()
    val pumpMaxBolus = maxBolusState.value?.maxBolus
    val hardLimits = remember(limits, pumpMaxBolus) {
        limits.withPumpMaxBolus(pumpMaxBolus)

    }
    LaunchedEffect(maxBolusState.value) {
        if (maxBolusState.value == null) {
            sendPumpCommands(SendType.STANDARD, listOf(GlobalMaxBolusSettingsRequest()))
        }
    }

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

    // 表单与链共用同一份计划：切页 / 中止后回到本页不再看到空输入框。
    // 只在整张表单都空时才回填，避免覆盖用户正在输入的值。
    LaunchedEffect(snapshot) {
        if (doses.any { it.isNotBlank() }) return@LaunchedEffect
        val plan = ScheduledBolusRuntime.currentPlan() ?: return@LaunchedEffect
        doses[0] = uPlain(plan.firstDoseMilliunits)
        plan.resolvedLinks().take(2).forEachIndexed { i, link ->
            doses[i + 1] = uPlain(link.doseMilliunits)
            intervals[i] = (link.delayAfterPreviousMs / 60_000L).toString()
        }
    }

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
        val found = plan.validate(config, hardLimits, now)
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
        val found = refreshed.validate(config, hardLimits, now)
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
                    // 首笔已交出：立刻把链推进到 COUNTING（第 2 笔从此刻起算 +delay）。
                    // 不能等流水「开始证据」——0.05 U 可能在两次轮询之间就输完，永远等不到。
                    ScheduledBolusRuntime.startAfterFirstSend(context, result.bolusId)
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

        // ---- 预约槽（短按套用，长按/管理进弹层）；执行中隐藏，避免改了表单却不影响已 arm 的链 ----
        if (snapshot == null) item {
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

        // ---- 模式互锁（执行中锁死，避免表单语义与已 arm 的链分叉） ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !absoluteMode,
                    onClick = { absoluteMode = false },
                    enabled = snapshot == null,
                    label = { Text(stringResource(R.string.scheduled_mode_relative)) },
                )
                FilterChip(
                    selected = absoluteMode,
                    onClick = { absoluteMode = true },
                    enabled = snapshot == null,
                    label = { Text(stringResource(R.string.scheduled_mode_absolute)) },
                )
            }
        }

        // ---- 三笔输入：未开始时全可改；首笔开始后首行灰化，后两笔在倒数期仍可改 ----
        if (!confirming) {
            val snap = snapshot
            val hints = snap?.let { dueHints(it) } ?: emptyList()
            val clockText: (Long) -> String = { at ->
                Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(clock)
            }
            val firstLocked = snap != null
            val linksEditable = snap == null ||
                snap.state == ScheduledBolusChain.State.IDLE ||
                snap.state == ScheduledBolusChain.State.COUNTING ||
                snap.state == ScheduledBolusChain.State.NOTIFIED

            /** 提交一笔修改：未开始时只改表单；已 arm 时同步改链（负数/非法量忽略）。 */
            fun commitDose(linkIndex: Int, text: String) {
                val dose = parseDose(text)
                if (snap != null && dose != null) {
                    ScheduledBolusRuntime.editPending(
                        context, linkIndex, doseMilliunits = dose,
                    )
                    snapshot = ScheduledBolusRuntime.currentSnapshot()
                }
            }

            fun commitInterval(linkIndex: Int, text: String) {
                val minutes = text.trim().toLongOrNull() ?: return
                if (snap != null) {
                    ScheduledBolusRuntime.editPending(
                        context, linkIndex, intervalMs = minutes * 60_000L,
                    )
                    snapshot = ScheduledBolusRuntime.currentSnapshot()
                }
            }

            fun commitTime(linkIndex: Int, text: String) {
                val at = parseClock(text, System.currentTimeMillis()) ?: return
                if (snap != null) {
                    ScheduledBolusRuntime.editPending(
                        context, linkIndex, absoluteAtMillis = at,
                    )
                    snapshot = ScheduledBolusRuntime.currentSnapshot()
                }
            }

            /** 行首提示：计划 / 预计 / 已开始 / 输注中 / 已执行。 */
            fun linkHint(linkIndex: Int): String? {
                if (snap == null) return null
                if (linkIndex < snap.index) return context.getString(R.string.scheduled_row_done)
                if (linkIndex == snap.index && snap.state == ScheduledBolusChain.State.ACTIVE) {
                    return context.getString(R.string.scheduled_row_running)
                }
                // 链已终止（完成 / 中止）：没发出去的笔不能再写「预计 HH:mm」，
                // 否则中止后看起来还像会发（真机 23:30 复现）。
                if (snap.isTerminal) return context.getString(R.string.scheduled_row_not_sent)
                val at = hints.getOrNull(linkIndex) ?: return null
                return context.getString(R.string.scheduled_row_due_at, clockText(at))
            }

            // 首笔的「已开始」时刻只认流水里记下的实际发出时刻。
            // 不能用 anchorAtMillis：确认第 2 笔时链会把 anchor 推进到确认时刻，
            // 第 1 行就会从 23:16 跳成 23:26（真机 23:27 复现）。
            BolusLedger.version.value
            val firstStartedAt = snap?.knownBolusIds?.firstOrNull()
                ?.let { BolusLedger.entry(it)?.startedAtMillis }
            val firstHint: String? = when {
                snap == null -> null
                snap.state == ScheduledBolusChain.State.IDLE ->
                    context.getString(R.string.scheduled_row_planned_at, clockText(snap.dueAtMillis))
                else -> context.getString(
                    R.string.scheduled_row_started_at,
                    clockText(firstStartedAt ?: snap.anchorAtMillis),
                )
            }

            item {
                PointRow(
                    index = 0,
                    dose = doses[0],
                    onDose = { doses[0] = it },
                    timeLabel = null,
                    hint = firstHint,
                    enabled = !firstLocked,
                )
            }
            item {
                val linkIndex = 0
                PointRow(
                    index = 1,
                    dose = doses[1],
                    onDose = {
                        doses[1] = it
                        commitDose(linkIndex, it)
                    },
                    interval = if (absoluteMode) null else intervals[0],
                    onInterval = {
                        intervals[0] = it
                        commitInterval(linkIndex, it)
                    },
                    time = if (absoluteMode) times[0] else null,
                    onTime = {
                        times[0] = it
                        commitTime(linkIndex, it)
                    },
                    hint = linkHint(linkIndex),
                    enabled = linksEditable && (snap == null || linkIndex >= snap.index),
                )
            }
            item {
                val linkIndex = 1
                PointRow(
                    index = 2,
                    dose = doses[2],
                    onDose = {
                        doses[2] = it
                        commitDose(linkIndex, it)
                    },
                    interval = if (absoluteMode) null else intervals[1],
                    onInterval = {
                        intervals[1] = it
                        commitInterval(linkIndex, it)
                    },
                    time = if (absoluteMode) times[1] else null,
                    onTime = {
                        times[1] = it
                        commitTime(linkIndex, it)
                    },
                    hint = linkHint(linkIndex),
                    enabled = linksEditable && (snap == null || linkIndex >= snap.index),
                )
            }
            if (snap == null) {
                if (issues.isNotEmpty()) {
                    item { IssueList(issues, hardLimits) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { goNext() }) { Text(stringResource(R.string.scheduled_next)) }
                    }
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
                val snapNow = snapshot!!
                val planNow = ScheduledBolusRuntime.currentPlan()
                val total = snapNow.runs.size + 1
                // 「已发出」而非「终态确认」：第二笔实际已输注时，终态回执可能还没回来，
                // 只数 terminalStatusId 会把 2 笔显示成 1 笔（真机 22:12 已复现）。
                val firstSent = snapNow.knownBolusIds.isNotEmpty() ||
                    snapNow.state == ScheduledBolusChain.State.SENDING ||
                    snapNow.state == ScheduledBolusChain.State.ACTIVE ||
                    snapNow.index >= 1
                val sentCount = (if (firstSent) 1 else 0) + snapNow.runs.count { it.bolusId != null }

                // 重组信号：流水收到新回执时刷新证据（只读，不回写流水）。
                BolusLedger.version.value
                // 首笔的泵侧回执在流水里（链只记 bolusId），按 bolusId 对账。
                val firstEntry = snapNow.knownBolusIds.firstOrNull()?.let { BolusLedger.entry(it) }
                val firstEvidence = firstEntry?.deliveryEvidence()
                    ?: bolusDeliveryEvidence(null, null, firstSent)
                val firstDone = firstEvidence != BolusDeliveryEvidence.NONE

                // 醒目状态卡：状态 + 进度 +（中止时）原因
                Surface(
                    color = when (snapNow.state) {
                        ScheduledBolusChain.State.CHAIN_ABORTED -> MaterialTheme.colorScheme.errorContainer
                        ScheduledBolusChain.State.DONE -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = stringResource(
                                R.string.scheduled_run_progress,
                                stateLabel(snapNow.state),
                                sentCount,
                                total,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (snapNow.state == ScheduledBolusChain.State.CHAIN_ABORTED) {
                            snapNow.abortReason?.let { reason ->
                                Spacer(Modifier.height(2.dp))
                                Text(abortLabel(reason), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                val dueList = dueHints(snapNow)
                fun clockOf(ms: Long): String =
                    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

                // 首笔也在执行态里露出来（以前只列第 2/3 笔，首笔打完看不见状态）。
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = if (firstDone) "✓" else "○",
                        modifier = Modifier.width(18.dp),
                        color = if (firstDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                    Text("1.", modifier = Modifier.weight(0.1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = uText(planNow?.firstDoseMilliunits ?: 0L) + " U",
                        modifier = Modifier.weight(0.4f),
                        color = if (firstDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (firstEvidence) {
                            BolusDeliveryEvidence.DELIVERED -> context.getString(R.string.scheduled_row_done)
                            BolusDeliveryEvidence.DELIVERING -> context.getString(R.string.scheduled_row_running)
                            BolusDeliveryEvidence.FAILED -> context.getString(R.string.scheduled_row_failed)
                            BolusDeliveryEvidence.SENT -> context.getString(
                                R.string.scheduled_row_sent_at,
                                // 已发出：用流水里这笔记下的实际发出时刻；
                                // 退回到 anchorAtMillis（IDLE 未发出时不展示「已开始」）。
                                clockOf(firstEntry?.startedAtMillis ?: snapNow.anchorAtMillis),
                            )
                            BolusDeliveryEvidence.NONE ->
                                if (snapNow.state == ScheduledBolusChain.State.IDLE) context.getString(
                                    R.string.scheduled_row_planned_at,
                                    clockOf(snapNow.dueAtMillis),
                                ) else context.getString(
                                    R.string.scheduled_row_due_at,
                                    clockOf(snapNow.dueAtMillis),
                                )
                        },
                        color = if (firstDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                snapNow.runs.forEachIndexed { index, run ->
                    // 唯一口径：已发出 ≠ 已输注；只有泵回执 COMPLETE 才允许显示「已输注」。
                    val evidence = run.bolusId
                        ?.let { BolusLedger.entry(it) }
                        ?.deliveryEvidence()
                        ?: bolusDeliveryEvidence(null, run.terminalStatusId, run.bolusId != null)
                    val done = evidence != BolusDeliveryEvidence.NONE
                    val atMillis = run.startedAtMillis ?: dueList.getOrNull(index)
                    val atText = atMillis?.let { clockOf(it) }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = if (done) "✓" else "○",
                            modifier = Modifier.width(18.dp),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                        Text("${index + 2}.", modifier = Modifier.weight(0.1f),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "${uText(run.link.doseMilliunits)} U",
                            modifier = Modifier.weight(0.4f),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                evidence == BolusDeliveryEvidence.DELIVERED ->
                                    stringResource(R.string.scheduled_row_done)
                                evidence == BolusDeliveryEvidence.FAILED ->
                                    stringResource(R.string.scheduled_row_failed)
                                evidence == BolusDeliveryEvidence.DELIVERING ->
                                    stringResource(R.string.scheduled_row_running)
                                evidence == BolusDeliveryEvidence.SENT ->
                                    atText?.let { context.getString(R.string.scheduled_row_sent_at, it) }
                                        ?: stringResource(R.string.scheduled_row_running)
                                // 链已终止且这一笔没发出：写「未发出」，不要留着「预计 HH:mm」。
                                snapNow.isTerminal -> stringResource(R.string.scheduled_row_not_sent)
                                atText != null -> context.getString(R.string.scheduled_row_due_at, atText)
                                else -> stringResource(R.string.scheduled_row_pending)
                            },
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (!snapNow.isTerminal) {
                    Text(stringResource(R.string.scheduled_cancel_hint), fontSize = 12.sp)
                    // 每一笔到点确认前都必须先看血糖趋势（用户要求，2026-11-13）。
                    if (snapNow.state == ScheduledBolusChain.State.ALERTING ||
                        snapNow.state == ScheduledBolusChain.State.WARNING
                    ) {
                        val cgmValue = dataStore.cgmReading.value
                        val cgmAtMs = dataStore.cgmReadingAt.value ?: 0L
                        val arrow = dataStore.cgmDeltaArrow.value.orEmpty()
                        val unit = dataStore.glucoseUnitPreference.value ?: GlucoseUnit.MGDL
                        val stale = cgmValue == null || cgmAtMs == 0L ||
                            System.currentTimeMillis() - cgmAtMs > 15 * 60_000L
                        val readingText = when {
                            stale -> context.getString(R.string.scheduled_cgm_none)
                            unit == GlucoseUnit.MMOL -> String.format(
                                "%.1f mmol/L %s",
                                cgmValue!! * GlucoseConverter.MGDL_TO_MMOL_FACTOR,
                                arrow,
                            )
                            else -> String.format("%d mg/dL %s", cgmValue!!, arrow)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    text = stringResource(R.string.scheduled_cgm_check_title),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = context.getString(R.string.scheduled_cgm_current, readingText),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    // 到点后：两次确认 → 发当前一笔。自动模式下由下面的 LaunchedEffect 直接走到发送。
                    when (snapNow.state) {
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
                } else {
                    // 已终止（完成 / 中止）后必须留一个出口，否则「下一步」不渲染，
                    // 用户会锁在终止态无法新建计划（真机 23:29 复现）。
                    OutlinedButton(onClick = {
                        ScheduledBolusRuntime.dismiss(context)
                        issues = emptyList()
                        fatalText = null
                        snapshot = ScheduledBolusRuntime.currentSnapshot()
                    }) {
                        Text(stringResource(R.string.scheduled_ui_dismiss))
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
                        val preset = currentPresetFromForm()
                        if (preset == null) {
                            toastText = context.getString(R.string.scheduled_preset_need_values)
                            return@TextButton
                        }
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
                    val preset = currentPresetFromForm()
                    if (preset == null) {
                        toastText = context.getString(R.string.scheduled_preset_need_values)
                        return@TextButton
                    }
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
    /** 行首的状态提示（计划 / 预计 / 已开始 / 输注中）；为空即不显示。 */
    hint: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.scheduled_ui_point_n, index + 1),
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (interval != null && onInterval != null) {
                OutlinedTextField(
                    value = interval,
                    onValueChange = { onInterval(it.filter { c -> c.isDigit() }.take(4)) },
                    label = { Text(stringResource(R.string.scheduled_field_interval)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = enabled,
                    // 左宽右窄：间隔文本长（Gap from previous），剂量框只需要放下 “x.x U”。
                    modifier = Modifier.weight(1.6f),
                )
            }
            if (time != null && onTime != null) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { onTime(it.take(5)) },
                    label = { Text(stringResource(R.string.scheduled_field_time)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1.6f),
                )
            }
            OutlinedTextField(
                value = dose,
                onValueChange = { onDose(it.filter { c -> c.isDigit() || c == '.' }.take(6)) },
                label = { Text(stringResource(R.string.scheduled_ui_dose_u)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotChip(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    // 之前只有 onLongClick != null 时才挂点击事件，「+ 存为新预设」传 null 就整块点不动。
    val modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

/**
 * 每笔（`runs` 下标）仍然有效的应付时刻。
 *
 * **槽位口径**：`runs[0]` 是第 2 笔、`runs[1]` 是第 3 笔，首笔不在 runs 里。
 * 因此 IDLE 状态下的 `dueAtMillis` 是「首笔」的计划时刻，不能直接当 runs[0] 的时刻；
 * 必须先从首笔锚点加一次 `runs[0]` 的 delay 才是第 2 笔，否则第 2/3 行会整体前移一格
 * （真机 2026-11-13 22:59 复现：第 2 笔显示 22:59、第 3 笔显示 23:05，应为 23:04 / 23:10）。
 */
private fun dueHints(snap: ScheduledBolusChain.Snapshot): List<Long?> {
    val out = MutableList<Long?>(snap.runs.size) { null }
    if (snap.runs.isEmpty()) return out

    // 当前这一槽的基准：IDLE = 首笔计划时刻（还没进链）；其余 = 当前 run 的应付时刻。
    var prev = if (snap.state == ScheduledBolusChain.State.IDLE) {
        snap.anchorAtMillis
    } else {
        snap.dueAtMillis
    }
    for (i in snap.index until snap.runs.size) {
        prev = if (i == snap.index && snap.state != ScheduledBolusChain.State.IDLE) {
            // 非 IDLE：当前 run 的 due 已经是这一笔自己的时刻，直接用。
            snap.dueAtMillis
        } else {
            // IDLE 的第 1 个 run / 后续每一笔：上一槽 + 本笔间隔。
            prev + snap.runs[i].link.delayAfterPreviousMs
        }
        out[i] = prev
    }
    return out
}

private fun intervalText(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes >= 60) "${minutes / 60}h${minutes % 60}m" else "+${minutes}m"
}

@Composable
private fun stateLabel(state: ScheduledBolusChain.State): String = stringResource(
    when (state) {
        ScheduledBolusChain.State.IDLE -> R.string.scheduled_state_idle
        ScheduledBolusChain.State.COUNTING -> R.string.scheduled_state_counting
        ScheduledBolusChain.State.NOTIFIED -> R.string.scheduled_state_notified
        ScheduledBolusChain.State.ALERTING -> R.string.scheduled_state_alerting
        ScheduledBolusChain.State.WARNING -> R.string.scheduled_state_warning
        ScheduledBolusChain.State.SENDING -> R.string.scheduled_state_sending
        ScheduledBolusChain.State.ACTIVE -> R.string.scheduled_state_active
        ScheduledBolusChain.State.DONE -> R.string.scheduled_state_done
        ScheduledBolusChain.State.CHAIN_ABORTED -> R.string.scheduled_state_aborted
    },
)

@Composable
private fun abortLabel(reason: ScheduledBolusChain.AbortReason): String = stringResource(
    when (reason) {
        ScheduledBolusChain.AbortReason.USER_CANCELLED -> R.string.scheduled_abort_user_cancelled
        ScheduledBolusChain.AbortReason.CONFIRM_TIMEOUT -> R.string.scheduled_abort_confirm_timeout
        ScheduledBolusChain.AbortReason.MISSED -> R.string.scheduled_abort_missed
        ScheduledBolusChain.AbortReason.LINK_LOSS -> R.string.scheduled_abort_link_loss
        ScheduledBolusChain.AbortReason.FOREIGN_BOLUS -> R.string.scheduled_abort_foreign_bolus
        ScheduledBolusChain.AbortReason.PUMP_ABORTED -> R.string.scheduled_abort_pump_aborted
        ScheduledBolusChain.AbortReason.SEND_FAILED -> R.string.scheduled_abort_send_failed
        ScheduledBolusChain.AbortReason.SEND_UNKNOWN -> R.string.scheduled_abort_send_unknown
        ScheduledBolusChain.AbortReason.FEATURE_DISABLED -> R.string.scheduled_abort_feature_disabled
        ScheduledBolusChain.AbortReason.CROSSES_MIDNIGHT -> R.string.scheduled_abort_crosses_midnight
        ScheduledBolusChain.AbortReason.PREFLIGHT_FAILED -> R.string.scheduled_abort_preflight_failed
    },
)

private fun issueText(issue: PlanIssue, limits: BolusLimits): String {
    // `validate` 里剂量相关的 step 是 0 基（0 = 首笔），界面按 1 基显示；
    // 间隔/负间隔/跨天用的是另一套 1 基序号，不能统一 +1（真机 22:58 复现：
    // 真实报错是第 2/3 笔剂量不在 0.05 网格，界面却显示成「第 1/2 笔」）。
    val step = when (issue) {
        is PlanIssue.DoseOutOfRange -> issue.step + 1
        is PlanIssue.DoseOffIncrement -> issue.step + 1
        is PlanIssue.CrossesMidnight -> if (issue.step == 0) 1 else issue.step
        is PlanIssue.NegativeInterval -> issue.step
        is PlanIssue.IntervalTooShort -> issue.step
        is PlanIssue.IntervalTooLong -> issue.step
        else -> 0
    }
    return when (issue) {
        is PlanIssue.TooFewLinks -> "至少 ${issue.min} 笔"
        is PlanIssue.TooManyLinks -> "最多 ${issue.max} 笔"
        is PlanIssue.NegativeInterval -> "第 $step 笔时间早于上一笔"
        is PlanIssue.CrossesMidnight -> "整链越过了 24:00（第 $step 笔）"
        is PlanIssue.DoseOutOfRange -> "第 $step 笔剂量超出范围"
        is PlanIssue.DoseOffIncrement -> "第 $step 笔剂量不是 ${issue.incrementMilliunits / 1000.0} U 的整数倍"
        is PlanIssue.IntervalTooShort -> "第 $step 笔间隔少于 ${issue.minMs / 60_000} 分钟"
        is PlanIssue.IntervalTooLong -> "第 $step 笔间隔多于 ${issue.maxMs / 60_000} 分钟"
        is PlanIssue.FirstTimeInPast -> "第一笔时刻已经过去"
        else -> ""
    }
}
