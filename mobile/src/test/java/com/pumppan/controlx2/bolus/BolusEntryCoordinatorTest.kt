package com.pumppan.controlx2.bolus

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.pumppan.controlx2.bolus.state.BolusControllerMode
import com.pumppan.controlx2.bolus.state.SessionPhase
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
    ) = BolusEntryCoordinator(
        sendPumpCommands = { _, msgs -> sent += msgs },
        isPumpConnected = { connected },
        scope = scope,
        mode = { mode },
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
    fun v2Mode_onPumpConnected_sendsOnlyFirstReadOnlyRequest() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(BolusControllerMode.V2, connected = true, scope = scope)
            c.onPumpConnected()
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
            assertEquals(SessionPhase.DELIVERING, c.sessionState.value?.phase)
            assertEquals(77, c.sessionState.value?.bolusId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun v2Mode_firstMessage_startsColdStartReconcile_onlyOnce() {
        val scope = CoroutineScope(Job())
        try {
            val c = coordinator(BolusControllerMode.V2, connected = true, scope = scope)
            c.onPumpMessage(deliveringBolus(77))
            // 冷启动 → CURRENT；该条 44 被对账器视为 CURRENT 已响应，立刻推进到 EXTENDED
            assertEquals(2, sent.size)
            assertTrue(sent.first() is CurrentBolusStatusRequest)
            assertTrue(sent.last() is ExtendedBolusStatusRequest)
            // 对账已在跑：再次显式触发不应发出任何新请求（不重复冷启动）
            c.onPumpConnected()
            assertEquals(2, sent.size)
        } finally {
            scope.cancel()
        }
    }

    private fun deliveringBolus(bolusId: Int) =
        CurrentBolusStatusResponse(1, bolusId, 0, 2300, 0, 0)
}
