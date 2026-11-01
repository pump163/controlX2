package com.jwoglom.controlx2.shared.util

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * 该型号的泵是否支持这个请求（读 pumpx2 的 @MessageProps(supportedDevices=...)）。
 * 例：SetTempRateRequest 是 MOBI_ONLY，InitiateBolusRequest 未标注 = ALL。
 * 型号未知（null）时返回 false：宁可少显示可控项，也不要让人点了才发现不行。
 */
fun supportsDevice(model: KnownDeviceModel?, messageClass: Class<out Message>): Boolean {
    if (model == null) return false
    val props = messageClass.getAnnotation(MessageProps::class.java) ?: return false
    return props.supportedDevices.supports(model)
}

/**
 * 控制类操作是否可用 = App 自己的型号规则 AND pumpx2 的 supportedDevices。
 * 不能只用 supportedDevices：库里只有 13 类请求标了 MOBI_ONLY，其余默认 ALL，
 * 单用等于把 X2 控制页全部放行。这里 App 规则先挡，库里的声明只做二次收窄。
 */
/** App 自己的规则：只有 Mobi 能远程操作控制项，X2 只能看。 */
fun canControlPump(model: KnownDeviceModel?): Boolean = model == KnownDeviceModel.MOBI

fun canControlPump(model: KnownDeviceModel?, messageClass: Class<out Message>): Boolean =
    canControlPump(model) && supportsDevice(model, messageClass)
