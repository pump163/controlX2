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

    /** 页面声明的可见域意图；与实例是否已挂接无关。 */
    private val visibleSubs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 窗口意图：协调器未挂接时先记下到期时刻，attach 后按剩余时长补开。 */
    private val pendingWindows = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
    }

    fun detach() {
        coordinator = null
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
}
