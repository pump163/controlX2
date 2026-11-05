package com.pumppan.controlx2.bolus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz
import com.jwoglom.controlx2.shared.util.shortTime
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.LastBolusSummary
import com.pumppan.controlx2.bolus.state.summarizeLastBolus

/**
 * 大剂量页顶部一行「上次大剂量」静态提醒。
 *
 * 数据同源首页「上次大剂量」窗口：`DataStore.lastBolusStatusResponse`（48 号响应），
 * 由 MainActivity 在收到 `LastBolusStatusAbstractResponse` 时写入；这里不新造数据通路、不发请求。
 * 只提示，不弹窗、不做重复剂量拦截。
 */
@Composable
fun LastBolusReminder(modifier: Modifier = Modifier) {
    val dataStore = LocalDataStore.current
    val response by dataStore.lastBolusStatusResponse.observeAsState()
    val deviceName by dataStore.setupDeviceName.observeAsState()
    val model = remember(deviceName) { determinePumpModel(deviceName.orEmpty()) }
    val increment = remember(model) { BolusLimits.forModel(model).bolusIncrementMilliunits }
    val summary = remember(response) {
        summarizeLastBolus(response?.deliveredVolume, response?.timestamp)
    }

    val text = when (summary) {
        LastBolusSummary.None -> stringResource(R.string.bolus_v2_last_bolus_none)

        is LastBolusSummary.UnknownTime -> stringResource(
            R.string.bolus_v2_last_bolus_unknown_time,
            formatDose(summary.doseMilliunits.toInt(), increment),
        )

        is LastBolusSummary.Record -> stringResource(
            R.string.bolus_v2_last_bolus,
            formatDose(summary.doseMilliunits.toInt(), increment),
            shortTime(
                pumpTimeToLocalTz(summary.timestamp),
                stringResource(R.string.units_m),
                stringResource(R.string.units_am),
                stringResource(R.string.units_pm),
            ),
        )
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
