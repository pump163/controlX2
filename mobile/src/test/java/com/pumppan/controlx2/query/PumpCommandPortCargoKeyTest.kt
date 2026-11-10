package com.pumppan.controlx2.query

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ActiveAamBitsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：`ActiveAamBitsRequest` 的 opCode 固定为 -110（146），cargo 是 1 字节的 2 或 4。
 * 单飞键只用 opCode 时，第二条会被误判 IN_FLIGHT 而丢数据（2026-11-10 真机发现）。
 */
class PumpCommandPortCargoKeyTest {

    private class RecordingSender : PumpRequestSender {
        val sent = mutableListOf<Message>()

        override fun send(type: SendType, messages: List<Message>): Boolean {
            sent.addAll(messages)
            return true
        }
    }

    private fun bits(value: Int) = ActiveAamBitsRequest(byteArrayOf(value.toByte()))

    @Test
    fun sameOpCodeDifferentCargoBothGoOut() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender)

        val first = port.request(bits(2), timeoutMs = 8000, now = 1000)
        val second = port.request(bits(4), timeoutMs = 8000, now = 1001)

        assertTrue("ActiveAamBits(2) 应发出", first.sent)
        assertTrue("ActiveAamBits(4) 不应被同 opCode 误拒", second.sent)
        assertEquals(2, sender.sent.size)
    }

    @Test
    fun sameOpCodeSameCargoStillDeduplicated() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender)

        val first = port.request(bits(2), timeoutMs = 8000, now = 1000)
        val second = port.request(bits(2), timeoutMs = 8000, now = 1001)

        assertTrue(first.sent)
        assertEquals(PumpCommandPort.Reject.IN_FLIGHT, second.reject)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun markThrottleClearsEveryCargoKeyOfOpCode() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender)

        port.request(bits(2), timeoutMs = 8000, now = 1000)
        port.request(bits(4), timeoutMs = 8000, now = 1001)
        assertEquals(1001L, port.inFlightSince(146))

        port.markThrottle(146, 1100)
        assertNull("markThrottle 应清掉该 opCode 下的全部在飞键", port.inFlightSince(146))

        val again = port.request(bits(2), timeoutMs = 8000, now = 1101)
        assertTrue("清在飞后应允许重发（minIntervalMs=0）", again.sent)
    }
}
