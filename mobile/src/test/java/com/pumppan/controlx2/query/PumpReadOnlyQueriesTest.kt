package com.pumppan.controlx2.query

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读白名单的「不漏」守卫。
 *
 * 背景：白名单是静态表，新增请求类不会自动进入；漏补等于该请求绕过唯一出口的
 * 单飞去重（退化成旧行为，不会误挡控制类，但也失去了保护）。这里用
 * `QueryDomainRequests`（域 → 请求清单的唯一登记处）反过来核对：
 * **凡是登记进某个域的请求，都必须被认为是只读**。
 *
 * 只挑「清单是字面量」的域：首页三档 / CGM / CIQ 设置 / PROFILE 的清单会经
 * `apiVersion()`、`IDPManager` 等触到 `DataStore` 单例，在纯 JVM 单测里初始化会 NPE。
 * 剩下的域已经覆盖了所有周期性保底域与事件域，足以挡住「新增查询忘了同步白名单」。
 */
class PumpReadOnlyQueriesTest {

    /** 清单为字面量（或纯 pumpx2 静态构造）的域；不含依赖 DataStore 的页面 getter。 */
    private val testableDomains = listOf(
        "PUMP_VITALS", "IOB", "BASAL", "BOLUS_LAST", "CGM", "CONTROL_IQ", "HISTORY",
        "PUMP_STATE", "NOTIFICATIONS", "CARTRIDGE", "SAFETY_LIMITS", "PUMP_GLOBALS",
        "BOLUS_PRECHECK", "BOLUS_OUTCOME",
    )

    @Test
    fun everyTestableDomainRequestIsWhitelistedAsReadOnly() {
        val missing = testableDomains
            .flatMap { domain -> QueryDomainRequests.forDomain(domain).map { domain to it } }
            .filter { (_, message) -> !PumpReadOnlyQueries.isReadOnly(message.javaClass.simpleName) }
            .map { (domain, message) -> "$domain/${message.javaClass.simpleName}" }

        assertTrue("以下只读请求不在白名单里：$missing", missing.isEmpty())
    }

    @Test
    fun controlRequestsAreNotWhitelisted() {
        // 反向抽查：控制/取消/签名类绝不能被当成只读，否则会被单飞挡掉。
        listOf(
            "InitiateBolusRequest",
            "CancelBolusRequest",
            "SetTempRateRequest",
            "StopTempRateRequest",
            "SuspendPumpingRequest",
            "ResumePumpingRequest",
            "DismissNotificationRequest",
            "BolusPermissionRequest",
            "SetModesRequest",
        ).forEach { name ->
            assertTrue("控制类误入白名单：$name", !PumpReadOnlyQueries.isReadOnly(name))
        }
    }
}
