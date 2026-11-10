package com.pumppan.controlx2.bolus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.jwoglom.controlx2.shared.util.shortTime24
import com.jwoglom.pumpx2.pump.messages.Message
import com.pumppan.controlx2.bolus.state.CancelStatus
import com.pumppan.controlx2.bolus.state.presentBolusStatus
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * 底栏常驻细条（设计定案：**任何页面都能一键取消**的唯一常驻入口）。
 *
 * - 位置：外层 `Scaffold` 的 `bottomBar` 内、应用导航栏上方；不是浮层，不遮内容。
 * - 显隐：只在会话「进行中」出现；无会话 / 已落定即消失（结局由大剂量页段二展示）。
 * - 抑制：`Landing` 在大剂量页（`BOLUS_V2`）不渲染本组件 —— 该页段二自带取消，
 *   避免同屏出现两个取消入口。
 * - 数据：与首页卡片 / 段二共用 [presentBolusStatus] 的同一份派生。
 * - 交互：**两级确认** —— 点整条才露出「取消」按钮，再点按钮才真正发 `-96`；
 *   取消在途 / 失败时自动展开，进度与失败原因必须可见。取消是最高级别动作，但不该为误触买单。
 * - 结构：外层用 `Column`，单笔时视觉与单行一致，将来多笔并存可直接叠行。
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
    // 两级确认：默认只显示状态，点整条才展开取消按钮。
    // 这两句必须在 `isOngoing` 提前返回之前，保证 composable 的 slot 结构稳定。
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(presentation.cancelStatus) {
        // 取消在途 / 失败时强制展开：进度和失败原因不能被收起掩掉。
        if (presentation.cancelStatus != CancelStatus.IDLE) expanded = true
    }
    LaunchedEffect(delivery?.bolusId) {
        // 换了一笔会话就收回按钮，避免误点到上一笔的取消。
        expanded = false
    }

    if (!presentation.isOngoing) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 外层 Column：单笔时视觉与单行一致，将来多笔并存可直接叠行。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = presentation.cancelStatus != CancelStatus.SENDING) {
                    expanded = !expanded
                }
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = bolusStatusText(presentation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 单会话补偿：新会话覆盖掉的那笔延展只留一行提示（不参与取消，见 holder.priorExtended）。
            val prior = coordinator.holder.priorExtended
            if (prior != null) {
                val endText = prior.expectedEndAtMillis?.let { shortTime24(Instant.ofEpochMilli(it)) }
                Text(
                    text = if (endText != null) {
                        stringResource(R.string.bolus_v2_prior_extended_with_end, endText)
                    } else {
                        stringResource(R.string.bolus_v2_prior_extended)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!secondary.isNullOrBlank()) {
                        Text(
                            text = secondary,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (presentation.cancelStatus == CancelStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
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
    }
}

/** 取消三态的时间推进：与页面取消行一致，每秒刷新一次。 */
private const val STRIP_TICK_MS = 1_000L
