package com.pumppan.controlx2.scheduled

import android.content.Context
import com.pumppan.controlx2.PumppanPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 预约槽（模板）：**只存「相对时隔 + 剂量」，不存绝对时间**。
 *
 * 这是刻意留白：绝对时间一旦落进模板，第二天再套用就是过期值。套用时只把间隔与剂量
 * 灌进当前表单，绝对时刻永远由执行前的那一刻现算。
 *
 * 槽位数由 [ScheduledBolusConfig.maxPresetCount] 决定（默认 6），超出的条目读写时直接丢弃。
 */
data class ScheduledPreset(
    val intervalAfterPreviousMs: Long,
    val doseMilliunits: Long,
)

object ScheduledPresetsStore {

    fun load(context: Context, limit: Int = ScheduledBolusConfig.load(context).maxPresetCount): List<ScheduledPreset> {
        val raw = PumppanPrefs.scheduledPresetsJson(context) ?: return emptyList()
        return runCatching { parse(raw).take(limit.coerceAtLeast(0)) }.getOrElse {
            clear(context)
            emptyList()
        }
    }

    fun save(context: Context, presets: List<ScheduledPreset>, limit: Int = ScheduledBolusConfig.load(context).maxPresetCount) {
        PumppanPrefs.setScheduledPresetsJson(context, encode(presets.take(limit.coerceAtLeast(0))).toString())
    }

    /** 覆盖指定槽位；越界或超上限一律忽略，不抛异常。 */
    fun upsert(context: Context, index: Int, preset: ScheduledPreset) {
        val limit = ScheduledBolusConfig.load(context).maxPresetCount
        if (index !in 0 until limit) return
        val current = load(context, limit).toMutableList()
        while (current.size <= index) current += ScheduledPreset(0L, 0L)
        current[index] = preset
        save(context, current, limit)
    }

    /** 追加到第一个空槽；满了返回 -1。 */
    fun append(context: Context, preset: ScheduledPreset): Int {
        val limit = ScheduledBolusConfig.load(context).maxPresetCount
        val current = load(context, limit).toMutableList()
        if (current.size >= limit) return -1
        current += preset
        save(context, current, limit)
        return current.lastIndex
    }

    fun remove(context: Context, index: Int) {
        val limit = ScheduledBolusConfig.load(context).maxPresetCount
        val current = load(context, limit).toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        save(context, current, limit)
    }

    fun clear(context: Context) = PumppanPrefs.setScheduledPresetsJson(context, null)

    internal fun encode(presets: List<ScheduledPreset>): JSONArray = JSONArray().apply {
        presets.forEach { preset ->
            put(JSONObject()
                .put("delay", preset.intervalAfterPreviousMs)
                .put("dose", preset.doseMilliunits))
        }
    }

    internal fun parse(raw: String): List<ScheduledPreset> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ScheduledPreset(
                intervalAfterPreviousMs = obj.optLong("delay", 0L),
                doseMilliunits = obj.optLong("dose", 0L),
            )
        }.filter { it.doseMilliunits > 0 && it.intervalAfterPreviousMs > 0 }
    }
}
