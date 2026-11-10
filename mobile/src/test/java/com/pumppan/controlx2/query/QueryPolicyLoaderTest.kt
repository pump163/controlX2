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
    fun pumpVitalsAndIobCarryFloor() {
        val policy = loadAsset()

        val vitals = policy.topics.getValue("PUMP_VITALS")
        val iob = policy.topics.getValue("IOB")

        assertEquals(60_000L, vitals.floorIntervalMs)
        assertEquals("P0_FLOOR", vitals.floorPriority)
        assertEquals(60_000L, vitals.effectiveIntervalMs())
        assertEquals(60_000L, iob.floorIntervalMs)
        assertTrue(vitals.hasFloor() && iob.hasFloor())
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
