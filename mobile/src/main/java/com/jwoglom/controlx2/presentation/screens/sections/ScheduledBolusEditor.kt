@file:OptIn(ExperimentalMaterial3Api::class)

package com.pumppan.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.scheduled.PlanIssue
import com.pumppan.controlx2.scheduled.ScheduledBolusChain
import com.pumppan.controlx2.scheduled.ScheduledBolusConfig
import com.pumppan.controlx2.scheduled.ScheduledBolusRuntime
import com.pumppan.controlx2.scheduled.ScheduledBolusStore
import com.pumppan.controlx2.scheduled.ScheduledPlan
import com.pumppan.controlx2.scheduled.validate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Debug「危险指令」组里的定时大剂量编辑页（设计 §13.2，2026-11-12 用户拍板）。
 *
 * 输入形态：第一笔 = 绝对时刻 + 剂量；之后每笔 = 「相对上一笔的间隔 + 剂量」，
 * 同时允许改填绝对时刻，两者在界面上双向换算（[ScheduledPlan.timeline]）。
 *
 * **本页不发胰岛素**：保存只把计划交给 [ScheduledBolusRuntime.arm]，真正的输注要等
 * 首笔在泵上确认开始后、由到点确认框走既有大剂量链路。
 */
@Composable
fun ScheduledBolusEditor(
    innerPadding: PaddingValues = PaddingValues(),
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val config = remember { ScheduledBolusConfig.load(context) }
    val limits = remember { BolusLimits.forModel(PumppanPrefs.pumpModel(context).model) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    var firstTime by remember { mutableStateOf(nextWholeHour()) }
    var firstDose by remember { mutableStateOf("") }
    // 每笔后续预约：时刻模式 / 间隔模式 + 剂量；最多 maxChainCount - 1 笔。
    val linkIntervals = remember { mutableStateListOf("", "") }
    val linkTimes = remember { mutableStateListOf("", "") }
    val linkUseAbsolute = remember { mutableStateListOf(false, false) }
    val linkDoses = remember { mutableStateListOf("", "") }
    var linkCount by remember { mutableStateOf(0) }

    var persisted by remember { mutableStateOf(ScheduledBolusStore.load(context)) }

    LazyColumn(
        contentPadding = innerPadding,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(stringResource(R.string.settings_scheduled_bolus))
                Divider()
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.scheduled_ui_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 第一笔：绝对时刻 + 剂量 ----
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.scheduled_ui_first_point),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = firstTime,
                            onValueChange = { firstTime = it.take(5) },
                            label = { Text(stringResource(R.string.scheduled_ui_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = firstDose,
                            onValueChange = { firstDose = it },
                            label = { Text(stringResource(R.string.scheduled_ui_dose_u)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- 后续每一笔：间隔 / 绝对时刻 二选一，剂量必填 ----
            items(linkCount) { index ->
                val pointNumber = index + 2
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Divider()
                    Text(
                        text = stringResource(R.string.scheduled_ui_point_n, pointNumber),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            linkUseAbsolute[index] = false
                        }) {
                            Text(
                                stringResource(R.string.scheduled_ui_mode_interval),
                                color = if (!linkUseAbsolute[index]) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            // 切到绝对时刻时，用当前时刻预填，避免空值让校验莫名其妙失败。
                            if (linkTimes[index].isBlank()) {
                                linkTimes[index] = previewPointTime(
                                    firstTime = firstTime,
                                    intervals = linkIntervals.take(index + 1),
                                    times = linkTimes.take(index + 1),
                                    useAbsolute = linkUseAbsolute.take(index + 1),
                                    formatter = formatter,
                                )
                            }
                            linkUseAbsolute[index] = true
                        }) {
                            Text(
                                stringResource(R.string.scheduled_ui_mode_absolute),
                                color = if (linkUseAbsolute[index]) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (linkUseAbsolute[index]) {
                            OutlinedTextField(
                                value = linkTimes[index],
                                onValueChange = { linkTimes[index] = it.take(5) },
                                label = { Text(stringResource(R.string.scheduled_ui_time)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            OutlinedTextField(
                                value = linkIntervals[index],
                                onValueChange = { linkIntervals[index] = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.scheduled_ui_interval_min)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = linkDoses[index],
                            onValueChange = { linkDoses[index] = it },
                            label = { Text(stringResource(R.string.scheduled_ui_dose_u)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- 增删一笔（总数上限含首笔） ----
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = linkCount + 1 < config.maxChainCount,
                        onClick = { linkCount++ },
                    ) { Text(stringResource(R.string.scheduled_ui_add_point)) }
                    TextButton(
                        enabled = linkCount > 0,
                        onClick = {
                            linkCount--
                            linkIntervals[linkCount] = ""
                            linkTimes[linkCount] = ""
                            linkDoses[linkCount] = ""
                            linkUseAbsolute[linkCount] = false
                        },
                    ) { Text(stringResource(R.string.scheduled_ui_remove_point)) }
                }
                Divider()
            }

            // ---- 计划预览与校验 ----
            item {
                val plan = buildPlan(
                    firstTime = firstTime,
                    firstDose = firstDose,
                    linkCount = linkCount,
                    intervals = linkIntervals,
                    times = linkTimes,
                    useAbsolute = linkUseAbsolute,
                    doses = linkDoses,
                )
                val issues = plan?.let {
                    it.validate(config, limits, System.currentTimeMillis())
                } ?: emptyList()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.scheduled_ui_timeline),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (plan == null) {
                        Text(
                            text = stringResource(R.string.scheduled_ui_incomplete),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        plan.timeline().forEach { point ->
                            val time = Instant.ofEpochMilli(point.atMillis)
                                .atZone(ZoneId.systemDefault())
                                .format(formatter)
                            Text(
                                text = stringResource(
                                    R.string.scheduled_ui_timeline_line,
                                    time,
                                    point.doseMilliunits / 1000.0,
                                ),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    issues.forEach { issue -> IssueText(issue, limits) }
                }
            }

            item {
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = {
                        val plan = buildPlan(
                            firstTime = firstTime,
                            firstDose = firstDose,
                            linkCount = linkCount,
                            intervals = linkIntervals,
                            times = linkTimes,
                            useAbsolute = linkUseAbsolute,
                            doses = linkDoses,
                        )
                        if (plan == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.scheduled_ui_incomplete),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@TextButton
                        }
                        val issues = ScheduledBolusRuntime.arm(context, plan)
                        if (issues.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.scheduled_ui_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@TextButton
                        }
                        persisted = ScheduledBolusStore.load(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.scheduled_ui_saved),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(stringResource(R.string.scheduled_ui_save)) }

                    TextButton(onClick = {
                        ScheduledBolusRuntime.cancel(context)
                        persisted = null
                    }) { Text(stringResource(R.string.scheduled_ui_cancel)) }
                }
                Divider()
            }

            // ---- 当前预约状态（只读） ----
            item {
                val snapshot = persisted?.snapshot
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (snapshot == null) {
                        Text(stringResource(R.string.scheduled_ui_none), fontSize = 13.sp)
                    } else {
                        val stateText = stringResource(
                            R.string.scheduled_ui_state,
                            snapshot.state.name,
                            snapshot.index,
                            // runs 只含「后续笔」，展示口径要含首笔。
                            snapshot.runs.size + 1,
                        )
                        Text(stateText, fontSize = 13.sp)
                        if (snapshot.state != ScheduledBolusChain.State.DONE &&
                            snapshot.state != ScheduledBolusChain.State.CHAIN_ABORTED &&
                            snapshot.dueAtMillis > 0
                        ) {
                            Text(
                                stringResource(
                                    R.string.scheduled_ui_next_due,
                                    Instant.ofEpochMilli(snapshot.dueAtMillis)
                                        .atZone(ZoneId.systemDefault())
                                        .format(formatter),
                                ),
                                fontSize = 13.sp,
                            )
                        }
                        snapshot.abortReason?.let {
                            Text(
                                stringResource(R.string.scheduled_ui_aborted, it.name),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = navigateBack,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.app_back))
                }
            }
        },
    )
}

@Composable
private fun IssueText(issue: PlanIssue, limits: BolusLimits) {
    val fmt = java.util.Locale.getDefault()
    val text = when (issue) {
        is PlanIssue.TooManyLinks ->
            stringResource(R.string.scheduled_issue_too_many, issue.max)
        is PlanIssue.DoseOutOfRange ->
            stringResource(R.string.scheduled_issue_dose_range, issue.step + 1)
        is PlanIssue.DoseOffIncrement -> stringResource(
            R.string.scheduled_issue_dose_step,
            issue.step + 1,
            String.format(fmt, "%.2f", issue.incrementMilliunits / 1000.0),
        )
        is PlanIssue.IntervalTooShort ->
            stringResource(R.string.scheduled_issue_interval_short, issue.step + 1, issue.minMs / 60_000)
        is PlanIssue.IntervalTooLong ->
            stringResource(R.string.scheduled_issue_interval_long, issue.step + 1, issue.maxMs / 60_000)
        PlanIssue.FirstTimeInPast -> stringResource(R.string.scheduled_issue_first_past)
        else -> ""
    }
    Text(text = text, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
}

/** 首笔默认放到下一个整点，减少“刚填好就已经过去”的挫败感。 */
private fun nextWholeHour(): String {
    val now = LocalDateTime.now().plusHours(1)
    return DateTimeFormatter.ofPattern("HH:mm").format(now.withMinute(0))
}

/** 把「HH:mm」解析成绝对毫秒；首笔若已过去则顺延到明天（在提示里说明）。 */
private fun parseClock(value: String, reference: LocalDate, rollToNextDayIfPast: Boolean): Long? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    var date = reference
    var at = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    if (rollToNextDayIfPast && at <= System.currentTimeMillis()) {
        date = date.plusDays(1)
        at = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return at
}

private fun parseDose(value: String): Long? =
    value.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }

private fun previewPointTime(
    firstTime: String,
    intervals: List<String>,
    times: List<String>,
    useAbsolute: List<Boolean>,
    formatter: DateTimeFormatter,
): String {
    // 仅用于切换模式时的预填：按已填内容推到当前这点。
    var previous = parseClock(firstTime, LocalDate.now(), true) ?: return ""
    intervals.forEachIndexed { index, interval ->
        val absolute = if (useAbsolute.getOrElse(index) { false }) {
            parseClock(times.getOrElse(index) { "" }, LocalDate.now(), false)
        } else {
            null
        }
        previous = absolute ?: (previous + (interval.toLongOrNull() ?: 0L) * 60_000L)
    }
    return Instant.ofEpochMilli(previous).atZone(ZoneId.systemDefault()).format(formatter)
}

/**
 * 把界面字段拼成计划；任何一栏无法解析都返回 null（由 UI 提示「未填完」）。
 * 首笔固定为「今天/明天最近的这个钟点」，后续笔的绝对时刻以首笔日期为基准滚动。
 */
private fun buildPlan(
    firstTime: String,
    firstDose: String,
    linkCount: Int,
    intervals: List<String>,
    times: List<String>,
    useAbsolute: List<Boolean>,
    doses: List<String>,
): ScheduledPlan? {
    val firstAt = parseClock(firstTime, LocalDate.now(), rollToNextDayIfPast = true) ?: return null
    val first = parseDose(firstDose) ?: return null
    val links = mutableListOf<ScheduledBolusChain.Link>()
    var previous = firstAt
    for (i in 0 until linkCount) {
        val dose = parseDose(doses[i]) ?: return null
        val absolute = if (useAbsolute[i]) {
            var at = parseClock(times[i], LocalDate.now(), false) ?: return null
            if (at <= previous) at += 24 * 60 * 60 * 1000L
            at
        } else {
            val minutes = intervals[i].toLongOrNull() ?: return null
            if (minutes <= 0) return null
            previous + minutes * 60_000L
        }
        links += ScheduledBolusChain.Link(
            doseMilliunits = dose,
            delayAfterPreviousMs = (absolute - previous).coerceAtLeast(0L),
            absoluteAtMillis = absolute.takeIf { useAbsolute[i] },
        )
        previous = absolute
    }
    return ScheduledPlan(firstAtMillis = firstAt, firstDoseMilliunits = first, links = links)
}
