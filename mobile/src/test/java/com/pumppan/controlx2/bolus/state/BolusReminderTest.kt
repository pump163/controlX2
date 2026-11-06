package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BolusReminderTest {

    private val base = Instant.ofEpochSecond(1_000)

    @Test
    fun strongUpToTwoHours_weakAfter() {
        val atTwoHours = buildBolusReminder(1_500, base, base.plusSeconds(7_200))
        val justOver = buildBolusReminder(1_500, base, base.plusSeconds(7_201))
        assertEquals(BolusReminderStrength.STRONG, atTwoHours?.strength)
        assertEquals(BolusReminderStrength.WEAK, justOver?.strength)
    }

    @Test
    fun weakBetweenTwoAndTwentyFourHours() {
        val threeHours = buildBolusReminder(1_500, base, base.plusSeconds(3 * 3600L))
        assertEquals(BolusReminderStrength.WEAK, threeHours?.strength)
        val atDay = buildBolusReminder(1_500, base, base.plusSeconds(24 * 3600L))
        assertEquals(BolusReminderStrength.WEAK, atDay?.strength)
    }

    @Test
    fun hiddenAfterTwentyFourHours() {
        assertNull(buildBolusReminder(1_500, base, base.plusSeconds(24 * 3600L + 1)))
    }

    @Test
    fun missingDose_isHidden() {
        assertNull(buildBolusReminder(0, base, base.plusSeconds(60)))
        assertNull(buildBolusReminder(-100, base, base.plusSeconds(60)))
    }

    @Test
    fun clockSkew_treatedAsFreshAndStrong() {
        val reminder = buildBolusReminder(1_500, base, base.minusSeconds(50))
        assertEquals(BolusReminderStrength.STRONG, reminder?.strength)
    }

    @Test
    fun deliveredVolumeAndTimestampAreCarriedThrough() {
        val reminder = buildBolusReminder(8_000, base, base.plusSeconds(900))
        assertEquals(8_000L, reminder?.deliveredMilliunits)
        assertEquals(base, reminder?.pumpTimestamp)
    }
}
