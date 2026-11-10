package com.pumppan.controlx2.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 3 步：schema v2 的分层叠加与保底周期语义。 */
class PolicyResolverTest {

    private fun topic(
        interval: Long = 60_000,
        floor: Long? = 60_000,
        priority: String = "P0_FLOOR",
    ) = TopicPolicy(
        intervalMs = interval,
        minIntervalMs = 15_000,
        priority = priority,
        onlyWhenVisible = false,
        maxWindowMs = null,
        floorIntervalMs = floor,
        floorPriority = if (floor != null) "P0_FLOOR" else null,
    )

    private fun policyOf(
        topics: Map<String, TopicPolicy>,
        overlays: PolicyOverlays = PolicyOverlays.empty(),
    ) = QueryPolicy(
        schemaVersion = 2,
        source = PolicySource.ASSET,
        hash = "test",
        loadedAtMillis = 0L,
        topics = topics,
        freshness = FreshnessPolicy(30_000, 180_000, 900_000),
        states = StatesPolicy(8_000, 30_000, 1_000, true, true, 10_000, 300_000),
        budget = BudgetPolicy(
            perMinute = 10,
            burst = 20,
            burstWindowMs = 60_000,
            degradeOrder = listOf("P2_DECORATIVE", "P1_STATE"),
            protectPriority = listOf("P0_FLOOR", "P0_CONFIRM"),
        ),
        events = emptyMap(),
        reconnect = ReconnectPolicy(listOf("PUMP_VITALS"), 3_000, 15_000, 5_000, 2),
        switches = SwitchesPolicy(true, true, true, true),
        diagnostics = PolicyDiagnostics(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        ),
        applyPendingOn = emptyList(),
        overlays = overlays,
    )

    private val baseTopics = mapOf(
        "PUMP_VITALS" to topic(),
        "IOB" to topic(),
        "CGM" to topic(60_000, null, "P1_STATE"),
    )

    @Test
    fun defaultOnlyWhenNoOverlayMatches() {
        val resolved = PolicyResolver.resolve(policyOf(baseTopics), model = "MOBI", apiVersion = 3, state = null)

        assertEquals(60_000L, resolved.topics.getValue("PUMP_VITALS").intervalMs)
        assertTrue(resolved.appliedOverlays.isEmpty())
    }

    @Test
    fun byModelOverridesInterval() {
        val overlays = PolicyOverlays(
            byModel = mapOf("X2" to mapOf("PUMP_VITALS" to TopicPolicyOverride(intervalMs = 30_000))),
        )

        val resolved = PolicyResolver.resolve(policyOf(baseTopics, overlays), model = "X2", apiVersion = null, state = null)

        assertEquals(30_000L, resolved.topics.getValue("PUMP_VITALS").intervalMs)
        assertEquals(listOf("byModel:X2"), resolved.appliedOverlays)
    }

    @Test
    fun apiVersionLayersApplyInAscendingOrderThenStateWins() {
        val overlays = PolicyOverlays(
            byApiVersion = listOf(
                ApiVersionOverlay(8, mapOf("IOB" to TopicPolicyOverride(intervalMs = 20_000))),
                ApiVersionOverlay(5, mapOf("IOB" to TopicPolicyOverride(intervalMs = 40_000))),
            ),
            byState = mapOf("delivering" to mapOf("IOB" to TopicPolicyOverride(intervalMs = 10_000))),
        )

        val resolved = PolicyResolver.resolve(
            policyOf(baseTopics, overlays), model = null, apiVersion = 9, state = "delivering",
        )

        assertEquals(10_000L, resolved.topics.getValue("IOB").intervalMs)
        assertEquals(
            listOf("byApiVersion:>=5", "byApiVersion:>=8", "byState:delivering"),
            resolved.appliedOverlays,
        )
    }

    @Test
    fun apiVersionBelowMinimumIsSkipped() {
        val overlays = PolicyOverlays(
            byApiVersion = listOf(ApiVersionOverlay(8, mapOf("IOB" to TopicPolicyOverride(intervalMs = 20_000)))),
        )

        val resolved = PolicyResolver.resolve(policyOf(baseTopics, overlays), model = null, apiVersion = 5, state = null)

        assertEquals(60_000L, resolved.topics.getValue("IOB").intervalMs)
        assertTrue(resolved.appliedOverlays.isEmpty())
    }

    @Test
    fun enabledFalseRemovesTopic() {
        val overlays = PolicyOverlays(
            byState = mapOf("disconnected" to mapOf("CGM" to TopicPolicyOverride(enabled = false))),
        )

        val resolved = PolicyResolver.resolve(
            policyOf(baseTopics, overlays), model = null, apiVersion = null, state = "disconnected",
        )

        assertFalse(resolved.topics.containsKey("CGM"))
        assertTrue(resolved.topics.containsKey("PUMP_VITALS"))
    }

    @Test
    fun floorTakesTheSmallerInterval() {
        val withFloor = topic(interval = 300_000, floor = 60_000)

        assertEquals(60_000L, withFloor.effectiveIntervalMs())
        assertTrue(withFloor.hasFloor())
    }

    @Test
    fun topicWithoutFloorUsesItsOwnInterval() {
        val noFloor = topic(interval = 300_000, floor = null, priority = "P1_STATE")

        assertEquals(300_000L, noFloor.effectiveIntervalMs())
        assertFalse(noFloor.hasFloor())
    }
}
