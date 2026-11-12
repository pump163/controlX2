package com.pumppan.controlx2.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 单条链 JSON 的往返：只验证「能原样读回」，不验证业务规则（那部分在
 * `ScheduledBolusChainTest` 与 `ScheduledPlanTest`）。
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledBolusStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `round trip keeps plan and pending snapshot`() {
        val plan = ScheduledPlan(
            firstAtMillis = 1_000L,
            firstDoseMilliunits = 8_000L,
            links = listOf(
                ScheduledBolusChain.Link(4_000L, 20 * 60 * 1000L),
                ScheduledBolusChain.Link(2_000L, 60 * 60 * 1000L, absoluteAtMillis = 5_000L),
            ),
        )
        var snapshot = ScheduledBolusChain.start("c1", plan.resolvedLinks(), 1_000L, config())
        snapshot = snapshot.copy(pendingBolusIds = setOf(8801, 8802))

        ScheduledBolusStore.save(context, plan, snapshot)
        val loaded = ScheduledBolusStore.load(context)!!

        assertEquals(plan.firstAtMillis, loaded.plan.firstAtMillis)
        assertEquals(plan.firstDoseMilliunits, loaded.plan.firstDoseMilliunits)
        assertEquals(2, loaded.plan.links.size)
        assertEquals(5_000L, loaded.plan.links[1].absoluteAtMillis)
        assertEquals(setOf(8801, 8802), loaded.snapshot.pendingBolusIds)
        assertEquals(ScheduledBolusChain.State.COUNTING, loaded.snapshot.state)
        assertEquals(snapshot.dueAtMillis, loaded.snapshot.dueAtMillis)
    }

    @Test
    fun `broken json clears the record instead of throwing`() {
        context.getSharedPreferences("WearX2", Context.MODE_PRIVATE)
            .edit().putString("pumppan-scheduled-chain-json", "{ not json").commit()

        assertNull(ScheduledBolusStore.load(context))
        assertNull(ScheduledBolusStore.load(context))
    }

    private fun config() = ScheduledBolusConfig(maxChainCount = 3)
}
