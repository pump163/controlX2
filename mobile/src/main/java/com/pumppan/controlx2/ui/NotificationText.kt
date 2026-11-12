package com.pumppan.controlx2.ui

import android.content.Context
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlarmStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlertStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CGMAlertStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ReminderStatusResponse

/**
 * 通知页枚举文案本地化。
 *
 * PumpX2 只提供英文 `name`（如 EMPTY_CARTRIDGE_ALARM）与英文 `description`，
 * 通知页会原样显示，导致中文界面里出现整段英文。
 *
 * 做法：按「枚举常量名」查中文资源（`values-zh-rCN/notification_enums.xml`）；
 * 查不到（英文界面、上游新增常量）就回退枚举原文，保证任何情况下都有内容可显示。
 *
 * 键名规则：`notif_enum_{alert|alarm|reminder|cgm_alert}_{name|desc}_{常量名小写}`
 */
object NotificationText {

    fun alertName(context: Context, type: AlertStatusResponse.AlertResponseType): String =
        nameOr(context, "notif_enum_alert_name_", "notif_enum_undefined_alert", type.name)
            ?: type.name

    /** 警报说明；PumpX2 未提供说明时返回空串（与原行为一致）。 */
    fun alertDescription(context: Context, type: AlertStatusResponse.AlertResponseType): String =
        descriptionOr(context, "notif_enum_alert_desc_", type.name)
            ?: type.description.orEmpty()

    /** 提醒名；PumpX2 的提醒没有 description。 */
    fun reminderName(context: Context, type: ReminderStatusResponse.ReminderType): String =
        nameOr(context, "notif_enum_reminder_name_", "notif_enum_undefined_reminder", type.name)
            ?: type.name

    fun alarmName(context: Context, type: AlarmStatusResponse.AlarmResponseType): String =
        nameOr(context, "notif_enum_alarm_name_", "notif_enum_undefined_alarm", type.name)
            ?: type.name

    /** 报警说明；PumpX2 未提供说明时返回空串。 */
    fun alarmDescription(context: Context, type: AlarmStatusResponse.AlarmResponseType): String =
        descriptionOr(context, "notif_enum_alarm_desc_", type.name)
            ?: type.description.orEmpty()

    fun cgmAlertName(context: Context, type: CGMAlertStatusResponse.CGMAlert): String =
        nameOr(context, "notif_enum_cgm_alert_name_", "notif_enum_undefined_cgm_alert", type.name)
            ?: type.name

    private fun nameOr(
        context: Context,
        keyPrefix: String,
        genericKey: String,
        enumName: String,
    ): String? {
        // DEFAULT_* 常量没有实际语义，统一显示“未定义……（代码 N）”。
        if (enumName.startsWith("DEFAULT_")) {
            return generic(context, genericKey, enumName)
        }
        return stringOrNull(context, keyPrefix + enumName.lowercase())
    }

    /** 说明类文案；DEFAULT_* 同样走通用兜底。 */
    private fun descriptionOr(context: Context, keyPrefix: String, enumName: String): String? {
        if (enumName.startsWith("DEFAULT_")) {
            // 说明行的兜底与名称行不同：没有说明就留空，避免重复“未定义”占两行。
            return ""
        }
        return stringOrNull(context, keyPrefix + enumName.lowercase())
    }

    private fun generic(context: Context, key: String, enumName: String): String? {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        if (id == 0) return null
        return context.getString(id, enumName.substringAfterLast('_'))
    }

    private fun stringOrNull(context: Context, key: String): String? {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }
}
