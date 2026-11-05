package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import java.time.Instant

/**
 * 输入页顶部「上次大剂量」提醒的三种展示结果。
 * 数据来源与首页「上次大剂量」窗口相同：[com.jwoglom.controlx2.presentation.DataStore.lastBolusStatusResponse]
 * （48 号 `LastBolusStatus*Response`，含 deliveredVolume 与 timestamp）。
 */
sealed interface LastBolusSummary {
    /** 没有可用的上次大剂量记录（DataStore 为空，或剂量与时间戳都为 0）。 */
    data object None : LastBolusSummary

    /** 有剂量但时间戳缺失/为 0：只显示剂量 + 「时间未知」，不显示 1970。 */
    data class UnknownTime(val doseMilliunits: Long) : LastBolusSummary

    /** 剂量与时间都可用；时间仍是泵时间，展示前由调用方转本地时区。 */
    data class Record(val doseMilliunits: Long, val timestamp: Instant) : LastBolusSummary
}

/** 把 48 号响应的 (deliveredVolume, timestamp) 归一化成三种展示状态。 */
fun summarizeLastBolus(deliveredVolume: Long?, timestampSeconds: Long?): LastBolusSummary {
    val dose = (deliveredVolume ?: 0L).coerceAtLeast(0L)
    val timestamp = timestampSeconds ?: 0L
    if (timestamp <= 0L) {
        return if (dose > 0L) LastBolusSummary.UnknownTime(dose) else LastBolusSummary.None
    }
    return LastBolusSummary.Record(dose, Dates.fromJan12008EpochSecondsToDate(timestamp))
}
