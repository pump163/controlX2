package com.pumppan.controlx2.scheduled

import android.content.Context
import com.pumppan.controlx2.PumppanPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 链状态持久化（设计 §8.0）：**单条 JSON**，只保存链本身，不留任何可读的用药流水。
 *
 * 存的是 [ScheduledPlan] + [ScheduledBolusChain.Snapshot]；进程被杀后可恢复「还剩几笔、
 * 下一笔定在何时」，从而在下次打开 App 时补一条「已错过」提醒（R10），
 * 而不是把预约当成没发生过。
 */
object ScheduledBolusStore {

    /**
     * 读回计划与快照；两者必须同时存在且链 ID 一致，否则视为无记录。
     * 解析失败一律返回 null 并清掉坏数据，不让损坏的 JSON 卡住入口。
     */
    fun load(context: Context): Persisted? {
        val raw = PumppanPrefs.scheduledChainJson(context) ?: return null
        return runCatching { parse(raw) }.getOrElse {
            clear(context)
            null
        }
    }

    fun save(context: Context, plan: ScheduledPlan, snapshot: ScheduledBolusChain.Snapshot) {
        PumppanPrefs.setScheduledChainJson(context, encode(plan, snapshot).toString())
    }

    fun clear(context: Context) = PumppanPrefs.setScheduledChainJson(context, null)

    data class Persisted(
        val plan: ScheduledPlan,
        val snapshot: ScheduledBolusChain.Snapshot,
    )

    internal fun encode(plan: ScheduledPlan, snapshot: ScheduledBolusChain.Snapshot): JSONObject {
        val planJson = JSONObject()
            .put("firstAt", plan.firstAtMillis)
            .put("firstDose", plan.firstDoseMilliunits)
            .put("links", JSONArray().apply {
                plan.links.forEach { link ->
                    put(JSONObject()
                        .put("dose", link.doseMilliunits)
                        .put("delay", link.delayAfterPreviousMs)
                        .apply { link.absoluteAtMillis?.let { put("absoluteAt", it) } })
                }
            })

        val runsJson = JSONArray().apply {
            snapshot.runs.forEach { run ->
                put(JSONObject()
                    .put("dose", run.link.doseMilliunits)
                    .put("delay", run.link.delayAfterPreviousMs)
                    .apply { run.link.absoluteAtMillis?.let { put("absoluteAt", it) } }
                    .put("startedAt", run.startedAtMillis ?: -1L)
                    .put("bolusId", run.bolusId ?: 0)
                    .put("delivered", run.deliveredMilliunits ?: -1L)
                    .put("terminal", run.terminalStatusId ?: -1))
            }
        }

        val chainJson = JSONObject()
            .put("chainId", snapshot.chainId)
            .put("state", snapshot.state.name)
            .put("runs", runsJson)
            .put("index", snapshot.index)
            .put("anchorAt", snapshot.anchorAtMillis)
            .put("dueAt", snapshot.dueAtMillis)
            .apply { snapshot.confirmDeadlineAtMillis?.let { put("confirmDeadlineAt", it) } }
            .put("pending", JSONArray().apply { snapshot.pendingBolusIds.forEach { put(it) } })
            .apply { snapshot.lastNotifiedAtMillis?.let { put("lastNotifiedAt", it) } }
            .apply { snapshot.abortReason?.let { put("abortReason", it.name) } }
            .put("doseEdited", snapshot.doseEdited)

        return JSONObject().put("plan", planJson).put("chain", chainJson)
    }

    internal fun parse(raw: String): Persisted {
        val root = JSONObject(raw)
        val planJson = root.getJSONObject("plan")
        val chainJson = root.getJSONObject("chain")

        fun readLink(obj: JSONObject) = ScheduledBolusChain.Link(
            doseMilliunits = obj.getLong("dose"),
            delayAfterPreviousMs = obj.getLong("delay"),
            absoluteAtMillis = if (obj.has("absoluteAt")) obj.getLong("absoluteAt") else null,
        )

        val linksJson = planJson.getJSONArray("links")
        val links = (0 until linksJson.length()).map { readLink(linksJson.getJSONObject(it)) }
        val plan = ScheduledPlan(
            firstAtMillis = planJson.getLong("firstAt"),
            firstDoseMilliunits = planJson.getLong("firstDose"),
            links = links,
        )

        val runsJson = chainJson.getJSONArray("runs")
        val runs = (0 until runsJson.length()).map { i ->
            val obj = runsJson.getJSONObject(i)
            val link = readLink(obj)
            ScheduledBolusChain.LinkRun(
                link = link,
                startedAtMillis = obj.optLong("startedAt", -1L).takeIf { it > 0 },
                bolusId = obj.optInt("bolusId", 0).takeIf { it > 0 },
                deliveredMilliunits = obj.optLong("delivered", -1L).takeIf { it > 0 },
                terminalStatusId = obj.optInt("terminal", -1).takeIf { it >= 0 },
            )
        }

        val pending = mutableSetOf<Int>()
        chainJson.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) pending += arr.getInt(i)
        }

        val snapshot = ScheduledBolusChain.Snapshot(
            chainId = chainJson.getString("chainId"),
            state = ScheduledBolusChain.State.valueOf(chainJson.getString("state")),
            runs = runs,
            index = chainJson.getInt("index"),
            anchorAtMillis = chainJson.getLong("anchorAt"),
            dueAtMillis = chainJson.getLong("dueAt"),
            confirmDeadlineAtMillis = chainJson.optLong("confirmDeadlineAt", -1L).takeIf { it > 0 },
            pendingBolusIds = pending,
            lastNotifiedAtMillis = chainJson.optLong("lastNotifiedAt", -1L).takeIf { it > 0 },
            abortReason = chainJson.optString("abortReason").takeIf { it.isNotEmpty() }
                ?.let { runCatching { ScheduledBolusChain.AbortReason.valueOf(it) }.getOrNull() },
            doseEdited = chainJson.optBoolean("doseEdited", false),
        )
        return Persisted(plan, snapshot)
    }
}
