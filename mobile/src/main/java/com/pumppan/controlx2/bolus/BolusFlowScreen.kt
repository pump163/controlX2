package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.pumppan.controlx2.bolus.preset.BolusPreset
import com.pumppan.controlx2.bolus.preset.BolusPresetDatabase
import com.pumppan.controlx2.bolus.preset.BolusPresetSaveResult
import com.pumppan.controlx2.bolus.preset.BolusPresetSpec
import com.pumppan.controlx2.bolus.preset.BolusPresetStore
import com.pumppan.controlx2.bolus.send.BolusEvidenceLog
import com.pumppan.controlx2.bolus.send.BolusPrepareResult
import com.pumppan.controlx2.bolus.send.BolusSendPlan
import com.pumppan.controlx2.bolus.send.BolusSender
import com.pumppan.controlx2.bolus.state.BolusAdvance
import com.pumppan.controlx2.bolus.state.BolusError
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource
import com.pumppan.controlx2.bolus.state.BolusValidationError
import com.pumppan.controlx2.bolus.state.PumpCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 四步输入页：入口选择 → 剂量与形状 → 校验与确认 → 结局。
 * 发送走 [BolusSender]（S6）：发送前二次校验能力 → `-94` 取 bolusId →
 * 生产通道 `TO_SERVER_BOLUS_REQUEST_PHONE`（BolusManager 内完成 HMAC 与确认）。
 * 草稿用 rememberSaveable + BolusFlowState.Saver：旋转恢复；返回上一格时模型值保留。
 */
@Composable
fun BolusFlowScreen(
    innerPadding: PaddingValues,
    onExit: () -> Unit,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    var flow by rememberSaveable(stateSaver = BolusFlowState.Saver) { mutableStateOf(BolusFlowState()) }
    // CIQ 状态暂无 UI 可读来源，按 fail-closed 取 null（时长上限 2h）。
    val ciqEnabled: Boolean? = null
    // 型号决定输注步进：X2/Mobi 按 50，未知型号按默认 25（见 BolusLimits.incrementFor）。
    val dataStore = LocalDataStore.current
    val deviceName by dataStore.setupDeviceName.observeAsState()
    val model = remember(deviceName) { determinePumpModel(deviceName.orEmpty()) }
    val limits = remember(model) { BolusLimits.forModel(model) }
    // 能力闸门唯一来源：发送前二次校验也查它（不依赖按钮置灰）。
    val profile = remember(model) { PumpCapabilities.forModel(model) }
    val errors = if (flow.step == BolusFlowStep.SHAPE) {
        flow.validationErrors(ciqEnabled, limits)
    } else {
        emptyList()
    }

    // 预设：纯本地 Room 库（独立文件），不读写任何泵数据。
    val context = LocalContext.current
    val store = remember {
        BolusPresetStore(BolusPresetDatabase.getDatabase(context).bolusPresetDao())
    }
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<BolusPreset>>(emptyList()) }
    var editingPreset by remember { mutableStateOf<BolusPreset?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetError by remember { mutableStateOf<BolusPresetSaveResult?>(null) }
    var justSaved by remember { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(store) { presets = store.list() }

    // S6 发送：pendingPlan 非空 → 发 -94 取 bolusId → 生产通道发送 -98。
    var pendingPlan by remember { mutableStateOf<BolusSendPlan?>(null) }
    var sendErrors by remember { mutableStateOf<List<BolusValidationError>>(emptyList()) }
    var sendFailureRes by remember { mutableStateOf<Int?>(null) }
    val sendErrorText: String? =
        sendErrors.firstOrNull()?.let { errorText(it, ciqEnabled) }
            ?: sendFailureRes?.let { stringResource(it) }

    LaunchedEffect(pendingPlan) {
        val plan = pendingPlan ?: return@LaunchedEffect
        val before = dataStore.bolusPermissionResponse.value
        sendPumpCommands(SendType.BUST_CACHE, listOf(BolusPermissionRequest()))
        var waited = 0L
        var granted: BolusPermissionResponse? = null
        while (waited < PERMISSION_TIMEOUT_MS) {
            delay(PERMISSION_POLL_MS)
            waited += PERMISSION_POLL_MS
            val current = dataStore.bolusPermissionResponse.value
            if (current != null && current !== before) {
                granted = current
                break
            }
        }
        // 用户已返回则放弃发送，不留下悬挂的 -94。
        if (flow.step != BolusFlowStep.CONFIRM) {
            pendingPlan = null
            return@LaunchedEffect
        }
        val permission = granted
        when {
            permission == null || !permission.isPermissionGranted || permission.bolusId <= 0 ->
                sendFailureRes = R.string.bolus_v2_send_permission_failed

            else -> {
                val request = BolusSender.buildRequest(permission.bolusId, plan)
                if (request == null) {
                    sendFailureRes = R.string.bolus_v2_send_build_failed
                } else {
                    val bytes = PumpMessageSerializer.toBytes(request)
                    // 未验证型号的延展：只写本地取证日志（不上传）。
                    BolusEvidenceLog.arm(plan, permission.bolusId, bytes.size)
                    sendMessage(MessagePaths.TO_SERVER_BOLUS_REQUEST_PHONE, bytes)
                    flow = flow.copy(step = BolusFlowStep.RESULT)
                }
            }
        }
        pendingPlan = null
    }

    val presetErrorText: String? = when (presetError) {
        null -> null
        is BolusPresetSaveResult.Saved -> null
        BolusPresetSaveResult.EmptyName -> stringResource(R.string.bolus_v2_preset_error_empty_name)
        BolusPresetSaveResult.DuplicateName -> stringResource(R.string.bolus_v2_preset_error_duplicate)
        BolusPresetSaveResult.LimitReached -> stringResource(R.string.bolus_v2_preset_error_limit)
        BolusPresetSaveResult.NotFound -> stringResource(R.string.bolus_v2_preset_error_missing)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 四步流程每一屏顶部都保留同一行提醒，进入即有上下文。
        LastBolusReminder(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = stringResource(R.string.bolus_v2_step_of, flow.step.ordinal + 1, BolusFlowStep.values().size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(stepTitle(flow.step)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        when (flow.step) {
            BolusFlowStep.ENTRY -> BolusEntryPanel(
                presets = presets,
                limits = limits,
                onSelect = { source: BolusSource ->
                    draftName = ""
                    justSaved = false
                    flow = flow.selectSource(source)
                },
                onPresetSelect = { preset ->
                    // 只预填成草稿初值；本次输注可任意改，不自动回写预设。
                    draftName = preset.name
                    justSaved = false
                    flow = preset.prefilledState(limits)
                },
                onPresetEdit = { preset ->
                    presetError = null
                    editingPreset = preset
                },
                onPresetDelete = { preset ->
                    scope.launch {
                        store.delete(preset.id)
                        presets = store.list()
                    }
                },
                onPresetMove = { preset, direction ->
                    scope.launch { presets = store.move(preset.id, direction) }
                },
            )

            BolusFlowStep.SHAPE -> BolusShapePanel(
                state = flow,
                errors = errors,
                ciqEnabled = ciqEnabled,
                limits = limits,
                onChange = { flow = it },
            )

            BolusFlowStep.CONFIRM -> BolusConfirmPanel(
                state = flow,
                limits = limits,
                justSaved = justSaved,
                experimental = flow.shape != BolusShape.NORMAL && !limits.remoteExtendedBolusVerified,
                errorText = sendErrorText,
                deliverEnabled = pendingPlan == null,
                onDeliver = {
                    // 发送前二次校验：即使按钮被绕过，也必须在 BolusSender 里再查一次能力。
                    when (val prep = BolusSender.prepare(flow.draft(), ciqEnabled, limits, profile)) {
                        is BolusPrepareResult.Blocked -> {
                            sendFailureRes = null
                            sendErrors = prep.errors
                        }

                        is BolusPrepareResult.Ready -> {
                            sendErrors = emptyList()
                            sendFailureRes = null
                            pendingPlan = prep.plan
                        }
                    }
                },
                onSaveAsPreset = {
                    presetError = null
                    showSaveDialog = true
                },
                onBack = { flow = flow.back() },
            )

            BolusFlowStep.RESULT -> BolusResultPanel()
        }

        Spacer(Modifier.height(16.dp))

        if (flow.step == BolusFlowStep.ENTRY) {
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_back))
            }
        } else if (flow.step == BolusFlowStep.SHAPE) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { flow = flow.back() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_back))
                }
                Button(
                    onClick = {
                        when (val result = flow.advance(ciqEnabled, limits)) {
                            is BolusAdvance.Ok -> flow = result.state
                            is BolusAdvance.Invalid -> Unit // 就地错误已展示，不前进
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.bolus_v2_next))
                }
            }
        } else if (flow.step == BolusFlowStep.RESULT) {
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bolus_v2_done))
            }
        }
    }

    editingPreset?.let { preset ->
        BolusPresetEditDialog(
            preset = preset,
            limits = limits,
            errorText = presetErrorText,
            onDismiss = {
                presetError = null
                editingPreset = null
            },
            onSave = { name, spec ->
                scope.launch {
                    when (val result = store.overwrite(preset.id, name, spec)) {
                        is BolusPresetSaveResult.Saved -> {
                            presets = store.list()
                            presetError = null
                            editingPreset = null
                        }

                        else -> presetError = result
                    }
                }
            },
        )
    }

    if (showSaveDialog) {
        BolusSavePresetDialog(
            presets = presets,
            initialName = draftName,
            initialSpec = BolusPresetSpec.from(flow),
            limits = limits,
            errorText = presetErrorText,
            onDismiss = {
                presetError = null
                showSaveDialog = false
            },
            onSaveNew = { name, spec ->
                scope.launch {
                    when (val result = store.saveNew(name, spec)) {
                        is BolusPresetSaveResult.Saved -> {
                            presets = store.list()
                            presetError = null
                            showSaveDialog = false
                            justSaved = true
                            draftName = result.preset.name
                        }

                        else -> presetError = result
                    }
                }
            },
            onOverwrite = { id, name, spec ->
                scope.launch {
                    when (val result = store.overwrite(id, name, spec)) {
                        is BolusPresetSaveResult.Saved -> {
                            presets = store.list()
                            presetError = null
                            showSaveDialog = false
                            justSaved = true
                            draftName = result.preset.name
                        }

                        else -> presetError = result
                    }
                }
            },
        )
    }
}

/** -94 权限响应轮询间隔与超时。 */
private const val PERMISSION_POLL_MS = 200L
private const val PERMISSION_TIMEOUT_MS = 30_000L

/** 结局页等待泵侧证据的最长时间。 */
private const val OUTCOME_TIMEOUT_MS = 60_000L

/** S7 结局页：Success / Cancelled / Failed(NACK·ALARM·TIMEOUT·DISCONNECTED)。 */
@Composable
private fun BolusResultPanel() {
    val outcome = BolusRuntime.coordinator?.outcomeState?.value ?: BolusOutcome.Pending
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(outcome) {
        timedOut = false
        if (outcome is BolusOutcome.Pending) {
            delay(OUTCOME_TIMEOUT_MS)
            timedOut = true
        }
    }
    val failed = outcome as? BolusOutcome.Failed
    val text = when {
        outcome is BolusOutcome.Success -> stringResource(R.string.bolus_v2_outcome_success)
        outcome is BolusOutcome.Cancelled -> stringResource(R.string.bolus_v2_outcome_cancelled)
        failed != null -> failureText(failed.error)
        connected == false -> stringResource(R.string.bolus_v2_outcome_failed_disconnected)
        timedOut -> stringResource(R.string.bolus_v2_outcome_failed_timeout)
        else -> stringResource(R.string.bolus_v2_send_sent)
    }
    val color = when {
        failed != null || connected == false || timedOut -> MaterialTheme.colorScheme.error
        outcome is BolusOutcome.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    if (failed?.statusId != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.bolus_v2_outcome_detail,
                failed.statusId,
                failed.reasonId ?: -1,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun failureText(error: BolusError): String = when (error) {
    BolusError.NACK -> stringResource(R.string.bolus_v2_outcome_failed_nack)
    BolusError.ALARM -> stringResource(R.string.bolus_v2_outcome_failed_alarm)
    BolusError.TIMEOUT -> stringResource(R.string.bolus_v2_outcome_failed_timeout)
    BolusError.DISCONNECTED -> stringResource(R.string.bolus_v2_outcome_failed_disconnected)
    BolusError.UNKNOWN -> stringResource(R.string.bolus_v2_outcome_failed_unknown)
}

private fun stepTitle(step: BolusFlowStep): Int = when (step) {
    BolusFlowStep.ENTRY -> R.string.bolus_v2_step_entry
    BolusFlowStep.SHAPE -> R.string.bolus_v2_step_shape
    BolusFlowStep.CONFIRM -> R.string.bolus_v2_step_confirm
    BolusFlowStep.RESULT -> R.string.bolus_v2_step_result
}
