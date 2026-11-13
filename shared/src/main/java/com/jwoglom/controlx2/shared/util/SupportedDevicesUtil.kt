package com.jwoglom.controlx2.shared.util

import com.jwoglom.controlx2.shared.capability.Capability
import com.jwoglom.controlx2.shared.capability.CapabilityTable
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
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

/**
 * 能力表版重载：总闸（`model == MOBI`）&& 能力表 && 库声明（可选）。
 *
 * 迁移口径：把「这个页面/这个动作能不能控制」改成「这台泵允不允许这类能力」，
 * 判断逻辑收进 [CapabilityTable]，调用点只选能力名。行为与迁移前一致：
 * X2 全禁、Mobi 按现状放开；后续放开/收紧只改表。
 *
 * @param messageClass 传入时叠加 pumpx2 `@MessageProps.supportedDevices` 的二次收窄
 * @param apiVersion 连接初期可能为 null；首版不参与判定，保留给按版本分档
 */
fun canControlPump(
    model: KnownDeviceModel?,
    capability: Capability,
    messageClass: Class<out Message>? = null,
    apiVersion: ApiVersion? = null,
): Boolean {
    if (!canControlPump(model)) return false
    if (!CapabilityTable.allows(model, apiVersion, capability)) return false
    return messageClass?.let { supportsDevice(model, it) } ?: true
}
