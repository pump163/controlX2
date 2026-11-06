package com.pumppan.controlx2.bolus

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQInfoV1Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQInfoV2Response
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
import com.pumppan.controlx2.bolus.state.BolusValidationError
import com.pumppan.controlx2.bolus.state.CiqVersion
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 三步输入页（原「入口选择」步已移到标签栏）：剂量与形状 → 校验与确认 → 结局。
 * 发送走 [BolusSender]（S6）：发送前二次校验能力 → `-94` 取 bolusId →
 * 生产通道 `TO_SERVER_BOLUS_REQUEST_PHONE`（BolusManager 内完成 HMAC 与确认）。
 *
 * 草稿由上层（[BolusV2Section]）以**全局唯一一份**持有并通过 [state] 传入：
 * 本组件不自己保存草稿，切标签不会产生第二份。
 * 底部按钮固定在页面尾部（[BolusBottomBar]），不随内容滚动，键盘弹出时不遮挡。
 */
@Composable
fun BolusFlowScreen(
    state: BolusFlowState,
    limits: BolusLimits,
    profile: PumpCapabilityProfile,
    onStateChange: (BolusFlowState) -> Unit,
    onExit: () -> Unit,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    // 发送协程里要读「当前」相位：state 是形参，改用 rememberUpdatedState 防捕获旧值。
    val latestState by rememberUpdatedState(state)
    // CIQ 开关状态暂无独立数据源；版本另从 ControlIQInfo 响应取（见下）。
    val ciqEnabled: Boolean? = null
    val dataStore = LocalDataStore.current
    // Control-IQ 版本信号：V2（Control-IQ+ 1.5）→ 8h；V1（1.0）→ 2h；
    // 拿不到响应 → null（保守按 2h，不猜 8h）。
    val ciqInfo by dataStore.controlIQInfoResponse.observeAsState()
    val ciqVersion = remember(ciqInfo) {
        when (ciqInfo) {
            is ControlIQInfoV2Response -> CiqVersion.V2
            is ControlIQInfoV1Response -> CiqVersion.V1
            else -> null
        }
    }
    val errors = if (state.step == BolusFlowStep.SHAPE) {
        state.validationErrors(ciqEnabled, limits, ciqVersion)
    } else {
        emptyList()
    }
    // §7.5：泵的 Max Bolus 设置只做提示不硬拦；取不到响应时不做任何提示。
    val maxBolusSettings by dataStore.globalMaxBolusSettingsResponse.observeAsState()
    val maxBolusMilliunits = maxBolusSettings?.maxBolus
    val overMaxBolus = state.step == BolusFlowStep.CONFIRM &&
        maxBolusMilliunits != null && state.totalMilliunits > maxBolusMilliunits

    // 第 2 页「+」：把当前输入值存为预设（追加末尾、自动命名）。
    val context = LocalContext.current
    val presetStore = remember(limits) {
        BolusPresetStore(
            BolusPresetDatabase.getDatabase(context, limits.bolusIncrementMilliunits).bolusPresetDao()
        )
    }
    val scope = rememberCoroutineScope()

    // S6 发送：pendingPlan 非空 → 发 -94 取 bolusId → 生产通道发送 -98。
    var pendingPlan by remember { mutableStateOf<BolusSendPlan?>(null) }
    var sendErrors by remember { mutableStateOf<List<BolusValidationError>>(emptyList()) }
    var sendFailureRes by remember { mutableStateOf<Int?>(null) }
    val sendErrorText: String? =
        sendErrors.firstOrNull()?.let { errorText(it, ciqEnabled, ciqVersion, limits) }
            ?: sendFailureRes?.let { stringResource(it) }

    // 发送前二次校验：即使按钮被绕过，也必须在 BolusSender 里再查一次能力。
    val deliver: () -> Unit = {
        when (val prep = BolusSender.prepare(state.draft(), ciqEnabled, limits, profile, ciqVersion)) {
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
    }

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
        if (latestState.step != BolusFlowStep.CONFIRM) {
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
                    onStateChange(latestState.copy(step = BolusFlowStep.RESULT))
                }
            }
        }
        pendingPlan = null
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 输入/确认/结局每一屏顶部都保留同一行提醒（确认页是最后一次拦人的位置）。
            LastBolusReminder(
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = stringResource(R.string.bolus_v2_step_of, state.step.ordinal + 1, BolusFlowStep.values().size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(stepTitle(state.step)),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            when (state.step) {
                BolusFlowStep.SHAPE -> BolusShapePanel(
                    state = state,
                    errors = errors,
                    ciqEnabled = ciqEnabled,
                    limits = limits,
                    ciqVersion = ciqVersion,
                    onChange = onStateChange,
                )

                BolusFlowStep.CONFIRM -> BolusConfirmPanel(
                    state = state,
                    limits = limits,
                    experimental = state.shape != BolusShape.NORMAL && !limits.remoteExtendedBolusVerified,
                    overMaxBolus = overMaxBolus,
                    maxBolusText = maxBolusMilliunits?.let { formatDose(it, limits.bolusIncrementMilliunits) },
                    errorText = sendErrorText,
                    deliverEnabled = pendingPlan == null,
                    onDeliver = deliver,
                    onBack = { onStateChange(state.back()) },
                    showActions = false,
                )

                BolusFlowStep.RESULT -> BolusResultPanel()
            }
        }

        when (state.step) {
            BolusFlowStep.SHAPE -> {
                // §4.6.7：三枚按钮（返回 / 「+」存预设 / 下一步），固定在页面底部。
                val canSavePreset =
                    errors.isEmpty() && state.advance(ciqEnabled, limits, ciqVersion) is BolusAdvance.Ok
                BolusBottomBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.profile_back))
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // 「+」：最小空号 1..12 自动命名，不覆盖已有预设。
                                    when (presetStore.saveNewAuto(BolusPresetSpec.from(state))) {
                                        is BolusPresetSaveResult.Saved -> Toast.makeText(
                                            context,
                                            R.string.bolus_v2_preset_added,
                                            Toast.LENGTH_SHORT,
                                        ).show()

                                        else -> Toast.makeText(
                                            context,
                                            R.string.bolus_v2_preset_full,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            enabled = canSavePreset,
                        ) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                        Button(
                            onClick = {
                                when (val result = state.advance(ciqEnabled, limits, ciqVersion)) {
                                    is BolusAdvance.Ok -> onStateChange(result.state)
                                    is BolusAdvance.Invalid -> Unit // 就地错误已展示，不前进
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.bolus_v2_next))
                        }
                    }
                    if (!canSavePreset) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.bolus_v2_preset_add_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            BolusFlowStep.CONFIRM -> BolusBottomBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStateChange(state.back()) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_back))
                    }
                    Button(
                        onClick = deliver,
                        enabled = pendingPlan == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (overMaxBolus) R.string.bolus_v2_confirm_continue else R.string.bolus_v2_send,
                            ),
                        )
                    }
                }
            }

            BolusFlowStep.RESULT -> BolusBottomBar {
                Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bolus_v2_done))
                }
            }
        }
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
        // 取消失败与发起失败分开：前者要给出「未停止」的断管指引，不能只说「泵拒绝」。
        failed != null && failed.fromCancel ->
            stringResource(R.string.bolus_v2_outcome_failed_cancel)
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
    // 取消失败：明确给出断管指引，不能只显示状态码。
    if (failed?.fromCancel == true) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.bolus_v2_cancel_not_stopped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
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

/** S7 四类结局文案（NACK / ALARM / TIMEOUT / DISCONNECTED），结局页与完成页共用。 */
@Composable
internal fun failureText(error: BolusError): String = when (error) {
    BolusError.NACK -> stringResource(R.string.bolus_v2_outcome_failed_nack)
    BolusError.ALARM -> stringResource(R.string.bolus_v2_outcome_failed_alarm)
    BolusError.TIMEOUT -> stringResource(R.string.bolus_v2_outcome_failed_timeout)
    BolusError.DISCONNECTED -> stringResource(R.string.bolus_v2_outcome_failed_disconnected)
    BolusError.UNKNOWN -> stringResource(R.string.bolus_v2_outcome_failed_unknown)
}

private fun stepTitle(step: BolusFlowStep): Int = when (step) {
    BolusFlowStep.SHAPE -> R.string.bolus_v2_step_shape
    BolusFlowStep.CONFIRM -> R.string.bolus_v2_step_confirm
    BolusFlowStep.RESULT -> R.string.bolus_v2_step_result
}
