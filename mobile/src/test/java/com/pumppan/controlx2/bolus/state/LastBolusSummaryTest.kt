package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastBolusSummaryTest {

    @Test
    fun summarize_recordWithDoseAndTime() {
        val summary = summarizeLastBolus(deliveredVolume = 8_000, timestampSeconds = 3_600)
        assertTrue(summary is LastBolusSummary.Record)
        summary as LastBolusSummary.Record
        assertEquals(8_000L, summary.doseMilliunits)
        assertEquals(2008, summary.timestamp.atZone(java.time.ZoneOffset.UTC).year)
    }

    @Test
    fun summarize_noRecord() {
        assertEquals(LastBolusSummary.None, summarizeLastBolus(null, null))
        assertEquals(LastBolusSummary.None, summarizeLastBolus(0L, 0L))
        assertEquals(LastBolusSummary.None, summarizeLastBolus(null, 0L))
    }

    @Test
    fun summarize_doseWithoutTimestampIsUnknownTime() {
        val summary = summarizeLastBolus(deliveredVolume = 8_000, timestampSeconds = 0L)
        assertTrue(summary is LastBolusSummary.UnknownTime)
        assertEquals(8_000L, (summary as LastBolusSummary.UnknownTime).doseMilliunits)

        val missing = summarizeLastBolus(deliveredVolume = 8_000, timestampSeconds = null)
        assertTrue(missing is LastBolusSummary.UnknownTime)
    }
}
