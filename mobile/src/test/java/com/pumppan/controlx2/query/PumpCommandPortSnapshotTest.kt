package com.pumppan.controlx2.query

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ActiveAamBitsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 步：链路状态与三态快照。
 *
 * 用 `ActiveAamBitsRequest`（opCode 146）冒充一条回包来喂 `onMessage`——它同样是
 * `Message` 子类且 `getRequestProps()` 返回自身 opCode，正好覆盖缓存与年龄路径。
 */
class PumpCommandPortSnapshotTest {

    private class RecordingSender : PumpRequestSender {
        val sent = mutableListOf<Message>()

        override fun send(type: SendType, messages: List<Message>): Boolean {
            sent.addAll(messages)
            return true
        }
    }

    private fun response() = ActiveAamBitsRequest(byteArrayOf(2))

    private fun port() = PumpCommandPort(RecordingSender(), linkSilentAfterMs = 30_000L)

    @Test
    fun neverReceivedIsUnknown() {
        val snapshot = port().snapshot(146, freshMs = 30_000, staleMaxMs = 180_000, now = 1000)

        assertEquals(PumpCommandPort.SnapshotState.UNKNOWN, snapshot.state)
        assertEquals(PumpCommandPort.UnknownReason.NEVER_RECEIVED, snapshot.reason)
        assertNull(snapshot.message)
    }

    @Test
    fun withinFreshWindowIsFresh() {
        val port = port()
        port.onMessage(response(), now = 1000)

        val snapshot = port.snapshot(146, freshMs = 30_000, staleMaxMs = 180_000, now = 2000)

        assertEquals(PumpCommandPort.SnapshotState.FRESH, snapshot.state)
        assertEquals(1000L, snapshot.ageMs)
    }

    @Test
    fun betweenFreshAndStaleIsStale() {
        val port = port()
        port.onMessage(response(), now = 1000)

        val snapshot = port.snapshot(146, freshMs = 30_000, staleMaxMs = 180_000, now = 100_000)

        assertEquals(PumpCommandPort.SnapshotState.STALE, snapshot.state)
        assertEquals(99_000L, snapshot.ageMs)
    }

    @Test
    fun pastStaleMaxIsUnknownExpired() {
        val port = port()
        port.onMessage(response(), now = 1000)

        val snapshot = port.snapshot(146, freshMs = 30_000, staleMaxMs = 180_000, now = 200_000)

        assertEquals(PumpCommandPort.SnapshotState.UNKNOWN, snapshot.state)
        assertEquals(PumpCommandPort.UnknownReason.EXPIRED, snapshot.reason)
    }

    @Test
    fun linkDownMakesSnapshotUnknownAndResponseRestoresIt() {
        val port = port()
        port.onMessage(response(), now = 1000)
        assertEquals(PumpCommandPort.LinkState.OK, port.linkState(now = 1000))

        port.markLinkDown()
        assertEquals(PumpCommandPort.LinkState.DOWN, port.linkState(now = 1000))
        val down = port.snapshot(146, freshMs = 30_000, staleMaxMs = 180_000, now = 1000)
        assertEquals(PumpCommandPort.SnapshotState.UNKNOWN, down.state)
        assertEquals(PumpCommandPort.UnknownReason.DISCONNECTED, down.reason)

        // 通信挂起后仍有回包：说明链路已恢复，不需要显式 markLinkUp
        port.onMessage(response(), now = 2000)
        assertEquals(PumpCommandPort.LinkState.OK, port.linkState(now = 2000))
        assertTrue(port.snapshot(146, 30_000, 180_000, now = 2001).state == PumpCommandPort.SnapshotState.FRESH)
    }

    @Test
    fun silenceBeyondLinkSilentAfterMarksSuspect() {
        val port = port()
        port.onMessage(response(), now = 1000)

        assertEquals(PumpCommandPort.LinkState.SUSPECT, port.linkState(now = 40_000))
    }

    @Test
    fun twoSendFailuresMarkSuspect() {
        val failing = PumpCommandPort(
            object : PumpRequestSender {
                override fun send(type: SendType, messages: List<Message>): Boolean = false
            },
            linkSilentAfterMs = 30_000L,
        )
        failing.request(response(), timeoutMs = 8000, now = 1000)
        failing.request(response(), timeoutMs = 8000, now = 1000)

        assertEquals(PumpCommandPort.LinkState.SUSPECT, failing.linkState(now = 1000))
    }

    @Test
    fun shortLivedConnectionMarksEdgeAndStableWindowExitsIt() {
        val port = port()
        port.onMessage(response(), now = 1_000)

        // 连上 14s 就断：命中「重连 60s 内又断」，进入 EDGE。
        port.markLinkUp(now = 1_000, quietMs = 0)
        port.markLinkDown(now = 15_000)
        port.markLinkUp(now = 16_000, quietMs = 0)
        assertEquals(PumpCommandPort.LinkState.EDGE, port.linkState(now = 16_000))

        // 稳定 3 分钟且期间有响应 → 退出 EDGE。
        port.onMessage(response(), now = 195_000)
        assertEquals(PumpCommandPort.LinkState.OK, port.linkState(now = 196_000))
    }

    @Test
    fun edgeExtendsQuietWindowAfterReconnect() {
        val port = port()
        port.onMessage(response(), now = 1_000)
        port.markLinkUp(now = 1_000, quietMs = 0)
        port.markLinkDown(now = 15_000)
        // EDGE 下重连静默窗取 max(入参, 15s)，避免与库的初始化抢链路。
        port.markLinkUp(now = 16_000, quietMs = 8_000)
        assertTrue(port.inQuietWindow(now = 16_000 + 14_000))
        assertFalse(port.inQuietWindow(now = 16_000 + 15_500))
    }
}
