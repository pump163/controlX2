package com.pumppan.controlx2.bolus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusTarget
import com.pumppan.controlx2.bolus.state.CancelStatus
import com.pumppan.controlx2.bolus.state.CancelRowState
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * 底栏常驻细条（设计定案：**任何页面都能一键取消**的唯一常驻入口）。
 *
 * - 位置：外层 `Scaffold` 的 `bottomBar` 内、应用导航栏上方；圆角卡片浮起，不横贯整屏。
 * - 显隐：只在有「进行中」的那笔时出现；无会话 / 已落定即消失。
 * - 抑制：`Landing` 在大剂量页（`BOLUS_V2`）不渲染本组件 —— 该页段二自带取消。
 * - 两笔：泵上可同时存在一笔普通 + 一笔扩展。两槽各自一行、各自一个取消按钮；
 *   **行内用「普通 / 扩展」前缀区分，同一行也不会误判**。
 * - 高度：**内容驱动**。一笔一行，两笔两行，没有第二笔就不留空高；
 *   每行 40dp 触摸高度，失败说明按需追加一行。
 * - 交互：两级确认 —— 点整条才露出取消按钮，再点才真正发 `-96`；
 *   取消在途 / 失败时自动展开，按钮禁用并显示失败原因。
 */
@Composable
fun BolusStatusStrip(
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator = BolusRuntime.coordinator ?: return
    val connected by LocalDataStore.current.pumpConnected.observeAsState()
    val isConnected = connected == true

    val mainDelivery = coordinator.sessionState.value?.takeIf { it.isStripVisible() }
    val extendedDelivery = coordinator.extendedState.value?.takeIf { it.isStripVisible() }

    // 取消三态需要时间推进（SENDING → FAILED），两笔各自独立。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val mainCancelStatus = coordinator.holder.cancelStatus(now, BolusTarget.MAIN)
    val extendedCancelStatus = coordinator.holder.cancelStatus(now, BolusTarget.EXTENDED)
    LaunchedEffect(mainCancelStatus, extendedCancelStatus) {
        if (mainCancelStatus == CancelStatus.SENDING || extendedCancelStatus == CancelStatus.SENDING) {
            while (true) {
                delay(STRIP_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }

    // 两级确认：默认只显示状态，点整条才展开取消按钮。
    // 这两句必须在提前返回之前，保证 composable 的 slot 结构稳定。
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(mainCancelStatus, extendedCancelStatus) {
        // 取消在途 / 失败时强制展开：进度和失败原因不能被收起掩掉。
        if (mainCancelStatus != CancelStatus.IDLE || extendedCancelStatus != CancelStatus.IDLE) {
            expanded = true
        }
    }
    LaunchedEffect(mainDelivery?.bolusId, extendedDelivery?.bolusId) {
        // 换了一笔就收回按钮，避免误点到上一笔的取消。
        expanded = false
    }

    if (mainDelivery == null && extendedDelivery == null) {
        // 诊断：细条从「有」变「无」也要留痕，否则现场只看到「闪」却查不到前后状态。
        LaunchedEffect(Unit) {
            Timber.d(
                "bolus strip: hidden main=%s(%s) ext=%s(%s)",
                coordinator.sessionState.value?.bolusId,
                coordinator.sessionState.value?.state,
                coordinator.extendedState.value?.bolusId,
                coordinator.extendedState.value?.state,
            )
        }
        return
    }

    // 诊断：每次显隐 / 状态变化打一行，含 bolusId 与两笔状态，用于定位「没有真实输注却闪细条」。
    LaunchedEffect(mainDelivery?.bolusId, mainDelivery?.state, extendedDelivery?.bolusId, extendedDelivery?.state) {
        Timber.d(
            "bolus strip: visible main=%s(%s) ext=%s(%s) stateRaw=%s/%s",
            mainDelivery?.bolusId,
            mainDelivery?.state,
            extendedDelivery?.bolusId,
            extendedDelivery?.state,
            coordinator.sessionState.value?.state,
            coordinator.extendedState.value?.state,
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = mainCancelStatus != CancelStatus.SENDING) {
                    // 展开（要露出「取消」）时补一次只读核对：细条可能停在两个轮询周期之间，
                    // 泵上已结束/已取消却仍显示「正在输注」。收起不查，避免来回点击刷请求。
                    if (!expanded) coordinator.requestStripStatusRefresh()
                    expanded = !expanded
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (mainDelivery != null) {
                LegRow(
                    delivery = mainDelivery,
                    expanded = expanded,
                    cancelStatus = mainCancelStatus,
                    cancelEnabled = coordinator.holder.cancelRowStateFor(
                        BolusTarget.MAIN, isConnected, now,
                    ) == CancelRowState.READY && mainCancelStatus != CancelStatus.SENDING,
                    onCancel = {
                        coordinator.performCancel(isConnected, sendPumpCommands, BolusTarget.MAIN)
                        now = System.currentTimeMillis()
                    },
                )
            }
            if (extendedDelivery != null) {
                LegRow(
                    delivery = extendedDelivery,
                    expanded = expanded,
                    cancelStatus = extendedCancelStatus,
                    cancelEnabled = coordinator.holder.cancelRowStateFor(
                        BolusTarget.EXTENDED, isConnected, now,
                    ) == CancelRowState.READY && extendedCancelStatus != CancelStatus.SENDING,
                    onCancel = {
                        coordinator.performCancel(isConnected, sendPumpCommands, BolusTarget.EXTENDED)
                        now = System.currentTimeMillis()
                    },
                )
            }
        }
    }
}

/**
 * 一笔输注对应一行：状态点 + 「普通 / 扩展」+ 状态 + 取消按钮。
 * 类型前缀写在文案里，因此两笔并排或上下排列都不会混淆。
 */
@Composable
private fun LegRow(
    delivery: BolusDelivery,
    expanded: Boolean,
    cancelStatus: CancelStatus,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    val extended = delivery.isExtendedLeg()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(7.dp)
                    .background(dotColor(delivery, cancelStatus), CircleShape),
            )
            Text(
                text = legStatusText(delivery, extended),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                TextButton(
                    onClick = onCancel,
                    enabled = cancelEnabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (extended) R.string.bolus_v2_cancel_extended
                            else R.string.bolus_v2_cancel_immediate
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        if (cancelStatus == CancelStatus.FAILED) {
            Text(
                text = stringResource(R.string.bolus_v2_cancel_not_stopped),
                modifier = Modifier.padding(start = 15.dp, end = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 这一笔是「普通」还是「扩展」，由会话身份决定，**不随当前在跑哪一段翻转**。
 *
 * 只按 `hasImmediateLeg` 判：
 * - 普通大剂量（本 App 发出的 total > 0）恒为普通，即使立即段已经输完、正在等 -91；
 * - 泵侧发起的纯方波恒为扩展。
 *
 * 旧写法 `|| immediateLegDoneAtMillis != null` 会让普通那笔在输完后变成「扩展正在输注」，
 * 两笔并存时两行都显示扩展、两个按钮都叫「取消扩展」，用户无法分辨（2026-11-11 真机）。
 */
private fun BolusDelivery.isExtendedLeg(): Boolean = !hasImmediateLeg

@Composable
private fun legStatusText(delivery: BolusDelivery, extended: Boolean): String =
    when (delivery.state) {
        BolusDeliveryState.CANCELLING -> stringResource(
            if (extended) R.string.bolus_v2_leg_extended_cancelling
            else R.string.bolus_v2_leg_immediate_cancelling
        )

        BolusDeliveryState.UNKNOWN -> stringResource(
            if (extended) R.string.bolus_v2_leg_extended_unknown
            else R.string.bolus_v2_leg_immediate_unknown
        )

        else -> stringResource(
            if (extended) R.string.bolus_v2_leg_extended_running
            else R.string.bolus_v2_leg_immediate_running
        )
    }

/** 主行状态点：颜色只做「一眼分辨」，语义仍由文字承担。 */
@Composable
private fun dotColor(delivery: BolusDelivery, cancelStatus: CancelStatus): Color = when {
    cancelStatus == CancelStatus.FAILED || delivery.state == BolusDeliveryState.UNKNOWN ->
        MaterialTheme.colorScheme.error

    delivery.state == BolusDeliveryState.CANCELLING ->
        MaterialTheme.colorScheme.tertiary

    else -> MaterialTheme.colorScheme.primary
}

/**
 * 细条只展示「确有这一笔、且正在进行」的相位（§6 定版口径）。
 *
 * - 必须带 `bolusId`：对账/恢复留下的 UNCONFIRMED、UNKNOWN 占位没有 id，不代表真实输注；
 * - 只认 `ACTIVE` / `CANCELLING`：CONFIRMING 是「已发出、等回包」，还没证据；
 * - 泵上发起的一笔同样会经 46 拿到 id 后显示，仍可取消，便利性不变。
 */
private fun BolusDelivery.isStripVisible(): Boolean =
    bolusId != null && !isFinished && state in setOf(
        BolusDeliveryState.ACTIVE,
        BolusDeliveryState.CANCELLING,
    )

/** 取消三态的时间推进：与页面取消行一致，每秒刷新一次。 */
private const val STRIP_TICK_MS = 1_000L
