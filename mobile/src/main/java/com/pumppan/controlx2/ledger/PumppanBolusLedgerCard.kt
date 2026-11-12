package com.pumppan.controlx2.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知页流水卡（第一刀：只做大剂量）。
 *
 * 布局依据设计 §6 / §19.3：
 * - 进行中的笔**置顶**，不参与时间倒序；
 * - 已终态的笔按终态时刻倒序；
 * - 只显示一笔一行，展开报文明细留待后续（第一刀不做）。
 *
 * 数据来自进程内 [BolusLedger]，不读数据库、不发任何泵请求。
 */
@Composable
fun PumppanBolusLedgerCard(modifier: Modifier = Modifier) {
    val version = BolusLedger.version.value
    val entries = remember(version) { BolusLedger.entries() }
    val ongoing = entries.filter { it.isOngoing }
    val finished = entries.filterNot { it.isOngoing }

    // 有进行中的笔时每秒推进一次：宽限期到期要把笔收成「未确认结束」。
    LaunchedEffect(ongoing.isNotEmpty()) {
        while (ongoing.isNotEmpty()) {
            delay(TICK_MS)
            BolusLedger.sweep(System.currentTimeMillis())
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ledger_bolus_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ledger_bolus_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.ledger_bolus_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // 进行中置顶
            ongoing.forEach { entry ->
                LedgerRow(entry = entry, ongoing = true)
            }

            finished.forEach { entry ->
                LedgerRow(entry = entry, ongoing = false)
            }
        }
    }
}

@Composable
private fun LedgerRow(entry: LedgerEntry, ongoing: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左侧色条：进行中 = 主色，已终态 = 描边色；不靠颜色单独表意，右侧仍有文字。
            Spacer(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(
                        if (ongoing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (entry.isExtended) {
                    stringResource(R.string.ledger_bolus_extended_title)
                } else {
                    stringResource(R.string.ledger_bolus_title_short)
                },
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLine(entry),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = clock(entry.sortAtMillis),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusLine(entry: LedgerEntry): String {
    val delivered = entry.deliveredMilliUnits
    val requested = entry.requestedMilliUnits
    val lastAt = clock(entry.lastSeenAtMillis)

    return when (entry.phase) {
        BolusLedgerPhase.REQUESTING ->
            stringResource(R.string.ledger_bolus_phase_requesting)

        BolusLedgerPhase.DELIVERING -> {
            val amount = requested ?: delivered
            if (amount != null && amount > 0) {
                stringResource(R.string.ledger_bolus_amount_delivering, u(amount), lastAt)
            } else {
                stringResource(R.string.ledger_bolus_phase_delivering)
            }
        }

        BolusLedgerPhase.UNKNOWN ->
            stringResource(R.string.ledger_bolus_phase_unknown)

        BolusLedgerPhase.FINISHED -> finishedLine(entry)
    }
}

@Composable
private fun finishedLine(entry: LedgerEntry): String {
    val delivered = entry.deliveredMilliUnits
    val requested = entry.requestedMilliUnits
    val terminal = terminalText(entry.terminalStatusId)

    // 部分输注：只有 V2（或已有 requested）才能判定，V1 先标「请求量待确认」。
    return when {
        delivered != null && requested != null && requested > 0 && delivered < requested ->
            "${stringResource(R.string.ledger_bolus_amount_partial, u(delivered), u(requested))} · $terminal"

        delivered != null && delivered > 0 ->
            "${stringResource(R.string.ledger_bolus_amount_complete, u(delivered))} · $terminal"

        delivered != null && requested == null && entry.terminalStatusId == TERMINAL_COMPLETE ->
            stringResource(R.string.ledger_bolus_amount_pending_confirmation, u(delivered))

        else -> terminal
    }
}

@Composable
private fun terminalText(statusId: Int?): String = when (statusId) {
    TERMINAL_COMPLETE -> stringResource(R.string.ledger_bolus_term_complete)
    TERMINAL_USER -> stringResource(R.string.ledger_bolus_term_user)
    TERMINAL_ALARM -> stringResource(R.string.ledger_bolus_term_alarm)
    TERMINAL_MALFUNCTION -> stringResource(R.string.ledger_bolus_term_malfunction)
    TERMINAL_WIRELESS -> stringResource(R.string.ledger_bolus_term_wireless)
    TERMINAL_REJECTED -> stringResource(R.string.ledger_bolus_term_rejected)
    TERMINAL_PLGS -> stringResource(R.string.ledger_bolus_term_plgs)
    else -> stringResource(R.string.ledger_bolus_phase_unknown)
}

/** 毫单位 → U（两位小数）。只做展示，不改数据层精度。 */
private fun u(milliUnits: Long): String =
    String.format(Locale.US, "%.2f", milliUnits / 1000.0)

private fun clock(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

private const val TICK_MS = 1_000L

// BolusStatus 的 id（与 LastBolusStatusAbstractResponse.BolusStatus 一致）。
private const val TERMINAL_USER = 0
private const val TERMINAL_ALARM = 1
private const val TERMINAL_MALFUNCTION = 2
private const val TERMINAL_COMPLETE = 3
private const val TERMINAL_WIRELESS = 4
private const val TERMINAL_REJECTED = 5
private const val TERMINAL_PLGS = 6
