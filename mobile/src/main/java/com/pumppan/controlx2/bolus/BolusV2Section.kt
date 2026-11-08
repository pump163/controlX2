package com.pumppan.controlx2.bolus

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.os.SystemClock
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.Message
import androidx.compose.runtime.livedata.observeAsState
import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusPanelMode
import com.pumppan.controlx2.bolus.state.BolusSource
import com.pumppan.controlx2.bolus.state.BolusTab
import com.pumppan.controlx2.bolus.state.BolusTabSession
import com.pumppan.controlx2.bolus.state.PumpCapabilities
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.bolus.wizard.BolusWizardRuntime
import kotlinx.coroutines.delay

/**
 * v2 大剂量分区：顶部三标签（大剂量 / 向导 / 预设），默认「大剂量」，与 [deliveryToPage] 映射配合。
 *
 * - **草稿单例**：唯一一份草稿放在 [BolusTabSession.draft]，标签只记来源标量；
 *   切标签、切高度档位都复用同一份，不新建第二份。
 * - **返回来源标签**：从哪个标签进流程就回哪个；来源未知 / 进程重建恢复失败 → 回「大剂量」。
 * - **高度两档**（全屏 / 半屏，默认全屏）：顶部把手拖动或点击切换；档位持久化到 [Prefs]。
 */
@Composable
fun BolusV2Section(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var session by rememberSaveable(stateSaver = BolusTabSession.Saver) { mutableStateOf(BolusTabSession()) }
    // 每个标签各自的 rememberSaveable 状态（选中项、编辑中的条目、滚动位置）在切走后保留。
    val tabStateHolder = rememberSaveableStateHolder()
    // 首次进入默认全屏；之后沿用上次档位（持久化在既有 prefs 里）。
    var panelMode by rememberSaveable {
        mutableStateOf(BolusPanelMode.fromPref(prefs.bolusPanelMode()))
    }
    val delivery = BolusRuntime.coordinator?.sessionState?.value
    val outcome = BolusRuntime.coordinator?.outcomeState?.value ?: BolusOutcome.Pending
    val page = deliveryToPage(delivery, outcome)

    // 相位非 DRAFT 时收回流程页并销毁草稿（§4.7）；进行中的输注由状态机页面照常展示，不清会话。
    // 向导来源的草稿被收回 = 已成功发送：向导输入随之清空（跨标签保留到此结束）。
    LaunchedEffect(page) {
        if (page != BolusEntryPage.DRAFT && session.draft != null) {
            if (session.draft?.source == BolusSource.CALC) BolusWizardRuntime.clear()
            session = session.clearDraft()
        }
    }

    val dataStore = LocalDataStore.current
    val deviceName by dataStore.setupDeviceName.observeAsState()
    val model = remember(deviceName) { determinePumpModel(deviceName.orEmpty()) }
    // 型号决定输注步进：X2/Mobi 按 50，未知型号按默认 25（见 BolusLimits.forModel）。
    val limits = remember(model) { BolusLimits.forModel(model) }
    // 能力闸门唯一来源：发送前二次校验也查它（不依赖按钮置灰）。
    val profile: PumpCapabilityProfile = remember(model) { PumpCapabilities.forModel(model) }

    // 键盘弹出 → 临时升到全屏：半屏只剩 ~60% 高度，再加底栏会被压成一条缝。
    // 只在渲染上提升，不改写已持久化的档位；键盘收起后回到用户上次选的档位。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val effectiveMode = if (imeVisible) BolusPanelMode.FULL else panelMode
    val dragThresholdPx = with(LocalDensity.current) { PANEL_DRAG_THRESHOLD.toPx() }

    fun requestMode(target: BolusPanelMode) {
        if (target == panelMode) return
        panelMode = target
        prefs.setBolusPanelMode(target.prefValue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        // 半屏：面板只占上方 60%，下方露出背景并压一层遮罩。
        if (effectiveMode == BolusPanelMode.HALF) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            shadowElevation = if (effectiveMode == BolusPanelMode.HALF) 8.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (effectiveMode == BolusPanelMode.HALF) {
                        Modifier
                            // 先给最小高度再按比例取值，空间不足时保住最小可读高度。
                            .heightIn(min = MIN_HALF_HEIGHT)
                            .fillMaxHeight(BolusPanelMode.HALF_HEIGHT_FRACTION)
                    } else {
                        Modifier.fillMaxHeight()
                    }
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 把手固定在面板顶部：拖动过阈值切档，点击 toggle；标签栏在两种档位下都随之固定在顶部。
                BolusPanelHandle(
                    onTap = { requestMode(panelMode.toggled()) },
                    onDragEnd = { dragY -> requestMode(panelMode.afterDrag(dragY, dragThresholdPx)) },
                )
                PrimaryTabRow(selectedTabIndex = session.tab.ordinal) {
                    BolusTab.values().forEach { tab ->
                        Tab(
                            selected = session.tab == tab,
                            onClick = { session = session.select(tab) },
                            text = { Text(stringResource(tabTitle(tab))) },
                        )
                    }
                }
                when (session.tab) {
                    BolusTab.BOLUS -> tabStateHolder.SaveableStateProvider(BolusTab.BOLUS.name) {
                        BolusTabPage(
                            session = session,
                            page = page,
                            delivery = delivery,
                            limits = limits,
                            profile = profile,
                            onSectionBack = onBack,
                            onExitFlow = { session = session.exitFlow() },
                            onSessionChange = { session = it },
                            sendMessage = sendMessage,
                            sendPumpCommands = sendPumpCommands,
                        )
                    }

                    BolusTab.WIZARD -> tabStateHolder.SaveableStateProvider(BolusTab.WIZARD.name) {
                        BolusWizardTab(
                            session = session,
                            limits = limits,
                            profile = profile,
                            // 只有处于可起草相位才允许把结果交给确认页；硬约束：输注中不给入口。
                            canStartFlow = page == BolusEntryPage.DRAFT,
                            onSessionChange = { session = it },
                            sendPumpCommands = sendPumpCommands,
                        )
                    }

                    BolusTab.PRESETS -> tabStateHolder.SaveableStateProvider(BolusTab.PRESETS.name) {
                        BolusPresetsTab(
                            limits = limits,
                            onApply = { preset -> session = session.applyPreset(preset.appliedState(limits)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「大剂量」标签的内容：有草稿 → 三步流程页；无草稿 → 三段式页面（提醒 / 状态 / 入口）。
 */
@Composable
private fun BolusTabPage(
    session: BolusTabSession,
    page: BolusEntryPage,
    delivery: BolusDelivery?,
    limits: BolusLimits,
    profile: PumpCapabilityProfile,
    onSectionBack: () -> Unit,
    onExitFlow: () -> Unit,
    onSessionChange: (BolusTabSession) -> Unit,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val draft = session.draft
    if (draft != null) {
        BolusFlowScreen(
            state = draft,
            limits = limits,
            profile = profile,
            onStateChange = { onSessionChange(session.updateDraft(it)) },
            onExit = onExitFlow,
            sendMessage = sendMessage,
            sendPumpCommands = sendPumpCommands,
        )
        return
    }

    // 结局停留自动收起：只统计页面处于 RESUMED 的时间；切后台/离开时暂停，回来接着计。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page, limits.endingPageDismissMillis, lifecycle) {
        if (page != BolusEntryPage.DONE) return@LaunchedEffect
        var remaining = limits.endingPageDismissMillis
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (remaining <= 0L) return@repeatOnLifecycle
            val startedAt = SystemClock.elapsedRealtime()
            var completed = false
            try {
                delay(remaining)
                remaining = 0L
                completed = true
            } finally {
                if (!completed) remaining -= SystemClock.elapsedRealtime() - startedAt
            }
            // 必须走协调器入口：清会话之外还要复位结局并立刻推送 UI，
            // 否则页面会一直停在完成态，直到下一条泵报文才刷新。
            if (completed) BolusRuntime.coordinator?.dismissFinish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 段一 · 提醒：一行灰字，任何状态下都在，与当前这一笔无关。
        LastBolusReminder(
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // 段二 · 状态：标题固定「大剂量」+ 一句状态描述；状态变化只改这一句，不重排整页。
        Text(
            text = stringResource(R.string.dashboard_bolus),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StateDescription(
            delivery = delivery,
            outcome = BolusRuntime.coordinator?.outcomeState?.value ?: BolusOutcome.Pending,
        )
        // 硬约束：只有空闲（DRAFT）/ 已完成（DONE）才允许进输入页；任何进行中的相位
        // （申请中 / 已确认 / 输注中 / 取消中 / 状态未知）都不给入口，也不允许程序性进入。
        val startFlow = {
            if (page == BolusEntryPage.DRAFT || page == BolusEntryPage.DONE) {
                onSessionChange(session.startFlow(BolusTab.BOLUS))
            }
        }
        // 段三 · 入口加按键：空闲与完成态共用同一张表（只换段二那句文案）；
        // 输注期间整段不出现（取消行属于段二，见 BolusActionMenu）。
        when (page) {
            BolusEntryPage.DRAFT, BolusEntryPage.DONE -> BolusActionMenu(
                onNewBolus = startFlow,
                onBack = onSectionBack,
                sendPumpCommands = sendPumpCommands,
            )

            BolusEntryPage.CANCEL, BolusEntryPage.READONLY_CANCEL -> BolusActionMenu(
                onNewBolus = startFlow,
                onBack = onSectionBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = false,
            )
        }
    }
}

/**
 * 面板把手：按住拖动过 [PANEL_DRAG_THRESHOLD] 切换档位（向下收半屏 / 向上扩全屏），
 * 轻点则 toggle。不做连续拖拽，也没引入 BottomSheet 的锚点体系——与顶部 TabRow 天然兼容。
 */
@Composable
private fun BolusPanelHandle(
    onTap: () -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    var accumulated by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onDragEnd = {
                        // 阈值判断放在 afterDrag 里，这里只交累计位移，避免两处各写一份。
                        onDragEnd(accumulated)
                        accumulated = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulated += amount.y
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
    }
}

private fun tabTitle(tab: BolusTab): Int = when (tab) {
    BolusTab.BOLUS -> R.string.dashboard_bolus
    BolusTab.WIZARD -> R.string.bolus_v2_tab_wizard
    BolusTab.PRESETS -> R.string.bolus_v2_entry_preset
}

/**
 * 完成态那一行的文案：与通知同源（只看 [BolusOutcome]），完成页与空闲页共用同一个槽位，
 * 不再单独维护一套「完成页」文案。
 */
@Composable
private fun outcomeText(outcome: BolusOutcome?): String = when (outcome) {
    is BolusOutcome.Cancelled -> stringResource(R.string.bolus_v2_outcome_cancelled)
    is BolusOutcome.Failed ->
        if (outcome.fromCancel) stringResource(R.string.bolus_v2_outcome_failed_cancel)
        else failureText(outcome.error)

    else -> stringResource(R.string.bolus_v2_outcome_success)
}

/**
 * 段二 · 状态描述：**唯一**一句当前状态文案，只看 [BolusDelivery] 与 [BolusOutcome]。
 * 原先横幅 / 页头 / 正文各写一套，才出现「已发出请求」与「取消请求已发出」互相打架。
 */
@Composable
private fun StateDescription(delivery: BolusDelivery?, outcome: BolusOutcome) {
    val failed = outcome is BolusOutcome.Failed
    val text = when {
        delivery == null ->
            if (failed) outcomeText(outcome) else stringResource(R.string.bolus_v2_status_idle)

        delivery.isFinished -> outcomeText(outcome)

        delivery.state == BolusDeliveryState.CONFIRMING ||
            delivery.state == BolusDeliveryState.UNCONFIRMED ->
            stringResource(R.string.bolus_v2_page_pending)

        delivery.state == BolusDeliveryState.ACTIVE ->
            if (delivery.isExtendedRunning) stringResource(R.string.bolus_v2_page_extended_running)
            else stringResource(R.string.bolus_v2_page_delivering)

        delivery.state == BolusDeliveryState.CANCELLING ->
            stringResource(R.string.bolus_v2_page_cancelling)

        delivery.state == BolusDeliveryState.UNKNOWN ->
            stringResource(R.string.bolus_v2_page_unknown)

        else ->
            if (failed) outcomeText(outcome) else stringResource(R.string.bolus_v2_status_idle)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 拖过这段距离即切档（轻点仍走 toggle）。 */
private val PANEL_DRAG_THRESHOLD = 24.dp

/** 半屏最小高度：把手 24 + 标签栏 ~48 + 底栏 ~72 + 内容留白，避免压成一条缝。 */
private val MIN_HALF_HEIGHT = 360.dp
