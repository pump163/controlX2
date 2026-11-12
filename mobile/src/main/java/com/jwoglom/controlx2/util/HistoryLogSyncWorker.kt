package com.jwoglom.controlx2.util

import com.jwoglom.controlx2.pump.PumpHistoryLogSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 历史日志补拉的「事件触发」入口，**不再自带周期定时**。
 *
 * 2026-11-12：原先这里每 5 分钟 `postDelayed` 一次 `HistoryLogStatusRequest`，
 * 与 `CommService.periodicUpdateTask` 的 5 min 批、以及首页第三档的同一条请求三重叠加。
 * 现在周期由查询协调器的 `HISTORY` 域（300s）统一负责，这里只保留两个事件入口：
 * - 连接建立后的一次性补拉（`refreshHistoryLogSyncWorker(triggerImmediateSync = true)`）；
 * - 页面/历史详情打开时要求立刻同步。
 *
 * `start()` / `stop()` 仅表示「是否处于可接受补拉请求的状态」，不再排定任何延时任务。
 */
class HistoryLogSyncWorker(
    private val requestSync: () -> Unit
) : PumpHistoryLogSyncWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isRunning = false

    override fun start() {
        if (isRunning) {
            Timber.v("HistoryLogSyncWorker already started")
            return
        }
        isRunning = true
        // 周期查询已移交 `HISTORY` 域（见 QueryDomainRequests），这里不排定时器。
        Timber.i("HistoryLogSyncWorker started (periodic sync handled by query coordinator)")
    }

    override fun stop() {
        isRunning = false
    }

    override fun triggerImmediateSync() {
        scope.launch {
            try {
                requestSync()
            } catch (t: Throwable) {
                Timber.w(t, "HistoryLogSyncWorker immediate sync failed")
            }
        }
    }
}
