package com.pumppan.controlx2.query

/**
 * 协调器实例的注册点：由持有端（CommService）挂上，调试端点读它。
 *
 * 为空表示「协调器还没接线」——此时 /api/query/stats 不输出 coordinator 段，
 * 避免把「有对象但没人调」误读成「已经在跑」。
 *
 * 可见性订阅也记在这里：页面可能比协调器先组合（首页就是启动首屏，服务晚几十毫秒
 * 才把协调器挂上）。若页面直接 `current()?.setVisible(...)`，那一刻 holder 还是空的，
 * 订阅会被静默丢掉，表现为「页面在，协调器却认为没人看」。所以订阅意图存在 holder 里，
 * `attach` 时补灌给新实例。
 */
object QueryCoordinatorHolder {

    @Volatile
    private var coordinator: QueryCoordinator? = null

    /**
     * 协调器的节拍线程（CommService 的 serviceLooper）。入口 kick 来自 UI 线程，
     * 必须回到这个线程执行，否则会和 [QueryCoordinator.tick] 抢同一批计时表。
     */
    @Volatile
    private var ticker: android.os.Handler? = null

    /** 页面声明的可见域意图；与实例是否已挂接无关。 */
    private val visibleSubs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 窗口意图：协调器未挂接时先记下到期时刻，attach 后按剩余时长补开。 */
    private val pendingWindows = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 入口 kick 意图：协调器未挂接时只记域名，attach 后补发一次。 */
    private val pendingKicks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun attach(coordinator: QueryCoordinator) {
        this.coordinator = coordinator
        // 把先到的页面订阅补灌进来（幂等：重复 add 不会重复触发拨表之外的副作用）。
        for (domain in visibleSubs) coordinator.setVisible(domain, true)
        // 打开窗口的意图同理：页面可能比协调器先到。
        val now = System.currentTimeMillis()
        for ((domain, until) in pendingWindows) {
            val remaining = until - now
            if (remaining > 0) coordinator.openWindow(domain, remaining) else pendingWindows.remove(domain)
        }
        // 入口 kick 的意图同样补发：页面先组合、服务后挂接时，这一次「进页看一眼」不能丢。
        for (domain in pendingKicks) coordinator.requestNow(domain, coalesceMs = 0)
        pendingKicks.clear()
    }

    fun detach() {
        coordinator = null
        ticker = null
    }

    fun current(): QueryCoordinator? = coordinator

    /**
     * 打开窗口域（大剂量确认期这类「短时间高频」）。
     *
     * 协调器还没挂接时只记意图，`attach` 时按剩余时长补开——否则页面早于服务时窗口会静默丢失，
     * 表现为确认期退回 10s 常规节奏，最坏把「泵已开始输注」的证据拖到十几秒后才看到。
     */
    fun openWindow(domain: String, durationMs: Long? = null) {
        val c = coordinator
        if (c != null) {
            c.openWindow(domain, durationMs)
        } else {
            pendingWindows[domain] = System.currentTimeMillis() +
                (durationMs ?: QueryCoordinator.DEFAULT_WINDOW_MS)
        }
    }

    fun closeWindow(domain: String) {
        pendingWindows.remove(domain)
        coordinator?.closeWindow(domain)
    }

    /** 页面订阅 / 退订：既改意图，也立刻作用到当前实例（若有）。 */
    fun setVisible(domain: String, visible: Boolean) {
        if (visible) visibleSubs.add(domain) else visibleSubs.remove(domain)
        coordinator?.setVisible(domain, visible)
    }

    /** 协调器就绪后把节拍线程登记进来；传空表退订。 */
    fun setTicker(handler: android.os.Handler?) {
        ticker = handler
    }

    /**
     * 一次性「立刻查一遍」：页面进入 / 切到某个标签时调用，委托给协调器。
     *
     * 三处细节：
     * - 协调器未挂接（服务还没起来）→ 只记意图，`attach` 时补发，不在这里发第二套请求；
     * - 必须回到服务线程执行，避免与 tick 抢计时表；本来就在服务线程时直接执行；
     * - 合并窗与链路补发都由协调器负责，页面不重复做。
     */
    fun requestNow(domain: String, coalesceMs: Long = QueryCoordinator.KICK_COALESCE_MS) {
        val c = coordinator ?: run {
            pendingKicks.add(domain)
            return
        }
        val handler = ticker
        if (handler != null && android.os.Looper.myLooper() != handler.looper) {
            handler.post { c.requestNow(domain, coalesceMs) }
        } else {
            c.requestNow(domain, coalesceMs)
        }
    }
}
