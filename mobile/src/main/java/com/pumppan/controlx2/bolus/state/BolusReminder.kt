package com.pumppan.controlx2.bolus.state

import java.time.Duration
import java.time.Instant

enum class BolusReminderStrength {
    /** 2 小时内：警示色 + 加粗。 */
    STRONG,

    /** 2–24 小时：普通弱化。 */
    WEAK,
}

data class BolusReminder(
    val deliveredMilliunits: Long,
    /** 已转为本地时区的泵侧时间。 */
    val pumpTimestamp: Instant,
    val strength: BolusReminderStrength,
)

/** 2 小时内强提醒；超过 24 小时不再显示。 */
const val BOLUS_REMINDER_STRONG_HOURS = 2

/** 超过这个时长视为「久远」，整行不显示。 */
const val BOLUS_REMINDER_HIDE_HOURS = 24

/**
 * 由「上次大剂量」的已交付量与本地时间生成提醒的展示强度。
 *
 * 数据同源首页「上次大剂量」窗口：[com.jwoglom.controlx2.presentation.DataStore.lastBolusStatusResponse]
 * （48/49 号 `LastBolusStatus*Response`）的 `deliveredVolume` + `timestamp`。
 * 该值包含被取消或部分输注的量——那也是实际发送到泵并执行过的命令。
 *
 * 剂量缺失、或已超过 [BOLUS_REMINDER_HIDE_HOURS] 小时 → 返回 null（调用方不渲染整行）。
 *
 * @param pumpTimestampLocal 已由 [com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz] 转成本地时区的时间。
 */
fun buildBolusReminder(
    deliveredMilliunits: Long,
    pumpTimestampLocal: Instant,
    now: Instant,
): BolusReminder? {
    if (deliveredMilliunits <= 0L) return null
    val elapsed = Duration.between(pumpTimestampLocal, now).seconds.coerceAtLeast(0)
    if (elapsed > BOLUS_REMINDER_HIDE_HOURS * 3600L) return null
    val strength = if (elapsed <= BOLUS_REMINDER_STRONG_HOURS * 3600L) {
        BolusReminderStrength.STRONG
    } else {
        BolusReminderStrength.WEAK
    }
    return BolusReminder(deliveredMilliunits, pumpTimestampLocal, strength)
}
