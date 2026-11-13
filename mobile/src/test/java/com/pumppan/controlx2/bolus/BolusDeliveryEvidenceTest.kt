package com.pumppan.controlx2.bolus

import com.pumppan.controlx2.ledger.BolusLedgerPhase
import com.pumppan.controlx2.ledger.LedgerEntry
import com.pumppan.controlx2.ledger.deliveryEvidence
import com.pumppan.controlx2.scheduled.ScheduledBolusChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已发出 ≠ 已输注」的唯一口径（用户 2026-11-13）。
 * 只有泵回执 `BolusStatus.complete` 才算已输注；仅本机发出只能算「已发出」。
 */
class BolusDeliveryEvidenceTest {

    @Test
    fun `pump complete is the only delivered evidence`() {
        assertEquals(
            BolusDeliveryEvidence.DELIVERED,
            bolusDeliveryEvidence(activeStatusId = null, terminalStatusId = 3, hasSendRecord = true),
        )
        // 终态优先于「刚发出」：即便发送记录缺失，COMPLETE 仍算已输注。
        assertEquals(
            BolusDeliveryEvidence.DELIVERED,
            bolusDeliveryEvidence(activeStatusId = null, terminalStatusId = 3, hasSendRecord = false),
        )
    }

    @Test
    fun `other terminals are failures not delivered`() {
        listOf(0, 1, 2, 4, 5, 6).forEach { id ->
            assertEquals(
                "status $id",
                BolusDeliveryEvidence.FAILED,
                bolusDeliveryEvidence(null, id, hasSendRecord = true),
            )
        }
    }

    @Test
    fun `sent without pump receipt stays sent`() {
        assertEquals(
            BolusDeliveryEvidence.SENT,
            bolusDeliveryEvidence(activeStatusId = null, terminalStatusId = null, hasSendRecord = true),
        )
    }

    @Test
    fun `active pump status is delivering`() {
        assertEquals(
            BolusDeliveryEvidence.DELIVERING,
            bolusDeliveryEvidence(activeStatusId = 2, terminalStatusId = null, hasSendRecord = true),
        )
        assertEquals(
            BolusDeliveryEvidence.DELIVERING,
            bolusDeliveryEvidence(activeStatusId = 1, terminalStatusId = null, hasSendRecord = true),
        )
    }

    @Test
    fun `no evidence at all is none`() {
        assertEquals(
            BolusDeliveryEvidence.NONE,
            bolusDeliveryEvidence(activeStatusId = null, terminalStatusId = null, hasSendRecord = false),
        )
    }

    @Test
    fun `ledger unknown phase cannot claim delivered`() {
        assertEquals(BolusDeliveryEvidence.SENT, entry(BolusLedgerPhase.UNKNOWN, null).deliveryEvidence())
        assertEquals(BolusDeliveryEvidence.DELIVERING, entry(BolusLedgerPhase.DELIVERING, null).deliveryEvidence())
        assertEquals(BolusDeliveryEvidence.DELIVERED, entry(BolusLedgerPhase.FINISHED, 3).deliveryEvidence())
        assertEquals(BolusDeliveryEvidence.FAILED, entry(BolusLedgerPhase.FINISHED, 5).deliveryEvidence())
    }

    @Test
    fun `chain constant stays aligned with pump status`() {
        assertTrue(ScheduledBolusChain.STATUS_COMPLETE == BolusTerminalStatus.COMPLETE)
    }

    private fun entry(phase: BolusLedgerPhase, terminalStatusId: Int?) = LedgerEntry(
        key = "test",
        bolusId = 1,
        phase = phase,
        startedAtMillis = 0L,
        lastSeenAtMillis = 0L,
        terminalStatusId = terminalStatusId,
    )
}
