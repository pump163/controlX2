package com.pumppan.controlx2.bolus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.controlx2.shared.util.shortTime24
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusReminderStrength
import com.pumppan.controlx2.bolus.state.buildBolusReminder
import java.time.Instant

/**
 * 「上次大剂量」提示行：**纯文字，不可点击、不带箭头、不跳转**。
 *
 * 数据同源首页「上次大剂量」窗口：`DataStore.lastBolusStatusResponse`（48/49 号响应）的
 * `deliveredVolume` + `timestamp`，由 `MainActivity` 收到报文时写入；这里不新造通路、不发请求。
 * 该值是泵实际执行过的交付量，**包含被取消或只打了部分的量**（那也是真实发生过的事）。
 *
 * 剂量缺失或已超过 24 小时 → 整行不渲染，不留占位。
 * 展示强度：2 小时内警示色 + 加粗；2–24 小时弱化；更早不显示。
 * 时间用绝对时间（`pumpTimeToLocalTz` + [shortTime24] 的 24 小时制），不做相对时间刷新。
 */
@Composable
fun LastBolusReminder(modifier: Modifier = Modifier) {
    val dataStore = LocalDataStore.current
    val response by dataStore.lastBolusStatusResponse.observeAsState()
    val deviceName by dataStore.setupDeviceName.observeAsState()
    val model = remember(deviceName) { determinePumpModel(deviceName.orEmpty()) }
    val increment = remember(model) { BolusLimits.forModel(model).bolusIncrementMilliunits }

    val volume = response?.deliveredVolume ?: 0L
    if (volume <= 0L) return

    val dose = formatDose(volume.toInt(), increment)
    val timestamp = response?.timestamp ?: 0L
    if (timestamp <= 0L) {
        // 有剂量但时间戳缺失：只显示剂量，不显示 1970。
        Text(
            text = stringResource(R.string.bolus_v2_last_bolus_unknown_time, dose),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val localTimestamp = pumpTimeToLocalTz(Dates.fromJan12008EpochSecondsToDate(timestamp))
    val reminder = remember(localTimestamp, volume) {
        buildBolusReminder(volume, localTimestamp, Instant.now())
    } ?: return

    // 大剂量时间统一走 shared 的 shortTime24（24 小时制）；不再向格式化函数传 am/pm 资源。
    val timeText = shortTime24(localTimestamp)
    val strong = reminder.strength == BolusReminderStrength.STRONG

    Text(
        text = stringResource(R.string.bolus_v2_last_bolus, dose, timeText),
        style = if (strong) {
            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodySmall
        },
        color = if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
