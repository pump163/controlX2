package com.jwoglom.controlx2.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun LastConnectionUpdatedTimestamp() {
    val ds = LocalDataStore.current

    val pumpConnected = ds.pumpConnected.observeAsState()
    val pumpLastConnectionTimestamp = ds.pumpLastConnectionTimestamp.observeAsState()
    val pumpLastMessageTimestamp = ds.pumpLastMessageTimestamp.observeAsState()

    var pumpLastMessageTimestampRelative: String? by remember { mutableStateOf(null) }
    val unitsIn = stringResource(R.string.units_in)
    val unitsAgo = stringResource(R.string.units_ago)
    val unitsNow = stringResource(R.string.units_now)
    fun updatePumpLastMessageTsRelative() {
        pumpLastMessageTimestampRelative = pumpLastMessageTimestamp.value?.let {
            shortTimeAgo(it, prefix = unitsIn, suffix = unitsAgo, nowThresholdSeconds = 1, nowText = unitsNow)
        }
        //Timber.d("set pumpLastMessageTimestampRelative=%s", pumpLastMessageTimestampRelative)
    }

    LaunchedEffect (pumpLastMessageTimestamp.value) {
        updatePumpLastMessageTsRelative()
    }

    LaunchedEffect (intervalOf(10)) {
        updatePumpLastMessageTsRelative()
    }

    val relativeTs = pumpLastMessageTimestampRelative
    val lastConnTs = pumpLastConnectionTimestamp.value
    when {
        pumpConnected.value == false -> when {
            relativeTs != null -> Line(stringResource(R.string.comm_last_conn_pump_mess_timestamp_relative, relativeTs), bold = true)
            lastConnTs != null -> Line(stringResource(R.string.comm_device_last_seen, lastConnTs), bold = true)
            else -> {}
        }
        pumpLastMessageTimestamp.value == null -> {}
        relativeTs == null -> {}
        else -> {
            Line(stringResource(R.string.comm_last_up_timestamp_relative, relativeTs), bold = true)
        }

    }
}
