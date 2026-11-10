package com.pumppan.controlx2.query

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 步：真实 assets 能被解析且不被拒绝。
 *
 * 单测工作目录是 `mobile/`，所以直接读源集里的 assets 文件；这比对着一份内联 JSON
 * 断言更接近线上路径——JSON 改坏了这里会红。
 */
class QueryPolicyLoaderTest {

    private fun loadAsset(): QueryPolicy {
        val file = File("src/main/assets/pump-query-policy.json")
        assertTrue("assets policy missing: ${file.absolutePath}", file.exists())
        return QueryPolicyLoader.parse(file.readText(), loadedAtMillis = 0L)
    }

    @Test
    fun assetIsSchemaV2AndAccepted() {
        val policy = loadAsset()

        assertEquals(2, policy.schemaVersion)
        assertEquals(PolicySource.ASSET, policy.source)
        assertTrue("rejected: ${policy.diagnostics.rejected}", policy.diagnostics.rejected.isEmpty())
        assertTrue("typeMismatch: ${policy.diagnostics.typeMismatch}", policy.diagnostics.typeMismatch.isEmpty())
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

        // 高频三域 60 秒；血糖与 CIQ 允许低频，但保底仍是「最长空窗」
        assertEquals(60_000L, policy.topics.getValue("PUMP_VITALS").effectiveIntervalMs())
        assertEquals(60_000L, policy.topics.getValue("BASAL").effectiveIntervalMs())
        assertEquals(300_000L, policy.topics.getValue("CGM").effectiveIntervalMs())
        assertEquals(300_000L, policy.topics.getValue("CONTROL_IQ").effectiveIntervalMs())
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
    fun overlaysAreParsed() {
        val policy = loadAsset()

        assertTrue(policy.overlays.byState.containsKey("delivering"))
        val delivering = policy.overlays.byState.getValue("delivering")
        assertEquals(1_000L, delivering.getValue("BOLUS_OUTCOME").intervalMs)
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
