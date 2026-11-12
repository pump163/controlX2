package com.jwoglom.controlx2.shared.util

import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoField
import java.time.temporal.TemporalField
import java.util.*
import kotlin.math.abs
import kotlin.time.DurationUnit

/**
 * 泵时间戳是「墙上时间」（按 UTC 装的伪 instant），这里按设备时区还原成真实瞬间。
 *
 * 偏移取**该时间点**的时区偏移，而不是「现在」的偏移：历史记录跨过夏令时切换时，
 * 用当前偏移会把图表上的点整整错开一小时（2026-11 由 VicoCgmChartTest 暴露，
 * 详见 `docs/bug/bug-基线单测DST失败.md`）。
 */
fun pumpTimeToLocalTz(time: Instant): Instant {
    val offsetSeconds = TimeZone.getDefault().getOffset(time.toEpochMilli()) / 1000
    return time.minusSeconds(offsetSeconds.toLong())
}

fun shortTimeAgo(
    time: Instant,
    prefix: String = "in",
    suffix: String = "ago",
    nowThresholdSeconds: Int = 60,
    nowText: String = "now",
): String {
    val now = Instant.now()
    val diff = Duration.between(time, now)
    var ret = ""
    if (diff.toDays() != 0L) {
        ret += "${abs(diff.toDays())}d"
    }
    if (diff.toHours() % 24 != 0L) {
        ret += "${abs(diff.toHours()%24)}h"
    }
    if (diff.toMinutes() % 60 != 0L && diff.toDays() == 0L) {
        ret += "${String.format("%d", abs(diff.toMinutes())%60)}m"
    }
    if (diff.seconds < nowThresholdSeconds && diff.seconds > (-1 * nowThresholdSeconds)) {
        return nowText
    } else if (diff.toMinutes() == 0L) {
        ret += "${String.format("%d", abs(diff.seconds))}s"
    }

    return if (time.isBefore(now)) {
        "$ret $suffix"
    } else {
        "$prefix $ret"
    }
}

fun shortTime(
    time: Instant,
    minuteSuffix: String = "m",
    amSuffix: String = "am",
    pmSuffix: String = "pm",
): String {
//    val zoned = time.atZone(UTC) // UTC, not the system timezone, for some reason. Perhaps TZ information is already encoded in the Tandem timestamps?
//
//    val offsetSeconds = TimeZone.getDefault().getOffset(Date().time) / 1000
//    val now = Instant.now().plusSeconds(offsetSeconds.toLong())
    val zoned = time.atZone(ZoneId.systemDefault())
    val now = Instant.now()
    val diff = Duration.between(time, now)

    val date = when (diff.toDays() >= 1 || diff.toDays() <= -1) {
        true -> "${zoned.month.value}/${zoned.dayOfMonth} "
        else -> ""
    }

    val m = when (date.length) {
        0 -> minuteSuffix
        else -> ""
    }

    val hr = when (zoned.hour) {
        0, 12 -> 12
        else -> zoned.hour % 12
    }
    val ampm = when (zoned.hour < 12) {
        true -> amSuffix.replace("m", m)
        false -> pmSuffix.replace("m", m)
    }
    return "${date}${hr}:${String.format("%02d", zoned.minute)}${ampm}"
}

/**
 * 大剂量相关界面的**唯一**时刻格式化入口（24 小时制）。
 *
 * 与 [shortTime] 的日期前缀规则保持一致：跨天（≥1 天）时前缀 `M/d `；
 * 但时刻统一为 `HH:mm`，不再拼 `am` / `pm` —— 中文「%H:%M 下午」是把英文 12 小时制
 * 后缀直接搬到中文语序里，读着别扭；`14:05` 也更精确。
 * 中英界面都用 24 小时制：同一功能内统一优先，且英文向导原本就是 `HH:mm`，
 * 不为了英文习惯再把大剂量时间改回 12 小时制。
 */
fun shortTime24(time: Instant): String {
    val zoned = time.atZone(ZoneId.systemDefault())
    val diff = Duration.between(time, Instant.now())
    val date = when (diff.toDays() >= 1 || diff.toDays() <= -1) {
        true -> "${zoned.month.value}/${zoned.dayOfMonth} "
        else -> ""
    }
    return "${date}${String.format("%02d", zoned.hour)}:${String.format("%02d", zoned.minute)}"
}

fun twoDecimalPlaces(decimal: Double): String {
    return String.format("%.2f", decimal)
}

fun oneDecimalPlace(decimal: Double): String {
    return String.format("%.1f", decimal)
}

fun twoDecimalPlaces1000Unit(insulin1000Units: Long): String {
    return twoDecimalPlaces(InsulinUnit.from1000To1(insulin1000Units))
}

fun oneDecimalPlace1000Unit(insulin1000Units: Long): String {
    return oneDecimalPlace(InsulinUnit.from1000To1(insulin1000Units))
}

fun snakeCaseToSpace(str: String): String {
    return str.replace("_", " ")
}

fun firstLetterCapitalized(str: String): String {
    return "${str.substring(0, 1).uppercase()}${str.substring(1)}"
}