package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.CancelRowState
import com.pumppan.controlx2.bolus.state.SessionPhase
import com.pumppan.controlx2.ui.ActionRow
import com.pumppan.controlx2.ui.NavigationRow

/**
 * S3 动作表：取消行（五态只显示、不发送）+「新的大剂量」入口。
 * READY 点击也只显示「取消发送尚未接入（待 S6）」，不调用任何发送通道。
 */
@Composable
fun BolusActionMenu(
    onNewBolus: () -> Unit,
    onBack: () -> Unit,
) {
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    val coordinator = BolusRuntime.coordinator
    // 读取 sessionState 以在报文更新时触发重组；holder 只提供判定。
    val session = coordinator?.sessionState?.value
    val isConnected = connected == true
    val rowState = coordinator?.holder?.cancelRowState(connected = isConnected) ?: CancelRowState.HIDDEN
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val cancelNotWired = stringResource(R.string.bolus_v2_cancel_not_wired)

    if (rowState == CancelRowState.HIDDEN) {
        Text(
            text = stringResource(R.string.bolus_v2_status_idle),
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
            readOnly = rowState != CancelRowState.READY,
            onClick = { notice = cancelNotWired },
            emphasized = rowState == CancelRowState.READY,
        )
        if (!notice.isNullOrBlank()) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    NavigationRow(
        title = stringResource(R.string.bolus_v2_action_new),
        subtitle = stringResource(R.string.bolus_v2_action_new_sub),
        onClick = {
            notice = null
            onNewBolus()
        },
    )

    Spacer(Modifier.height(16.dp))

    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.profile_back))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.bolus_v2_send_not_wired),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
