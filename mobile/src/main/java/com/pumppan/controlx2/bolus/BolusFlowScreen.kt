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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.send.BolusPrepareResult
import com.pumppan.controlx2.bolus.send.BolusSendPlan
import com.pumppan.controlx2.bolus.send.BolusSender
import com.pumppan.controlx2.bolus.state.BolusAdvance
import com.pumppan.controlx2.bolus.state.BolusError
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusSessionMeta
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.SOURCE_APP
import com.pumppan.controlx2.bolus.state.BolusValidationError
import com.pumppan.controlx2.bolus.state.CiqVersion
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.bolus.state.canStartRemoteExtended
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
    val dataStore = LocalDataStore.current
    // CIQ 开关状态：来自 ControlIQInfo*Response.getClosedLoopEnabled（MainActivity 解析入 DataStore）；
    // 未收到报文前为 null，按「不知道」处理，不假设已开启。
    val ciqEnabledState by dataStore.controlIQEnabled.observeAsState()
    val ciqEnabled: Boolean? = ciqEnabledState
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

    // 确认页兜底：正常路径进不来，但通知跳转 / 草稿恢复等入口可能带着延展形状直接落到确认页。
    // 与「应用」用同一个能力函数；拦下时清掉延展段（不并入立即段），退回输入页并提示。
    val extendedBlocked = state.shape != BolusShape.NORMAL && !limits.canStartRemoteExtended()
    LaunchedEffect(state.step, extendedBlocked) {
        if (state.step == BolusFlowStep.CONFIRM && extendedBlocked) {
            val modelName = limits.modelDisplayName ?: context.getString(R.string.bolus_v2_current_model)
            Toast.makeText(
                context,
                context.getString(R.string.bolus_v2_extended_unsupported_model, modelNameInSentence(modelName)),
                Toast.LENGTH_LONG,
            ).show()
            onStateChange(
                state.copy(
                    step = BolusFlowStep.SHAPE,
                    shape = BolusShape.NORMAL,
                    laterMilliunits = 0,
                    extendedSeconds = 0,
                ),
            )
        }
    }

    // S6 发送：pendingPlan 非空 → 发 -94 取 bolusId → 生产通道发送 -98。
    var pendingPlan by remember { mutableStateOf<BolusSendPlan?>(null) }
    var sendErrors by remember { mutableStateOf<List<BolusValidationError>>(emptyList()) }
    var sendFailureRes by remember { mutableStateOf<Int?>(null) }
    // 小总量变形（立即段不足一步 → 实际只发纯方波）需用户确认后才进确认页。
    var smallImmediateConfirm by remember { mutableStateOf(false) }
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
                    // 进入单一状态机的确认阶梯：0–10s / 10–60s / 60s → UNKNOWN。
                    BolusRuntime.coordinator?.onBolusInitiated(
                        bolusId = permission.bolusId,
                        hasImmediateLeg = plan.totalVolumeMilliunits > 0,
                        expectedEndAtMillis = if (plan.usesExtendedFields) {
                            System.currentTimeMillis() + plan.extendedSeconds * 1000L
                        } else {
                            null
                        },
                        // 只用于落盘 single 块的补充字段（shape/剂量/来源），不进运行时状态机。
                        meta = BolusSessionMeta(
                            shape = state.shape.name,
                            nowMilliunits = state.nowMilliunits.toLong(),
                            laterMilliunits = state.laterMilliunits.toLong(),
                            extendedSeconds = state.extendedSeconds.toLong(),
                            source = SOURCE_APP,
                        ),
                    )
                    onStateChange(latestState.copy(step = BolusFlowStep.RESULT))
                }
            }
        }
        pendingPlan = null
    }

    // 小总量变形提示：百分比换算后立即段被步进取整成 0、延展段 > 0，
    // 用户以为在打双波、实际会落成纯方波；必须确认后才继续（不静默提交）。
    if (smallImmediateConfirm) {
        AlertDialog(
            onDismissRequest = { smallImmediateConfirm = false },
            text = {
                Text(
                    stringResource(
                        R.string.bolus_v2_small_immediate_warning,
                        formatDose(limits.bolusIncrementMilliunits, limits.bolusIncrementMilliunits),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        smallImmediateConfirm = false
                        when (val result = state.advance(ciqEnabled, limits, ciqVersion)) {
                            is BolusAdvance.Ok -> onStateChange(result.state)
                            is BolusAdvance.Invalid -> Unit
                        }
                    },
                ) {
                    Text(stringResource(R.string.bolus_v2_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { smallImmediateConfirm = false }) {
                    Text(stringResource(R.string.dashboard_cancel))
                }
            },
        )
    }

    // 发起前「已有进行中」提示（§4.9.9）：只在确认页渲染一行，不阻断、不禁用按钮。
    val inProgressDelivery = BolusRuntime.coordinator?.sessionState?.value
    val inProgressHint = if (
        inProgressDelivery != null &&
        !inProgressDelivery.isFinished &&
        inProgressDelivery.state != BolusDeliveryState.IDLE &&
        inProgressDelivery.state != BolusDeliveryState.CANCELLED
    ) {
        stringResource(R.string.bolus_v2_confirm_in_progress)
    } else {
        null
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
                    inProgressHint = inProgressHint,
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
                                if (needsSmallImmediateConfirm(state)) {
                                    smallImmediateConfirm = true
                                } else {
                                    when (val result = state.advance(ciqEnabled, limits, ciqVersion)) {
                                        is BolusAdvance.Ok -> onStateChange(result.state)
                                        is BolusAdvance.Invalid -> Unit // 就地错误已展示，不前进
                                    }
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

/**
 * S7 结局页：全部由单一输注状态驱动（不再有 60s 看门狗）。
 * CONFIRMING / UNCONFIRMED 先复用既有的「已发出，等待确认」文案；
 * 精确的「未确认开始输注，请检查…」提示留给 S7 文案集合，本轮不新增字符串。
 */
@Composable
private fun BolusResultPanel() {
    val outcome = BolusRuntime.coordinator?.outcomeState?.value ?: BolusOutcome.Pending
    val delivery = BolusRuntime.coordinator?.sessionState?.value
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    val failed = outcome as? BolusOutcome.Failed
    val state = delivery?.state
    val text = when {
        outcome is BolusOutcome.Success -> stringResource(R.string.bolus_v2_outcome_success)
        outcome is BolusOutcome.Cancelled -> stringResource(R.string.bolus_v2_outcome_cancelled)
        // 取消失败与发起失败分开：前者要给出「未停止」的断管指引，不能只说「泵拒绝」。
        failed != null && failed.fromCancel ->
            stringResource(R.string.bolus_v2_outcome_failed_cancel)
        failed != null -> failureText(failed.error)
        connected == false -> stringResource(R.string.bolus_v2_outcome_failed_disconnected)
        state == BolusDeliveryState.UNKNOWN -> stringResource(R.string.bolus_v2_outcome_failed_timeout)
        state == BolusDeliveryState.CANCELLING -> stringResource(R.string.bolus_v2_page_cancelling)
        state == BolusDeliveryState.ACTIVE -> stringResource(R.string.bolus_v2_page_delivering)
        else -> stringResource(R.string.bolus_v2_send_sent)
    }
    val color = when {
        failed != null || connected == false || state == BolusDeliveryState.UNKNOWN ->
            MaterialTheme.colorScheme.error
        outcome is BolusOutcome.Cancelled || state == BolusDeliveryState.CANCELLING ->
            MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * 小总量变形：百分比换算后「立即段 = 0 而延展段 > 0」——用户以为在打双波，实际落成纯方波。
 * percent 为 0 / 100 是明确的纯方波或无延展，不打扰；只在中间占比时提示。
 */
private fun needsSmallImmediateConfirm(state: BolusFlowState): Boolean =
    state.shape != BolusShape.NORMAL &&
        state.percent in 1..99 &&
        state.nowMilliunits == 0 &&
        state.laterMilliunits > 0
