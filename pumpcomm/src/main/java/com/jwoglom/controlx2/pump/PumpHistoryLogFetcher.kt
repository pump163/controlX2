package com.jwoglom.controlx2.pump

import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HistoryLogStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLog
import kotlinx.coroutines.CoroutineScope

/**
 * Interface for history log fetching, abstracting the concrete HistoryLogFetcher
 * so PumpCommHandler doesn't depend on mobile-app DB classes.
 */
interface PumpHistoryLogFetcher {
    suspend fun onStatusResponse(message: HistoryLogStatusResponse, scope: CoroutineScope)
    suspend fun onStreamResponse(log: HistoryLog)
    fun cancel()

    /**
     * Drops fetch bookkeeping (dedupe cache / high-water sequence id) so entries
     * can be re-persisted after the history log database is emptied.
     * No-op by default; implementations that track state override it.
     */
    fun reset() {}
}
