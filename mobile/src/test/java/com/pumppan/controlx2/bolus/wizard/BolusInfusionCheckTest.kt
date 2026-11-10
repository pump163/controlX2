package com.pumppan.controlx2.bolus.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大剂量前置检查的三态语义：**「确认没有」和「没问明白」不能是同一个值**。
 */
class BolusInfusionCheckTest {

    @Test
    fun freshQueryWithoutDeliveryIsNone() {
        val check = BolusInfusionCheck.evaluate(
            freshQuery = true,
            bolusDelivering = false,
            extendedBolusActive = false,
        )
        assertEquals(BolusInfusionCheck.NONE, check)
        assertTrue(check.allowsStart())
    }

    @Test
    fun freshQueryWithEitherLegIsDelivering() {
        assertEquals(
            BolusInfusionCheck.DELIVERING,
            BolusInfusionCheck.evaluate(freshQuery = true, bolusDelivering = true, extendedBolusActive = false),
        )
        assertEquals(
            BolusInfusionCheck.DELIVERING,
            BolusInfusionCheck.evaluate(freshQuery = true, bolusDelivering = false, extendedBolusActive = true),
        )
        assertFalse(BolusInfusionCheck.DELIVERING.allowsStart())
    }

    @Test
    fun staleOrMissingResponseIsUnknownEvenWhenFlagsLookClear() {
        // 关键：两个布尔都是 false 也不能当成「确认没有」——响应根本没到。
        assertEquals(
            BolusInfusionCheck.UNKNOWN,
            BolusInfusionCheck.evaluate(freshQuery = false, bolusDelivering = false, extendedBolusActive = false),
        )
        assertFalse(BolusInfusionCheck.UNKNOWN.allowsStart())
    }
}
