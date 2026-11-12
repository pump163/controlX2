package com.pumppan.controlx2.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlarmStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlertStatusResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知页枚举文案本地化回归测试。
 *
 * 目的：确认 `NotificationText` 能把 PumpX2 枚举解析成 zh-rCN 资源里的中文，
 * 而不是继续显示枚举常量名/英文说明（用户反馈的“报警还是英文”）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class NotificationTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `中文环境下报警名称与说明均为中文`() {
        val alarm = AlarmStatusResponse.AlarmResponseType.EMPTY_CARTRIDGE_ALARM
        assertEquals("药盒已空报警", NotificationText.alarmName(context, alarm))
        assertEquals("药盒胰岛素已用尽，无法继续输注，请装入新药盒。", NotificationText.alarmDescription(context, alarm))

        val resume = AlarmStatusResponse.AlarmResponseType.RESUME_PUMP_ALARM2
        assertEquals("恢复输注报警（二）", NotificationText.alarmName(context, resume))
        assertEquals("当前已停止输注，请尽快重新开始输注。", NotificationText.alarmDescription(context, resume))
    }

    @Test
    fun `中文环境下警报名称与说明均为中文`() {
        val alert = AlertStatusResponse.AlertResponseType.LOW_INSULIN_ALERT
        assertEquals("药盒胰岛素不足警报", NotificationText.alertName(context, alert))
        assertEquals("药盒内剩余胰岛素不足。", NotificationText.alertDescription(context, alert))

        val cartridgeChange = AlertStatusResponse.AlertResponseType.INCOMPLETE_CARTRIDGE_CHANGE_ALERT
        assertEquals("更换药盒未完成警报", NotificationText.alertName(context, cartridgeChange))
        assertEquals("已开始更换药盒，但未完成。", NotificationText.alertDescription(context, cartridgeChange))
    }

    @Test
    fun `DEFAULT 常量走未定义兜底而不是英文常量名`() {
        val unknownAlarm = AlarmStatusResponse.AlarmResponseType.DEFAULT_ALARM_13
        assertEquals("未定义报警（代码 13）", NotificationText.alarmName(context, unknownAlarm))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `英文环境回退到枚举原文`() {
        val alarm = AlarmStatusResponse.AlarmResponseType.EMPTY_CARTRIDGE_ALARM
        assertEquals("EMPTY_CARTRIDGE_ALARM", NotificationText.alarmName(context, alarm))
        assertEquals(
            "Cartridge is out of insulin and insulin delivery cannot occur. Please fill a new cartridge.",
            NotificationText.alarmDescription(context, alarm),
        )
    }
}
