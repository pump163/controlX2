package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.pumppan.controlx2.bolus.state.CancelStatus
import com.pumppan.controlx2.bolus.state.presentBolusStatus
import kotlinx.coroutines.delay

/**
 * 底栏常驻细条（设计定案：**任何页面都能一键取消**的唯一常驻入口）。
 *
 * - 位置：外层 `Scaffold` 的 `bottomBar` 内、应用导航栏上方；不是浮层，不遮内容。
 * - 显隐：只在会话「进行中」出现；无会话 / 已落定即消失（结局由大剂量页段二展示）。
 * - 抑制：`Landing` 在大剂量页（`BOLUS_V2`）不渲染本组件 —— 该页段二自带取消，
 *   避免同屏出现两个取消入口。
 * - 数据：与首页卡片 / 段二共用 [presentBolusStatus] 的同一份派生，只做单行布局。
 */
@Composable
fun BolusStatusStrip(
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator = BolusRuntime.coordinator ?: return
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    val isConnected = connected == true

    // 读取两个快照参与重组；holder 只提供判定。
    val delivery = coordinator.sessionState.value
    val outcome = coordinator.outcomeState.value

    // 取消三态需要时间推进（SENDING → FAILED），报文不来时靠本地 ticker。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val cancelStatus = coordinator.holder.cancelStatus(now)
    LaunchedEffect(cancelStatus) {
        if (cancelStatus == CancelStatus.SENDING) {
            while (true) {
                delay(STRIP_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }

    val presentation = presentBolusStatus(
        delivery = delivery,
        outcome = outcome,
        connected = isConnected,
        now = now,
        holder = coordinator.holder,
    )
    if (!presentation.isOngoing) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = bolusStatusText(presentation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = when {
                    presentation.cancelStatus == CancelStatus.FAILED ->
                        stringResource(R.string.bolus_v2_cancel_not_stopped)

                    presentation.cancelStatus == CancelStatus.SENDING ->
                        stringResource(R.string.bolus_v2_cancel_pending)

                    else -> bolusCancelBlockedReason(
                        row = presentation.cancelRow,
                        deliveryState = presentation.deliveryState,
                        connected = isConnected,
                    )
                }
                if (!secondary.isNullOrBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (presentation.cancelStatus == CancelStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = {
                    coordinator.performCancel(isConnected, sendPumpCommands)
                    now = System.currentTimeMillis()
                },
                enabled = presentation.cancelEnabled,
            ) {
                Text(stringResource(R.string.bolus_v2_cancel_row))
            }
        }
    }
}

/** 取消三态的时间推进：与页面取消行一致，每秒刷新一次。 */
private const val STRIP_TICK_MS = 1_000L
