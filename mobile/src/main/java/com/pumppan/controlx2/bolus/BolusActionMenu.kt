package com.pumppan.controlx2.bolus

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest
import com.pumppan.controlx2.bolus.state.CancelDecision
import com.pumppan.controlx2.bolus.state.CancelRowState
import com.pumppan.controlx2.bolus.state.CancelStatus
import com.pumppan.controlx2.bolus.state.SessionPhase
import com.pumppan.controlx2.ui.ActionRow
import com.pumppan.controlx2.ui.NavigationRow
import kotlinx.coroutines.delay

/**
 * S3 动作表：取消行（五态判定）+「新的大剂量」入口。
 * S8a：READY 点击经 [BolusSessionHolder.requestCancel] 判定后，才发 `-96`
 * （`SendType.BUST_CACHE`，cargo = bolusId；禁盲取消 / 断连 fail-fast / 15s 内 3 次节流）。
 */
@Composable
fun BolusActionMenu(
    onNewBolus: () -> Unit,
    onBack: () -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    /** 输注进行中不给「新的大剂量」入口（§4.6.1）；延后段在跑时为二选一的第二项。 */
    showNewBolus: Boolean = true,
    /** 相位标题（取消页 / 二选一页共用本组件时的页头）。 */
    headerText: String? = null,
    /** 二选一页的第二项文案；null 时用「新的大剂量」。 */
    newBolusTitle: String? = null,
    newBolusSubtitle: String? = null,
    /** 取消行隐藏时（如等权限/等状态）的中性说明；null 时用「当前没有进行中的大剂量」。 */
    hiddenText: String? = null,
) {
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    val coordinator = BolusRuntime.coordinator
    // 读取 sessionState 以在报文更新时触发重组；holder 只提供判定。
    val session = coordinator?.sessionState?.value
    val isConnected = connected == true
    val rowState = coordinator?.holder?.cancelRowState(connected = isConnected) ?: CancelRowState.HIDDEN
    // 取消三态需要时间推进（10s 窗口 / 5s 兜底），报文不来时靠本地 ticker 刷新。
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    val cancelStatus = coordinator?.holder?.cancelStatus(tick) ?: CancelStatus.IDLE
    LaunchedEffect(cancelStatus) {
        if (cancelStatus == CancelStatus.SENDING) {
            while (true) {
                delay(CANCEL_TICK_MS)
                tick = System.currentTimeMillis()
            }
        }
    }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val cancelThrottledText = stringResource(R.string.bolus_v2_cancel_reason_throttled)
    val cancelDisconnectedText = stringResource(R.string.bolus_v2_cancel_reason_disconnected)
    val cancelNeedsIdText = stringResource(R.string.bolus_v2_cancel_reason_needs_id)
    val cancelReconcilingText = stringResource(R.string.bolus_v2_cancel_reason_reconciling)
    val cancelIdleText = stringResource(R.string.bolus_v2_status_idle)

    if (!headerText.isNullOrBlank()) {
        Text(
            text = headerText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (rowState == CancelRowState.HIDDEN) {
        Text(
            text = hiddenText ?: stringResource(R.string.bolus_v2_status_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val subtitle = when (rowState) {
            CancelRowState.RECONCILING -> stringResource(R.string.bolus_v2_cancel_reason_reconciling)
            CancelRowState.NEEDS_ID -> stringResource(R.string.bolus_v2_cancel_reason_needs_id)
            CancelRowState.READ_ONLY ->
                if (!isConnected) stringResource(R.string.bolus_v2_cancel_reason_disconnected)
                else if (session?.phase == SessionPhase.UNKNOWN) stringResource(R.string.bolus_v2_cancel_reason_unknown)
                else stringResource(R.string.bolus_v2_cancel_reason_disconnected)
            CancelRowState.READY -> stringResource(R.string.bolus_v2_cancel_ready_sub)
            CancelRowState.HIDDEN -> stringResource(R.string.bolus_v2_status_idle)
        }
        ActionRow(
            title = stringResource(R.string.bolus_v2_cancel_row),
            subtitle = subtitle,
            // 取消中（10s 窗口内）不允许重复发 -96；5s 未停才回到可重试。
            readOnly = rowState != CancelRowState.READY || cancelStatus == CancelStatus.SENDING,
            onClick = {
                notice = when (val decision = coordinator?.holder?.requestCancel(connected = isConnected)) {
                    is CancelDecision.Send -> {
                        sendPumpCommands(SendType.BUST_CACHE, listOf(CancelBolusRequest(decision.bolusId)))
                        null // 状态行由 cancelStatus 统一渲染，避免重复文案
                    }

                    CancelDecision.MissingBolusId -> cancelNeedsIdText
                    CancelDecision.Disconnected -> cancelDisconnectedText
                    CancelDecision.Reconciling -> cancelReconcilingText
                    CancelDecision.Throttled -> cancelThrottledText
                    CancelDecision.NotActive, null -> cancelIdleText
                }
            },
            emphasized = rowState == CancelRowState.READY && cancelStatus != CancelStatus.SENDING,
        )
        // 三态：取消中（10s 窗口）/ 已停止 / 未停止（5s 兜底 → Bolus Not Stopped 断管提示）。
        val statusText = when (cancelStatus) {
            CancelStatus.SENDING -> stringResource(R.string.bolus_v2_cancel_pending)
            CancelStatus.FAILED -> stringResource(R.string.bolus_v2_cancel_not_stopped)
            CancelStatus.CONFIRMED -> stringResource(R.string.bolus_v2_outcome_cancelled)
            CancelStatus.IDLE -> notice
        }
        if (!statusText.isNullOrBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (cancelStatus == CancelStatus.FAILED || notice != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    if (showNewBolus) {
        Spacer(Modifier.height(16.dp))

        NavigationRow(
            title = newBolusTitle ?: stringResource(R.string.bolus_v2_action_new),
            subtitle = newBolusSubtitle ?: stringResource(R.string.bolus_v2_action_new_sub),
            onClick = {
                notice = null
                onNewBolus()
            },
        )
    }

    Spacer(Modifier.height(16.dp))

    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.profile_back))
    }
}

/** 取消三态的时间推进：报文不来时每秒刷新一次，驱动 SENDING → FAILED。 */
private const val CANCEL_TICK_MS = 1_000L
