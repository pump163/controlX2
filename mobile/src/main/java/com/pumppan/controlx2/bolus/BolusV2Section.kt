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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.Message
import androidx.compose.runtime.livedata.observeAsState
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusPanelMode
import com.pumppan.controlx2.bolus.state.BolusTab
import com.pumppan.controlx2.bolus.state.BolusTabSession
import com.pumppan.controlx2.bolus.state.PumpCapabilities
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.bolus.state.SessionPhase
import kotlinx.coroutines.delay

/**
 * v2 大剂量分区：顶部三标签（大剂量 / 向导 / 预设），默认「大剂量」，与 [phaseToPage] 映射配合。
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
    val phase = BolusRuntime.coordinator?.sessionState?.value?.phase
    val page = phaseToPage(phase)

    // 相位非 DRAFT 时收回流程页并销毁草稿（§4.7）；进行中的输注由状态机页面照常展示，不清会话。
    LaunchedEffect(page) {
        if (page != BolusEntryPage.DRAFT && session.draft != null) session = session.clearDraft()
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
                            phase = phase,
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
                        BolusWizardPlaceholder()
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
 * 「大剂量」标签的内容：有草稿 → 三步流程页；无草稿 → 按相位映射的动作表 / 结局页。
 */
@Composable
private fun BolusTabPage(
    session: BolusTabSession,
    page: BolusEntryPage,
    phase: SessionPhase?,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 一进大剂量页就能看到上次大剂量；静态提示，不弹窗、不拦截、不可点击。
        LastBolusReminder(
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.dashboard_bolus),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val startFlow = { onSessionChange(session.startFlow(BolusTab.BOLUS)) }
        when (page) {
            BolusEntryPage.DRAFT -> {
                // ABORTED / FAILED 也映射到 DRAFT：失败/中止后要留一行结局提示，
                // 否则用户退出流程页后看不到任何反馈（成功结局不在此重复展示）。
                val outcome = BolusRuntime.coordinator?.outcomeState?.value
                if (outcome is BolusOutcome.Failed) {
                    Text(
                        text = if (outcome.fromCancel) stringResource(R.string.bolus_v2_outcome_failed_cancel)
                        else failureText(outcome.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                BolusActionMenu(
                    onNewBolus = startFlow,
                    onBack = onSectionBack,
                    sendPumpCommands = sendPumpCommands,
                )
            }

            BolusEntryPage.CANCEL -> BolusActionMenu(
                onNewBolus = startFlow,
                onBack = onSectionBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = false, // 立即段在打 / 正在取消：不给新输入入口
                headerText = phaseHeader(phase),
                hiddenText = stringResource(R.string.bolus_v2_cancel_pending),
            )

            BolusEntryPage.EITHER_OR -> BolusActionMenu(
                onNewBolus = startFlow,
                onBack = onSectionBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = true, // 二选一：取消 / 追加普通大剂量
                headerText = phaseHeader(phase),
                newBolusTitle = stringResource(R.string.bolus_v2_action_extra_now),
                newBolusSubtitle = stringResource(R.string.bolus_v2_action_extra_now_sub),
            )

            BolusEntryPage.READONLY_CANCEL -> BolusActionMenu(
                onNewBolus = startFlow,
                onBack = onSectionBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = false,
                headerText = phaseHeader(phase),
                hiddenText = stringResource(R.string.bolus_v2_cancel_pending),
            )

            BolusEntryPage.DONE -> BolusDonePage()
        }
    }
}

/** 「向导」标签占位：只有页标题 + 一句「功能建设中」，不放任何按钮或半成品。 */
@Composable
private fun BolusWizardPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.bolus_v2_tab_wizard),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.bolus_v2_wizard_building),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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

/** 已完成提示页：按结局区分「完成 / 已停止 / 失败」，数秒后自动收起回到动作表。 */
@Composable
private fun BolusDonePage() {
    val outcome = BolusRuntime.coordinator?.outcomeState?.value
    LaunchedEffect(Unit) {
        delay(DONE_DISMISS_MS)
        BolusRuntime.coordinator?.holder?.clear()
    }
    val text = when (outcome) {
        is BolusOutcome.Cancelled -> stringResource(R.string.bolus_v2_outcome_cancelled)
        is BolusOutcome.Failed ->
            if (outcome.fromCancel) stringResource(R.string.bolus_v2_outcome_failed_cancel)
            else failureText(outcome.error)
        else -> stringResource(R.string.bolus_v2_outcome_success)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (outcome is BolusOutcome.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun phaseHeader(phase: SessionPhase?): String = when (phase) {
    SessionPhase.DELIVERING -> stringResource(R.string.bolus_v2_page_delivering)
    SessionPhase.CANCELLING -> stringResource(R.string.bolus_v2_page_cancelling)
    SessionPhase.EXTENDED_RUNNING -> stringResource(R.string.bolus_v2_page_extended_running)
    SessionPhase.UNKNOWN -> stringResource(R.string.bolus_v2_page_unknown)
    SessionPhase.PERMITTING, SessionPhase.AWAITING_PHONE ->
        stringResource(R.string.bolus_v2_page_pending)

    null, SessionPhase.DRAFT, SessionPhase.DONE,
    SessionPhase.ABORTED, SessionPhase.FAILED,
    -> stringResource(R.string.dashboard_bolus)
}

private const val DONE_DISMISS_MS = 4_000L

/** 拖过这段距离即切档（轻点仍走 toggle）。 */
private val PANEL_DRAG_THRESHOLD = 24.dp

/** 半屏最小高度：把手 24 + 标签栏 ~48 + 底栏 ~72 + 内容留白，避免压成一条缝。 */
private val MIN_HALF_HEIGHT = 360.dp
