package com.pumppan.controlx2.bolus

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.pumppan.controlx2.bolus.state.BolusControllerMode
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusEntryCoordinatorTest {

    private val sent = mutableListOf<Message>()

    private fun coordinator(
        mode: BolusControllerMode,
        connected: Boolean,
        scope: CoroutineScope,
        tickMillis: Long = 1_000L,
        settleMillis: Long = 10_000L,
    ) = BolusEntryCoordinator(
        sendPumpCommands = { _, msgs -> sent += msgs },
        isPumpConnected = { connected },
        scope = scope,
        mode = { mode },
        tickMillis = tickMillis,
        reconcileSettleMillis = settleMillis,
    )

    @Test
    fun legacyMode_neitherConsumesMessagesNorSendsRequests() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(BolusControllerMode.LEGACY, connected = true, scope = scope)
            c.onPumpMessage(deliveringBolus(77))
            c.onPumpConnected()
            assertNull(c.sessionState.value)
            assertTrue(sent.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_onPumpConnected_waitsForSettleBeforeFirstRequest() {
        val scope = CoroutineScope(Job())
        try {
            // 连上不立刻发：`pump-connected` 只代表 ACL 建链，这时发只会 2s 超时（真机实测）。
            val c = coordinator(BolusControllerMode.V2, connected = true, scope = scope, settleMillis = 5000)
            c.onPumpConnected()
            assertTrue("静置期内不应发任何查询", sent.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_afterSettle_sendsCurrentBolusQueryOnce() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(
                BolusControllerMode.V2,
                connected = true,
                scope = scope,
                tickMillis = 50,
                settleMillis = 0,
            )
            c.onPumpConnected()
            awaitUntil { sent.isNotEmpty() }
            assertEquals(1, sent.size)
            assertTrue(sent.single() is CurrentBolusStatusRequest)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_notConnected_doesNotSendRequests() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(BolusControllerMode.V2, connected = false, scope = scope)
            c.onPumpConnected()
            assertTrue(sent.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_onPumpMessage_forwardsToControllerAndMirrorsState() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(BolusControllerMode.V2, connected = true, scope = scope)
            c.onPumpMessage(deliveringBolus(77))
            assertEquals(BolusDeliveryState.ACTIVE, c.sessionState.value?.state)
            assertEquals(77, c.sessionState.value?.bolusId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_firstMessage_doesNotStartReconcileImmediately() {
        val scope = CoroutineScope(Job())
        try {
            // 首包也会顺带排一次对账，但同样要等静置期；静置期内不应对这根刚建立的链路发查询。
            val c = coordinator(BolusControllerMode.V2, connected = true, scope = scope, settleMillis = 5000)
            c.onPumpMessage(deliveringBolus(77))
            assertTrue(sent.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun awaitUntil(timeoutMillis: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时：条件未满足")
    }

    private fun deliveringBolus(bolusId: Int) =
        CurrentBolusStatusResponse(1, bolusId, 0, 2300, 0, 0)
}
