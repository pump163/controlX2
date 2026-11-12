package com.pumppan.controlx2.query

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 步：真实 assets 能被解析且不被拒绝。
 *
 * 单测工作目录是 `mobile/`，而策略文件放在 `shared/src/main/assets/`（library assets 会合并进
 * phone 与 wear 两个 APK，将来 wear 接入不用搬文件），所以这里走 `../shared/...`。
 * 这比对着一份内联 JSON 断言更接近线上路径——JSON 改坏了这里会红。
 *
 * 2026-11-12 起 asset 是 `base + 平台/型号覆盖层`：测试按线上顺序先 compose 再 parse。
 */
class QueryPolicyLoaderTest {

    private fun asset(path: String): File {
        val file = File("../shared/src/main/assets/$path")
        assertTrue("assets policy missing: ${file.absolutePath}", file.exists())
        return file
    }

    private fun loadAsset(model: String = "slimx2"): QueryPolicy {
        val overlay = asset("query-policy/mobile/$model.json")
        return QueryPolicyLoader.parse(
            QueryPolicyLoader.compose(
                asset("query-policy/base.json").readText(),
                listOf(overlay.readText()),
            ),
            loadedAtMillis = 0L,
        )
    }

    @Test
    fun assetIsSchemaV2AndAccepted() {
        val policy = loadAsset()

        assertEquals(2, policy.schemaVersion)
        assertEquals(PolicySource.ASSET, policy.source)
        assertTrue("rejected: ${policy.diagnostics.rejected}", policy.diagnostics.rejected.isEmpty())
        assertTrue("typeMismatch: ${policy.diagnostics.typeMismatch}", policy.diagnostics.typeMismatch.isEmpty())
        // 覆盖层带来的 platform / model 是元信息，不能被记成 unknown。
        assertTrue("unknownKeys: ${policy.diagnostics.unknownKeys}", policy.diagnostics.unknownKeys.isEmpty())
    }

    @Test
    fun bothMobileModelLayersAreAccepted() {
        listOf("slimx2", "mobi").forEach { model ->
            val policy = loadAsset(model)
            assertTrue("$model rejected: ${policy.diagnostics.rejected}", policy.diagnostics.rejected.isEmpty())
            assertEquals(emptyList<String>(), policy.diagnostics.unknownKeys)
        }
    }

    @Test
    fun overlayLayoutHasBaseAndFourSlots() {
        // base + (mobile|wear) × (slimx2|mobi)；wear 两份本轮只是占位，不参与加载。
        listOf(
            "query-policy/base.json",
            "query-policy/mobile/slimx2.json",
            "query-policy/mobile/mobi.json",
            "query-policy/wear/slimx2.json",
            "query-policy/wear/mobi.json",
        ).forEach { asset(it) }
    }

    @Test
    fun mobileProcessOnlyReadsMobileBranch() {
        assertEquals("query-policy/mobile/slimx2.json", QueryPolicyLoader.overlayAssetName("slimx2"))
        assertEquals("query-policy/mobile/mobi.json", QueryPolicyLoader.overlayAssetName("mobi"))
    }

    @Test
    fun overlayMergesTopicFieldWise() {
        val base = """
            {"schemaVersion":2,
             "default":{"topics":{
               "IOB":{"intervalMs":60000,"floorIntervalMs":60000,"floorPriority":"P0_FLOOR","minIntervalMs":15000,"priority":"P0_FLOOR","onlyWhenVisible":false},
               "HISTORY":{"intervalMs":300000,"minIntervalMs":60000,"priority":"P2_DECORATIVE","onlyWhenVisible":false}},
              "switches":{"periodicEnabled":true}}}
        """.trimIndent()
        val overlay = """
            {"platform":"mobile","model":"mobi","note":"测试覆盖层",
             "default":{"topics":{"IOB":{"intervalMs":180000,"floorIntervalMs":180000}}}}
        """.trimIndent()

        val policy = QueryPolicyLoader.parse(QueryPolicyLoader.compose(base, listOf(overlay)), 0L)

        // 覆盖层只写了两个字段：其余字段必须沿用 base，不能整域替换。
        val iob = policy.topics.getValue("IOB")
        assertEquals(180_000L, iob.intervalMs)
        assertEquals(180_000L, iob.floorIntervalMs)
        assertEquals("P0_FLOOR", iob.priority)
        assertEquals(15_000L, iob.minIntervalMs)
        assertEquals(false, iob.onlyWhenVisible)
        // 没写的域保持 base。
        assertEquals(300_000L, policy.topics.getValue("HISTORY").intervalMs)
        assertEquals(emptyList<String>(), policy.diagnostics.unknownKeys)
        assertEquals(emptyList<String>(), policy.diagnostics.rejected)
    }

    @Test
    fun overlayNoteIsIgnoredDuringMerge() {
        // note 是给人看的：覆盖层写自己的说明，不覆盖 base 的 note。
        val base = """
            {"schemaVersion":2,"note":"BASE",
             "default":{"topics":{"IOB":{"intervalMs":60000,"minIntervalMs":15000,"priority":"P0_FLOOR","onlyWhenVisible":false}}}}
        """.trimIndent()
        val overlay = """{"platform":"mobile","model":"slimx2","note":"OVERLAY","default":{}}"""

        val merged = QueryPolicyLoader.compose(base, listOf(overlay))
        assertTrue(merged.contains("BASE"))
        assertTrue(!merged.contains("OVERLAY"))
    }

    @Test
    fun dashboardResidentFieldsCarryFloor() {
        val policy = loadAsset()

        // 首页最小显示状态里常驻的字段：电池/储药、IOB、血糖、基础率、上次大剂量、CIQ。
        // 这些「屏蔽不屏蔽都在」的字段必须有保底，否则页面一降级就看不到。
        val resident = listOf("PUMP_VITALS", "IOB", "BASAL", "BOLUS_LAST", "CGM", "CONTROL_IQ")

        resident.forEach { domain ->
            val topic = policy.topics.getValue(domain)
            assertTrue("$domain missing floor", topic.hasFloor())
            assertEquals("$domain floor priority", "P0_FLOOR", topic.floorPriority)
            assertTrue("$domain must not depend on visibility", !topic.onlyWhenVisible)
        }

        // 常显且每分钟会变的：电池/储药、IOB → 60 秒；
        // 「一条事件才变一次」的基础率 / 上次大剂量 → 120 秒（首页第二批的拍）；
        // 血糖与 CIQ 允许低频，但保底仍是「最长空窗」
        assertEquals(60_000L, policy.topics.getValue("PUMP_VITALS").effectiveIntervalMs())
        assertEquals(60_000L, policy.topics.getValue("IOB").effectiveIntervalMs())
        assertEquals(120_000L, policy.topics.getValue("BASAL").effectiveIntervalMs())
        assertEquals(120_000L, policy.topics.getValue("BOLUS_LAST").effectiveIntervalMs())
        assertEquals(300_000L, policy.topics.getValue("CGM").effectiveIntervalMs())
        assertEquals(300_000L, policy.topics.getValue("CONTROL_IQ").effectiveIntervalMs())
        assertEquals(300_000L, policy.topics.getValue("HISTORY").effectiveIntervalMs())
    }

    @Test
    fun protectedPrioritiesAreOutOfDegradeOrder() {
        val policy = loadAsset()

        assertEquals(listOf("P0_FLOOR", "P0_CONFIRM"), policy.budget.protectPriority)
        assertEquals(listOf("P2_DECORATIVE", "P1_STATE"), policy.budget.degradeOrder)
        val overlap = policy.budget.degradeOrder.filter { it in policy.budget.protectPriority }
        assertTrue("protected priorities must not be degradable: $overlap", overlap.isEmpty())
    }

    @Test
    fun eventsReferenceExistingTopics() {
        val policy = loadAsset()

        for ((name, event) in policy.events) {
            val unknown = event.topics.filter { it !in policy.topics.keys }
            assertTrue("event $name references unknown topics: $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun reconnectProbeTopicExists() {
        val policy = loadAsset()

        val missing = policy.reconnect.probeTopics.filter { it !in policy.topics.keys }
        assertTrue("probe topics unknown: $missing", missing.isEmpty())
    }
}
