package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.pumppan.controlx2.bolus.state.SessionPhase
import kotlinx.coroutines.delay

/**
 * v2 大剂量分区：入口页面由 [phaseToPage] 单点映射（§4.6.2），
 * 发送只发生在四步页的确认步（S6），取消走 [BolusActionMenu] 的 `-96`（S8a）。
 */
@Composable
fun BolusV2Section(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    var showFlow by rememberSaveable { mutableStateOf(false) }
    val phase = BolusRuntime.coordinator?.sessionState?.value?.phase
    val page = phaseToPage(phase)

    // Saver 只恢复 DRAFT：相位非 DRAFT 时丢弃栈内草稿（§4.7）。
    LaunchedEffect(Unit) {
        if (page != BolusEntryPage.DRAFT) showFlow = false
    }

    if (showFlow) {
        BolusFlowScreen(
            innerPadding = innerPadding,
            onExit = { showFlow = false },
            sendMessage = sendMessage,
            sendPumpCommands = sendPumpCommands,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 一进大剂量页就能看到上次大剂量；静态提示，不弹窗、不拦截。
        LastBolusReminder(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = stringResource(R.string.dashboard_bolus),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (page) {
            BolusEntryPage.DRAFT -> BolusActionMenu(
                onNewBolus = { showFlow = true },
                onBack = onBack,
                sendPumpCommands = sendPumpCommands,
            )

            BolusEntryPage.CANCEL -> BolusActionMenu(
                onNewBolus = { showFlow = true },
                onBack = onBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = false, // 立即段在打 / 正在取消：不给新输入入口
                headerText = phaseHeader(phase),
            )

            BolusEntryPage.EITHER_OR -> BolusActionMenu(
                onNewBolus = { showFlow = true },
                onBack = onBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = true, // 二选一：取消 / 追加普通大剂量
                headerText = phaseHeader(phase),
                newBolusTitle = stringResource(R.string.bolus_v2_action_extra_now),
                newBolusSubtitle = stringResource(R.string.bolus_v2_action_extra_now_sub),
            )

            BolusEntryPage.READONLY_CANCEL -> BolusActionMenu(
                onNewBolus = { showFlow = true },
                onBack = onBack,
                sendPumpCommands = sendPumpCommands,
                showNewBolus = false,
                headerText = phaseHeader(phase),
            )

            BolusEntryPage.DONE -> BolusDonePage()
        }
    }
}

/** 已完成提示页：数秒后自动收起，回到动作表。 */
@Composable
private fun BolusDonePage() {
    LaunchedEffect(Unit) {
        delay(DONE_DISMISS_MS)
        BolusRuntime.coordinator?.holder?.clear()
    }
    Text(
        text = stringResource(R.string.bolus_v2_outcome_success),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
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
