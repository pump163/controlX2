package com.pumppan.controlx2.query

/**
 * 协调器实例的注册点：由持有端（CommService）挂上，调试端点读它。
 *
 * 为空表示「协调器还没接线」——此时 /api/query/stats 不输出 coordinator 段，
 * 避免把「有对象但没人调」误读成「已经在跑」。
 */
object QueryCoordinatorHolder {

    @Volatile
    private var coordinator: QueryCoordinator? = null

    fun attach(coordinator: QueryCoordinator) {
        this.coordinator = coordinator
    }

    fun detach() {
        coordinator = null
    }

    fun current(): QueryCoordinator? = coordinator
}
