package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.shortTime24
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusCalcDataSnapshotRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource
import com.pumppan.controlx2.bolus.state.BolusTabSession
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.bolus.state.WizardConfig
import com.pumppan.controlx2.query.QueryDomainRequests
import com.pumppan.controlx2.bolus.wizard.BolusInfusionCheck
import com.pumppan.controlx2.bolus.wizard.BolusWizardCalculator
import com.pumppan.controlx2.bolus.wizard.BolusWizardGlucose
import com.pumppan.controlx2.bolus.wizard.BolusWizardRuntime
import com.pumppan.controlx2.bolus.wizard.BolusWizardStep
import com.pumppan.controlx2.bolus.wizard.BolusWizardValidation
import com.pumppan.controlx2.bolus.wizard.GlucoseUnit
import com.pumppan.controlx2.bolus.wizard.WizardCalcResult
import com.pumppan.controlx2.bolus.wizard.WizardDataOrigin
import com.pumppan.controlx2.bolus.wizard.WizardField
import com.pumppan.controlx2.bolus.wizard.WizardFieldIssue
import com.pumppan.controlx2.bolus.wizard.WizardFieldStatus
import com.pumppan.controlx2.bolus.wizard.WizardGlucose
import com.pumppan.controlx2.bolus.wizard.WizardIob
import com.pumppan.controlx2.bolus.wizard.WizardIobUnavailableReason
import com.pumppan.controlx2.bolus.wizard.WizardIobSource
import com.pumppan.controlx2.bolus.wizard.WizardPumpParams
import com.pumppan.controlx2.bolus.wizard.WizardSnapshot
import com.pumppan.controlx2.bolus.wizard.WizardSnapshots
import com.pumppan.controlx2.bolus.wizard.WizardUnavailableReason
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 向导标签：输入（碳水 / 血糖 / 泵参数）→ 结果 → 确认（复用既有草稿与发送链路）。
 *
 * - 输入状态在 [BolusWizardRuntime]，**不在组合里**：切标签、离开页面都保留。
 * - 血糖字段**常驻**：CGM 有实时值就预填、可改；没有就留空等用户输指血。
 *   手动输入是主路径，不做 LastBG 回落，「空」不等于 0。**不预填越界值**。
 * - 血糖内部只存 mg/dL；mg/dL 禁小数点，mmol 强制一位小数，区间按单位分别判。
 * - 过期阈值、合法区间、上报开关全部来自 [PumpCapabilityProfile.wizard]。
 * - 校验沿用 #61 三态：状态挂在字段值上，按钮只读状态；错误只陈述事实，不给建议。
 */
@Composable
fun BolusWizardTab(
    session: BolusTabSession,
    limits: BolusLimits,
    profile: PumpCapabilityProfile,
    canStartFlow: Boolean,
    onSessionChange: (BolusTabSession) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val config = profile.wizard
    val dataStore = LocalDataStore.current
    val snapshotResponse by dataStore.bolusCalcDataSnapshot.observeAsState()
    val snapshotAt by dataStore.bolusCalcDataSnapshotAt.observeAsState()
    // IOB 只认 ControlIQIOBResponse：值与到达时刻都从 DataStore 取，不读快照报文自带的 iob 字段。
    val iobUnits by dataStore.iobUnits.observeAsState()
    val iobAt by dataStore.iobUpdatedAtMillis.observeAsState()
    // 输注否决信号：本机发送 / 泵历史新增的记录时刻，以及泵真相层的大剂量、延展状态。
    val lastInfusionAt by dataStore.lastInfusionAtMillis.observeAsState()
    val bolusDelivering by dataStore.bolusDelivering.observeAsState()
    val bolusStatusAt by dataStore.bolusStatusUpdatedAtMillis.observeAsState()
    val extendedBolusActive by dataStore.extendedBolusActive.observeAsState()
    val extendedBolusAt by dataStore.extendedBolusUpdatedAtMillis.observeAsState()
    val cgmReading by dataStore.cgmReading.observeAsState()
    val cgmAt by dataStore.cgmReadingAt.observeAsState()
    val g6TxId by dataStore.cgmSetupG6TxId.observeAsState()
    val g7SensorCode by dataStore.cgmSetupG7SensorCode.observeAsState()

    // 能识别到传感器型号就按型号取预填范围；识别不到传 null，配置层取保守交集 40–400。
    val sensorKey = remember(g6TxId, g7SensorCode) {
        when {
            !g7SensorCode.isNullOrBlank() -> "DEXCOM_G7"
            !g6TxId.isNullOrBlank() -> "DEXCOM_G6"
            else -> null
        }
    }

    val context = LocalContext.current
    val wizard = BolusWizardRuntime.state

    // 主动查询是主路径：真向泵发一次只读请求（BUST_CACHE），不是读内存 / HTTP 的周期缓存。
    // 「计算」前的查询不受节流限制；进入标签时的查询按 fetchThrottleMillis 节流。
    val fireQuery: () -> Unit = {
        val stamp = System.currentTimeMillis()
        BolusWizardRuntime.update { it.withFetchStamp(stamp) }
        // 只声明「要这个一次性任务」，请求清单集中在 QueryDomainRequests.BOLUS_PRECHECK；
        // 此后增减请求只改那一处，向导不用跟着动。
        sendPumpCommands(
            SendType.BUST_CACHE,
            QueryDomainRequests.forDomain(QueryDomainRequests.BOLUS_PRECHECK),
        )
    }

    LaunchedEffect(Unit) {
        // 血糖单位与「单位锁定」是跨次进入的长期偏好：绑定后立刻恢复上次的选择，
        // 否则进程重启 / 重进向导都会退回 mg/dL + 未锁定。
        BolusWizardRuntime.attach(context)
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - wizard.lastFetchAtMillis > config.fetchThrottleMillis) {
            fireQuery()
        }
    }

    val now = System.currentTimeMillis()
    val reading = snapshotResponse
        ?.takeIf { !it.isUnacked }
        ?.let {
            WizardSnapshots.readingFromPump(
                carbRatioPerMille = it.carbRatio,
                isfMgdlPerUnit = it.isf,
                targetBgMgdl = it.targetBg,
                maxBolusMilliunits = it.maxBolusAmount,
                carbEntryEnabled = it.carbEntryEnabled,
                atMillis = snapshotAt ?: 0L,
            )
        }
    val snapshot = WizardSnapshots.build(
        cgmMgdl = cgmReading,
        cgmAtMillis = cgmAt,
        raw = reading,
        nowMillis = now,
        config = config,
        sensorKey = sensorKey,
        // 最近一次主动查询的发起时刻：读数到达时间不早于它才算「本次查询」。
        queryAtMillis = wizard.lastFetchAtMillis,
        iobMilliunits = iobUnits?.let { Math.round(it * 1000.0).toInt() },
        iobAtMillis = iobAt,
        lastInfusionAtMillis = lastInfusionAt,
        bolusDelivering = bolusDelivering == true,
        extendedBolusActive = extendedBolusActive == true,
    )

    // 泵真相层的输注状态必须来自**本次查询**（到达时间不早于查询发起时刻）才可信；
    // 否则无法排除「泵上起的延展」，本次一律不扣（按 A「不可用」处理）。
    val queryAt = wizard.lastFetchAtMillis
    val activityFresh = queryAt > 0L &&
        bolusStatusAt != null && bolusStatusAt!! >= queryAt &&
        extendedBolusAt != null && extendedBolusAt!! >= queryAt
    // 三态：false 既可能是「确认没有」，也可能是「这次没问明白」，必须分开。
    val infusionCheck = BolusInfusionCheck.evaluate(
        freshQuery = activityFresh,
        bolusDelivering = bolusDelivering == true,
        extendedBolusActive = extendedBolusActive == true,
    )
    val infusionVeto = snapshot.iobUnavailable == WizardIobUnavailableReason.INFUSION_ACTIVITY

    // 按「计算」时若手上不是本次查询的 IOB，就先发查询，等读数回来再算。
    // 「计算」等的是 IOB 落到本次查询；参数只要没过期就可以先用。
    var pendingCalc by remember { mutableStateOf<PendingCalc?>(null) }
    var queryFailed by remember { mutableStateOf(false) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestActivityFresh by rememberUpdatedState(activityFresh)

    val applyCalc: (PendingCalc, WizardSnapshot) -> Unit = { pending, snap ->
        val params = snap.params
        if (params != null) {
            // 输注否决优先：即使 IOB 是本次查询值也不扣（延展期间实时值最多滞后约 1 U）。
            val veto = snap.iobUnavailable == WizardIobUnavailableReason.INFUSION_ACTIVITY
            val activityTrusted = latestActivityFresh || veto
            val iobForCalc = snap.iob.takeIf { activityTrusted }
            val iobReason = when {
                veto -> WizardIobUnavailableReason.INFUSION_ACTIVITY
                !activityTrusted -> WizardIobUnavailableReason.EXPIRED_CACHE
                else -> snap.iobUnavailable
            }
            val result = BolusWizardCalculator.calculate(
                carbsGrams = pending.carbsGrams?.toDouble(),
                glucoseMgdl = pending.bgMgdl?.toDouble(),
                params = params,
                iob = iobForCalc,
                iobUnavailable = iobReason,
                config = config,
            )
            BolusWizardRuntime.update {
                it.showResult(
                    result = result,
                    bgMgdl = pending.bgMgdl,
                    bgFromCgm = pending.bgFromCgm,
                    bgAtMillis = pending.bgAtMillis,
                    params = params,
                    atMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    LaunchedEffect(
        pendingCalc,
        snapshot.params,
        snapshot.paramsStale,
        snapshot.iob,
        snapshot.iobUnavailable,
        activityFresh,
    ) {
        val pending = pendingCalc ?: return@LaunchedEffect
        // 输注否决已成立时不必再等读数，直接给出「不扣」的结果；
        // 否则 IOB 必须来自本次查询，且输注状态证据也来自本次查询。
        if (snapshot.ready &&
            (infusionVeto || (snapshot.iob?.source == WizardIobSource.CURRENT_QUERY && activityFresh))
        ) {
            applyCalc(pending, snapshot)
            pendingCalc = null
            queryFailed = false
        }
    }

    // 查询超时：按已拿到的证据算 —— 输注中（B）照写；证据不新鲜或缓存过期（A）都不扣。
    LaunchedEffect(pendingCalc) {
        val pending = pendingCalc ?: return@LaunchedEffect
        delay(profile.requestTimeoutMs)
        if (pendingCalc === pending) {
            pendingCalc = null
            val snap = latestSnapshot
            if (snap.ready) {
                applyCalc(pending, snap)
                queryFailed = false
            } else {
                queryFailed = true
            }
        }
    }

    // 用户没动过血糖框时，用实时 CGM 预填；动过就绝不覆盖。
    LaunchedEffect(snapshot.glucose.mgdl, snapshot.glucose.atMillis, wizard.bgUnit, wizard.bgTouched) {
        if (!wizard.bgTouched) {
            BolusWizardRuntime.update { it.prefillFromCgm(snapshot.glucose) }
        }
    }

    val carbsField = BolusWizardValidation.carbs(wizard.carbsText, config)
    val bgField = BolusWizardGlucose.parse(wizard.bgText, wizard.bgUnit, config)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.bolus_v2_wizard_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 唯一的「清空」入口：只有它、完成、成功发送会丢掉向导输入。
            if (wizard.step == BolusWizardStep.INPUT && (wizard.carbsText.isNotEmpty() || wizard.bgText.isNotEmpty())) {
                TextButton(onClick = { BolusWizardRuntime.clear() }) {
                    Text(stringResource(R.string.bolus_v2_wizard_clear))
                }
            }
        }

        // 在跑的大剂量 / 状态不明：两者都必须挡住向导，但说法不同。
        // 「没问明白」不能说成「有延展在跑」，否则用户会以为泵上真的有东西。
        val infusionWarning = when (infusionCheck) {
            BolusInfusionCheck.DELIVERING -> R.string.bolus_v2_ongoing_extended
            BolusInfusionCheck.UNKNOWN -> R.string.bolus_v2_infusion_unknown
            BolusInfusionCheck.NONE -> null
        }
        if (infusionWarning != null) {
            Text(
                text = stringResource(infusionWarning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }

        if (wizard.step == BolusWizardStep.INPUT) {
            WizardInputPage(
                carbsText = wizard.carbsText,
                bgText = wizard.bgText,
                bgUnit = wizard.bgUnit,
                unitLocked = wizard.unitLocked,
                bgFromCgm = wizard.bgFromCgm,
                bgCgmAtMillis = wizard.bgCgmAtMillis,
                paramsStale = snapshot.paramsStale,
                unavailable = snapshot.unavailable,
                iob = snapshot.iob,
                iobUnavailable = snapshot.iobUnavailable,
                nowMillis = now,
                pending = pendingCalc != null,
                queryFailed = queryFailed,
                config = config,
                carbsField = carbsField,
                bgField = bgField,
                onCarbsChange = { text ->
                    BolusWizardRuntime.update { it.withCarbs(filterNumberInput(text, decimals = false)) }
                },
                onBgChange = { text ->
                    BolusWizardRuntime.update { it.withBg(filterNumberInput(text, decimals = true), config) }
                },
                onUnitChange = { unit ->
                    BolusWizardRuntime.update { it.withBgUnit(unit, config) }
                },
                onUnitLockChange = { locked ->
                    BolusWizardRuntime.update { it.withUnitLock(locked) }
                },
                params = snapshot.params,
                onRetry = {
                    queryFailed = false
                    fireQuery()
                },
                onCalculate = { carbsGrams, bgMgdl ->
                    val pending = PendingCalc(
                        carbsGrams = carbsGrams,
                        bgMgdl = bgMgdl,
                        bgFromCgm = wizard.bgFromCgm,
                        bgAtMillis = wizard.bgCgmAtMillis,
                    )
                    // 已经是本次查询、且泵参数仍在短时效内：直接算；否则先真发一次查询再算。
                    if (snapshot.ready && snapshot.iob?.source == WizardIobSource.CURRENT_QUERY) {
                        applyCalc(pending, snapshot)
                    } else {
                        pendingCalc = pending
                        queryFailed = false
                        fireQuery()
                    }
                },
            )
        } else {
            val result = wizard.result
            val params = wizard.resultParams
            if (result != null && params != null) {
                val editedField = BolusWizardValidation.dose(wizard.editedTotalText, limits)
                WizardResultPage(
                    result = result,
                    params = params,
                    bgMgdl = wizard.resultBgMgdl,
                    bgFromCgm = wizard.resultBgFromCgm,
                    bgAtMillis = wizard.resultBgAtMillis,
                    bgUnit = wizard.bgUnit,
                    limits = limits,
                    config = config,
                    canStartFlow = canStartFlow,
                    editedTotalText = wizard.editedTotalText,
                    editedField = editedField,
                    actualTotalMilliunits = wizard.actualTotalMilliunits,
                    resultAtMillis = wizard.resultAtMillis,
                    onEditedTotalChange = { text ->
                        BolusWizardRuntime.update {
                            it.withEditedTotal(filterNumberInput(text, decimals = true), limits)
                        }
                    },
                    onBack = { BolusWizardRuntime.update { it.backToInput() } },
                    onConfirm = {
                        val draft = BolusFlowState(
                            // 先落到带「扩展」开关的剂量输入段（SHAPE），确认页在它之后；
                            // 直接跳 CONFIRM 会跳过扩展开关，等于替用户决定不用扩展。
                            step = BolusFlowStep.SHAPE,
                            source = BolusSource.CALC,
                            shape = BolusShape.NORMAL,
                            nowMilliunits = wizard.actualTotalMilliunits,
                            suggestedMilliunits = result.totalMilliunits.takeIf { wizard.totalEdited },
                            calc = wizard.calcPayload,
                        )
                        onSessionChange(session.applyWizard(draft))
                    },
                )
            }
        }
    }
}

@Composable
private fun WizardInputPage(
    carbsText: String,
    bgText: String,
    bgUnit: GlucoseUnit,
    unitLocked: Boolean,
    bgFromCgm: Boolean,
    bgCgmAtMillis: Long?,
    paramsStale: Boolean,
    unavailable: WizardUnavailableReason?,
    iob: WizardIob?,
    iobUnavailable: WizardIobUnavailableReason?,
    nowMillis: Long,
    pending: Boolean,
    queryFailed: Boolean,
    config: WizardConfig,
    carbsField: WizardField,
    bgField: WizardField,
    onCarbsChange: (String) -> Unit,
    onBgChange: (String) -> Unit,
    onUnitChange: (GlucoseUnit) -> Unit,
    onUnitLockChange: (Boolean) -> Unit,
    params: WizardPumpParams?,
    onRetry: () -> Unit,
    onCalculate: (carbsGrams: Int?, bgMgdl: Int?) -> Unit,
) {
    val carbsFocus = remember { FocusRequester() }
    val bgFocus = remember { FocusRequester() }

    Text(
        text = stringResource(R.string.bolus_v2_wizard_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (unavailable != null) {
        Text(
            text = unavailableText(unavailable, config),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.bolus_v2_wizard_retry))
        }
        return
    }

    OutlinedTextField(
        value = carbsText,
        onValueChange = onCarbsChange,
        label = { Text(stringResource(R.string.bolus_v2_wizard_carbs)) },
        singleLine = true,
        isError = carbsField.status == WizardFieldStatus.INVALID,
        supportingText = { Text(fieldHint(carbsField, config.maxCarbsGrams)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .focusRequester(carbsFocus),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = bgUnit == GlucoseUnit.MG_DL,
            enabled = !unitLocked,
            onClick = { onUnitChange(GlucoseUnit.MG_DL) },
            label = { Text(stringResource(R.string.bolus_v2_wizard_unit_mgdl)) },
        )
        FilterChip(
            selected = bgUnit == GlucoseUnit.MMOL_L,
            enabled = !unitLocked,
            onClick = { onUnitChange(GlucoseUnit.MMOL_L) },
            label = { Text(stringResource(R.string.bolus_v2_wizard_unit_mmol)) },
        )
        Spacer(Modifier.weight(1f))
        IconToggleButton(
            checked = unitLocked,
            onCheckedChange = onUnitLockChange,
        ) {
            Icon(
                imageVector = if (unitLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = stringResource(
                    if (unitLocked) {
                        R.string.bolus_v2_wizard_unit_unlock
                    } else {
                        R.string.bolus_v2_wizard_unit_lock
                    },
                ),
            )
        }
    }

    OutlinedTextField(
        value = bgText,
        onValueChange = onBgChange,
        label = { Text(stringResource(R.string.bolus_v2_wizard_bg)) },
        singleLine = true,
        isError = bgField.status == WizardFieldStatus.INVALID,
        supportingText = {
            Text(bgSupportText(bgField, bgUnit, config, bgFromCgm, bgCgmAtMillis))
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (bgUnit == GlucoseUnit.MG_DL) KeyboardType.Number else KeyboardType.Decimal,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .focusRequester(bgFocus),
    )

    if (params != null) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_params),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        WizardRow(
            label = stringResource(R.string.bolus_v2_wizard_icr),
            value = String.format(Locale.US, "%.2f", params.carbRatioGramsPerUnit),
        )
        // 单位只在 label 里写一次；value 只放数字，避免「校正系数（mg/dL 每 U） 27 mg/dL」这类重复。
        WizardRow(
            label = stringResource(R.string.bolus_v2_wizard_isf, unitLabel(bgUnit)),
            value = isfText(params.isfMgdlPerUnit, bgUnit),
        )
        WizardRow(
            label = stringResource(R.string.bolus_v2_wizard_target, unitLabel(bgUnit)),
            value = BolusWizardGlucose.format(params.targetBgMgdl, bgUnit),
        )
        // IOB 独立时效：泵参数过期不连带作废；界面如实标注来源与时间。
        // 行内只放数值，来源 / 不可用原因另起一行全宽小字；长句不再挤占 label 的宽度。
        WizardRow(
            label = stringResource(R.string.bolus_v2_wizard_iob),
            value = iobValueText(iob),
            warn = iob == null,
        )
        Text(
            text = iobNoteText(iob, iobUnavailable, nowMillis),
            style = MaterialTheme.typography.bodySmall,
            color = if (iob == null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )

        if (paramsStale) {
            Text(
                text = stringResource(
                    R.string.bolus_v2_wizard_params_stale,
                    (config.snapshotStaleMillis / 1000L).toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (pending) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_query_reading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (queryFailed) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_query_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    Button(
        onClick = {
            // #61：按钮只读状态，不推进非法值；焦点落回出错字段。
            when {
                carbsField.status == WizardFieldStatus.INVALID -> carbsFocus.requestFocus()
                bgField.status == WizardFieldStatus.INVALID -> bgFocus.requestFocus()
                carbsField.status == WizardFieldStatus.EMPTY && bgField.status == WizardFieldStatus.EMPTY ->
                    carbsFocus.requestFocus()

                else -> onCalculate(carbsField.value, bgField.value)
            }
        },
        enabled = !pending,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Text(
            stringResource(
                if (pending) R.string.bolus_v2_wizard_query_reading else R.string.bolus_v2_wizard_next,
            ),
        )
    }
}

@Composable
private fun WizardResultPage(
    result: WizardCalcResult,
    params: WizardPumpParams,
    bgMgdl: Int?,
    bgFromCgm: Boolean,
    bgAtMillis: Long?,
    bgUnit: GlucoseUnit,
    limits: BolusLimits,
    config: WizardConfig,
    canStartFlow: Boolean,
    editedTotalText: String,
    editedField: WizardField,
    actualTotalMilliunits: Int,
    resultAtMillis: Long,
    onEditedTotalChange: (String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val increment = limits.bolusIncrementMilliunits
    val suggested = result.totalMilliunits
    val actual = actualTotalMilliunits
    val deliverable = actual > 0
    val editedValid = editedField.status == WizardFieldStatus.EMPTY ||
        editedField.status == WizardFieldStatus.VALID

    Text(
        text = stringResource(R.string.bolus_v2_wizard_result_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    // 竖排公式：每一段只摆这一段的依据与结果，全部来自同一次快照、同一次计算。
    FormulaSegment(
        label = stringResource(R.string.bolus_v2_wizard_result_food),
        expression = if (result.carbsGrams == null) {
            stringResource(R.string.bolus_v2_wizard_formula_food_missing)
        } else {
            stringResource(
                R.string.bolus_v2_wizard_formula_food,
                result.carbsGrams.toString(),
                gramsText(params.carbRatioGramsPerUnit),
            )
        },
        value = formatDose(result.foodMilliunits, increment),
        muted = result.carbsGrams == null,
    )

    val correctionExpression = when {
        result.glucoseMgdl == null ->
            stringResource(R.string.bolus_v2_wizard_formula_correction_missing)

        result.correctionInRange ->
            stringResource(R.string.bolus_v2_wizard_formula_correction_in_range)

        else -> stringResource(
            R.string.bolus_v2_wizard_formula_correction,
            BolusWizardGlucose.format(result.glucoseMgdl, bgUnit),
            BolusWizardGlucose.format(params.targetBgMgdl, bgUnit),
            isfText(params.isfMgdlPerUnit, bgUnit),
            unitLabel(bgUnit),
        )
    }
    FormulaSegment(
        label = stringResource(R.string.bolus_v2_wizard_result_correction),
        expression = correctionExpression,
        value = signedDose(result.correctionGrossMilliunits, increment),
        muted = result.glucoseMgdl == null || result.correctionInRange,
    )

    if (bgMgdl != null) {
        Text(
            text = bgTextOf(bgMgdl, bgFromCgm, bgAtMillis, bgUnit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // IOB 只在上面的校正段真的用到它时才出现；过期时明确写「未扣除」，不让减项悄悄变 0。
    if (result.iobApplied || result.iobStale) {
        val origin = stringResource(
            if (params.origin == WizardDataOrigin.PUMP) {
                R.string.bolus_v2_wizard_iob_from_pump
            } else {
                R.string.bolus_v2_wizard_iob_from_local
            },
        )
        // 只陈述事实：数据来自本次查询还是缓存；不断言泵内部是否现算。
        val queryLabel = stringResource(
            if (result.iobSource == WizardIobSource.CURRENT_QUERY) {
                R.string.bolus_v2_wizard_iob_source_current
            } else {
                R.string.bolus_v2_wizard_iob_source_cache
            },
        )
        val source = "$origin · $queryLabel"
        val ago = agoText((resultAtMillis - params.atMillis).coerceAtLeast(0L))
        val rawIob = String.format(Locale.US, "%.2f", result.iobMilliunits / 1000.0)
        val sourceLine = if (result.iobStale) {
            // 不可用时不写「缓存 · N 分钟前」这种会让人误以为有读数可用的来源句，只写数据出处。
            origin
        } else {
            stringResource(
                R.string.bolus_v2_wizard_iob_source_with_raw,
                source,
                ago,
                rawIob,
            )
        }
        val unavailableLine = when (result.iobUnavailable) {
            WizardIobUnavailableReason.INFUSION_ACTIVITY ->
                stringResource(R.string.bolus_v2_wizard_iob_infusion_risk)

            WizardIobUnavailableReason.EXPIRED_CACHE, null ->
                stringResource(R.string.bolus_v2_wizard_iob_unavailable)
        }
        FormulaSegment(
            label = stringResource(R.string.bolus_v2_wizard_result_iob_deducted),
            // 不可用原因是长句：放进下方全宽小字，右侧数值位只放剂量，避免把 label 挤成竖排。
            expression = if (result.iobStale) "$origin · $unavailableLine" else sourceLine,
            value = stringResource(
                R.string.bolus_v2_wizard_formula_iob,
                formatDose(if (result.iobStale) 0 else result.iobDeductedMilliunits, increment),
            ),
            muted = result.iobStale,
            warn = result.iobStale,
        )
    }

    // 上游口径：IOB 只并入血糖校正段，碳水段不动；超出的部分无处可扣，剂量不变，只提示。
    if (result.iobExcessMilliunits > 0) {
        Text(
            text = stringResource(
                R.string.bolus_v2_wizard_iob_exceeds_correction,
                String.format(Locale.US, "%.2f", result.iobExcessMilliunits / 1000.0),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_suggested),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDose(suggested, increment),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    OutlinedTextField(
        value = editedTotalText,
        onValueChange = onEditedTotalChange,
        label = { Text(stringResource(R.string.bolus_v2_wizard_actual_label)) },
        singleLine = true,
        isError = editedField.status == WizardFieldStatus.INVALID,
        supportingText = {
            Text(
                if (editedField.status == WizardFieldStatus.INVALID) {
                    doseHint(editedField, limits)
                } else {
                    stringResource(R.string.bolus_v2_wizard_actual_hint)
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )

    // 改过就摆出对照，避免「改完就忘了改了什么」。
    if (editedField.status == WizardFieldStatus.VALID && actual != suggested) {
        Text(
            text = stringResource(
                R.string.bolus_v2_wizard_suggested_vs_actual,
                formatDose(suggested, increment),
                formatDose(actual, increment),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    Text(
        text = stringResource(R.string.bolus_v2_wizard_params),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp),
    )
    WizardRow(
        label = stringResource(R.string.bolus_v2_wizard_icr),
        value = String.format(Locale.US, "%.2f", params.carbRatioGramsPerUnit),
    )
    WizardRow(
        label = stringResource(R.string.bolus_v2_wizard_isf, unitLabel(bgUnit)),
        value = isfText(params.isfMgdlPerUnit, bgUnit),
    )
    WizardRow(
        label = stringResource(R.string.bolus_v2_wizard_target, unitLabel(bgUnit)),
        value = BolusWizardGlucose.format(params.targetBgMgdl, bgUnit),
    )

    if (result.belowLow) {
        Text(
            text = stringResource(
                R.string.bolus_v2_wizard_low_bg,
                BolusWizardGlucose.format(config.lowBgMgdl, bgUnit) + " " + unitLabel(bgUnit),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (result.clampedToZero) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_result_clamped),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (!deliverable) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_result_zero),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (!canStartFlow) {
        Text(
            text = stringResource(R.string.bolus_v2_wizard_wait_outcome),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.bolus_v2_wizard_back))
        }
        Button(
            onClick = onConfirm,
            enabled = deliverable && canStartFlow && editedValid,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.bolus_v2_wizard_to_confirm))
        }
    }
}

/** 公式的一段：左侧标题，右侧数值，下方小字写算式 / 来源。 */
@Composable
private fun FormulaSegment(
    label: String,
    expression: String,
    value: String,
    muted: Boolean = false,
    warn: Boolean = false,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    warn -> MaterialTheme.colorScheme.error
                    muted -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Text(
            text = expression,
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WizardRow(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun unavailableText(reason: WizardUnavailableReason, config: WizardConfig): String =
    when (reason) {
        WizardUnavailableReason.DISABLED -> stringResource(R.string.bolus_v2_wizard_unavailable_disabled)
        WizardUnavailableReason.NO_PUMP_PARAMS -> stringResource(R.string.bolus_v2_wizard_unavailable_no_params)
        WizardUnavailableReason.PUMP_PARAMS_INVALID -> stringResource(R.string.bolus_v2_wizard_unavailable_invalid)
    }

/**
 * IOB 行内的数值：单位已写在 label「活性胰岛素（U）」里，这里只放数字；
 * 不可用时给占位符，原因由 [iobNoteText] 单独用一行陈述。
 */
private fun iobValueText(iob: WizardIob?): String =
    if (iob == null) "—" else String.format(Locale.US, "%.2f", iob.milliunits / 1000.0)

/**
 * IOB 行的来源 / 不可用原因：只陈述可确认的事实。
 * - 可用：「本次查询 / 缓存 · N 分钟前」；
 * - 不可用按原因分开 —— A 查不到 / 超窗口 →「本次不可用」；B 输注中 →「可能不准确」。
 * 两种不可用都不显示数值，避免用户把不可信的值当成本次读数。
 * 单独成行是为了让长句获得整行宽度，而不是把 label 挤成一行一个字。
 */
@Composable
private fun iobNoteText(
    iob: WizardIob?,
    iobUnavailable: WizardIobUnavailableReason?,
    nowMillis: Long,
): String {
    if (iob == null) {
        return when (iobUnavailable) {
            WizardIobUnavailableReason.INFUSION_ACTIVITY ->
                stringResource(R.string.bolus_v2_wizard_iob_infusion_risk)

            WizardIobUnavailableReason.EXPIRED_CACHE, null ->
                stringResource(R.string.bolus_v2_wizard_iob_unavailable)
        }
    }
    val source = stringResource(
        if (iob.source == WizardIobSource.CURRENT_QUERY) {
            R.string.bolus_v2_wizard_iob_source_current
        } else {
            R.string.bolus_v2_wizard_iob_source_cache
        },
    )
    val ago = agoText((nowMillis - iob.atMillis).coerceAtLeast(0L))
    return stringResource(R.string.bolus_v2_wizard_iob_source, source, ago)
}

/** 错误只陈述事实：格式不对 / 超出范围，不推测原因、不给修改建议。 */
@Composable
private fun fieldHint(field: WizardField, maxValue: Int): String = when (field.status) {
    WizardFieldStatus.VALID, WizardFieldStatus.EMPTY ->
        stringResource(R.string.bolus_v2_wizard_carbs_max, maxValue)

    WizardFieldStatus.INVALID -> when (field.issue) {
        WizardFieldIssue.ABOVE_MAX -> stringResource(R.string.bolus_v2_wizard_error_above, maxValue.toString())
        WizardFieldIssue.BELOW_MIN -> stringResource(R.string.bolus_v2_wizard_error_below, "0")
        WizardFieldIssue.DECIMAL_NOT_ALLOWED -> stringResource(R.string.bolus_v2_wizard_error_decimal_mgdl)
        WizardFieldIssue.NOT_A_NUMBER -> stringResource(R.string.bolus_v2_wizard_error_number)
        WizardFieldIssue.NOT_ALIGNED, null -> ""
    }
}

@Composable
private fun bgSupportText(
    field: WizardField,
    unit: GlucoseUnit,
    config: WizardConfig,
    fromCgm: Boolean,
    cgmAtMillis: Long?,
): String = when (field.status) {
    WizardFieldStatus.VALID -> if (fromCgm) {
        stringResource(R.string.bolus_v2_wizard_bg_prefill, timeText(cgmAtMillis))
    } else {
        stringResource(R.string.bolus_v2_wizard_bg_manual)
    }

    WizardFieldStatus.EMPTY -> bgRangeHint(unit, config)
    WizardFieldStatus.INVALID -> when (field.issue) {
        WizardFieldIssue.ABOVE_MAX ->
            stringResource(R.string.bolus_v2_wizard_error_above, bgMaxText(unit, config) + " " + unitLabel(unit))

        WizardFieldIssue.BELOW_MIN ->
            stringResource(R.string.bolus_v2_wizard_error_below, bgMinText(unit, config) + " " + unitLabel(unit))

        WizardFieldIssue.DECIMAL_NOT_ALLOWED -> stringResource(R.string.bolus_v2_wizard_error_decimal_mgdl)
        else -> stringResource(R.string.bolus_v2_wizard_error_number)
    }
}

@Composable
private fun bgRangeHint(unit: GlucoseUnit, config: WizardConfig): String =
    stringResource(
        R.string.bolus_v2_wizard_bg_range,
        bgMinText(unit, config),
        bgMaxText(unit, config) + " " + unitLabel(unit),
    )

private fun bgMinText(unit: GlucoseUnit, config: WizardConfig): String = when (unit) {
    GlucoseUnit.MG_DL -> config.fingerstick.mgdlMin.toString()
    GlucoseUnit.MMOL_L -> String.format(Locale.US, "%.1f", config.fingerstick.mmolMin)
}

private fun bgMaxText(unit: GlucoseUnit, config: WizardConfig): String = when (unit) {
    GlucoseUnit.MG_DL -> config.fingerstick.mgdlMax.toString()
    GlucoseUnit.MMOL_L -> String.format(Locale.US, "%.1f", config.fingerstick.mmolMax)
}

@Composable
private fun bgTextOf(mgdl: Int?, fromCgm: Boolean, atMillis: Long?, unit: GlucoseUnit): String {
    if (mgdl == null) return stringResource(R.string.bolus_v2_wizard_bg_none)
    val value = BolusWizardGlucose.format(mgdl, unit)
    val source = if (fromCgm) {
        stringResource(R.string.bolus_v2_wizard_bg_cgm_at, timeText(atMillis))
    } else {
        stringResource(R.string.bolus_v2_wizard_bg_manual)
    }
    return stringResource(R.string.bolus_v2_wizard_bg_with_source, value, source)
}

/** 只保留数字与最多一个小数点；不静默改写用户意图（mg/dL 的小数点会留到校验阶段报错）。 */
private fun filterNumberInput(raw: String, decimals: Boolean): String {
    val normalized = raw.replace(',', '.')
    val hasDot = normalized.contains('.')
    val filtered = normalized.filter { it.isDigit() || (decimals && it == '.') }
    if (!decimals) return filtered
    // 多个小数点只保留第一个，避免不可解析的字符串。
    if (!hasDot) return filtered
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) {
        filtered
    } else {
        filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
    }.take(8)
}

/** 血糖预填 / 读数时刻：走 shared 的 [shortTime24]，与其它大剂量时间同源（24 小时制）。 */
private fun timeText(atMillis: Long?): String {
    if (atMillis == null) return ""
    return shortTime24(Instant.ofEpochMilli(atMillis))
}

/** 只陈述事实的剂量错误提示：不说「看起来像什么」，也不替用户取整。 */
@Composable
private fun doseHint(field: WizardField, limits: BolusLimits): String = when (field.issue) {
    WizardFieldIssue.NOT_ALIGNED -> stringResource(
        R.string.bolus_v2_wizard_error_aligned,
        formatDose(limits.bolusIncrementMilliunits, limits.bolusIncrementMilliunits),
    )

    WizardFieldIssue.BELOW_MIN -> stringResource(
        R.string.bolus_v2_wizard_error_below,
        formatDose(limits.minBolusMilliunits, limits.bolusIncrementMilliunits),
    )

    WizardFieldIssue.ABOVE_MAX -> stringResource(
        R.string.bolus_v2_wizard_error_above,
        formatDose(limits.maxBolusMilliunits, limits.bolusIncrementMilliunits),
    )

    WizardFieldIssue.DECIMAL_NOT_ALLOWED, WizardFieldIssue.NOT_A_NUMBER, null ->
        stringResource(R.string.bolus_v2_wizard_error_number)
}

/** 碳水比按 g/U 显示，整数时不带小数尾巴（10 g/U 而不是 10.00 g/U）。 */
private fun gramsText(gramsPerUnit: Double): String {
    val text = String.format(Locale.US, "%.2f", gramsPerUnit)
    return if (text.endsWith(".00")) text.dropLast(3) else text
}

/** 血糖单位下的 ISF：mg/dL 取整，mmol/L 一位小数。 */
private fun isfText(isfMgdlPerUnit: Int, unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MG_DL -> isfMgdlPerUnit.toString()
    GlucoseUnit.MMOL_L ->
        String.format(Locale.US, "%.1f", isfMgdlPerUnit / BolusWizardGlucose.MGDL_PER_MMOL)
}

private fun unitLabel(unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MG_DL -> "mg/dL"
    GlucoseUnit.MMOL_L -> "mmol/L"
}

/** 「12 分钟前」：只描述时间事实，不含任何判断。 */
@Composable
private fun agoText(ageMillis: Long): String {
    val minutes = (ageMillis / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.bolus_v2_wizard_ago_now)
        minutes < 60 -> stringResource(R.string.bolus_v2_wizard_ago_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.bolus_v2_wizard_ago_hours_only, minutes / 60)
        else -> stringResource(R.string.bolus_v2_wizard_ago_hours, minutes / 60, minutes % 60)
    }
}

private fun signedDose(milliunits: Int, increment: Int): String {
    val sign = if (milliunits < 0) "-" else ""
    return sign + formatDose(kotlin.math.abs(milliunits), increment)
}

/**
 * 按下「计算」时冻结的输入。查询是异步的：等本次查询的读数回来后再用这份快照去算，
 * 保证界面标注「本次查询」时用的确实是本次查询的值。
 */
private data class PendingCalc(
    val carbsGrams: Int?,
    val bgMgdl: Int?,
    val bgFromCgm: Boolean,
    val bgAtMillis: Long?,
)
