package com.jwoglom.controlx2.presentation.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.jwoglom.controlx2.WearPrefs
import com.jwoglom.controlx2.presentation.ui.components.rememberRemoteTextInputLauncher
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncConfig
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncStatusStore
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncWorker
import java.time.Instant
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

/**
 * Pump-host watch settings for Nightscout sync. Reads/writes the `"controlx2"`
 * SharedPreferences file via [NightscoutSyncConfig.load] / [save] — same file
 * [WearPumpCommService.onPumpConnectedSync] passes to the worker.
 *
 * Scope is intentionally the MVP subset of mobile's `NightscoutSettings.kt`:
 * enable toggle, URL, API secret, and sync status. Processors / sync interval /
 * lookback hours remain phone-side only.
 */
@Composable
fun NightscoutSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("controlx2", Context.MODE_PRIVATE) }

    var config by remember { mutableStateOf(NightscoutSyncConfig.load(prefs)) }
    var syncStatus by remember { mutableStateOf(NightscoutSyncStatusStore.load(prefs)) }

    fun saveAndReload(newConfig: NightscoutSyncConfig) {
        config = newConfig
        NightscoutSyncConfig.save(prefs, newConfig)
        syncStatus = NightscoutSyncStatusStore.load(prefs)
    }

    val editUrlLauncher = rememberRemoteTextInputLauncher(label = stringResource(R.string.nightscout_ns_url)) { result ->
        if (result != null) saveAndReload(config.copy(nightscoutUrl = result))
    }
    val editSecretLauncher = rememberRemoteTextInputLauncher(label = stringResource(R.string.nightscout_api_secret)) { result ->
        if (result != null) saveAndReload(config.copy(apiSecret = result))
    }

    val state = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        state = state,
        autoCentering = AutoCenteringParams(),
    ) {
        item {
            Chip(
                onClick = {
                    val pumpSid = WearPrefs(context).currentPumpSid()
                    val enabling = !config.enabled
                    val newConfig = config.copy(enabled = enabling)
                    saveAndReload(newConfig)
                    when {
                        !enabling -> {
                            NightscoutSyncWorker.stopIfRunning()
                            Toast.makeText(context, context.getString(R.string.nightscout_ns_disabled), Toast.LENGTH_SHORT).show()
                        }
                        pumpSid < 0 -> {
                            // No pump has connected yet this run, so we have no
                            // valid Nightscout device tag. Let `onPumpConnectedSync`
                            // start the worker once the first real pumpSid arrives
                            // — the config is already persisted above.
                            Toast.makeText(
                                context,
                                context.getString(R.string.nightscout_enabled_starts_after_conn),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        else -> {
                            NightscoutSyncWorker.startIfEnabled(context, prefs, pumpSid)
                            Toast.makeText(context, context.getString(R.string.nightscout_ns_enabled), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                label = { Text(if (config.enabled) context.getString(R.string.nightscout_enabled_2) else context.getString(R.string.nightscout_disabled_2), fontSize = 13.sp) },
                secondaryLabel = { Text(context.getString(R.string.nightscout_tap_to_toggle), fontSize = 10.sp) },
                colors = if (config.enabled) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { editUrlLauncher() },
                label = { Text(context.getString(R.string.nightscout_url_2), fontSize = 12.sp) },
                secondaryLabel = {
                    Text(
                        text = compactUrlLabel(config.nightscoutUrl),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { editSecretLauncher() },
                label = { Text(stringResource(R.string.nightscout_api_secret), fontSize = 12.sp) },
                secondaryLabel = {
                    Text(
                        text = if (config.apiSecret.isBlank()) context.getString(R.string.nightscout_not_set) else "••••••",
                        fontSize = 10.sp,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LaunchedStatusText(syncStatus)
        }
        item {
            Text(
                text = context.getString(R.string.nightscout_advanced_config_on_phone),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun LaunchedStatusText(
    syncStatus: com.jwoglom.controlx2.sync.nightscout.NightscoutSyncStatus,
) {
    // Tick every 10s so relative timestamps refresh without re-saving config.
    val tick = intervalOf(10)
    val lastErrorTimeTemplate = stringResource(R.string.nightscout_last_error_err_time_ago)
    val lastErrorTemplate = stringResource(R.string.nightscout_last_error_err)
    val text = remember(syncStatus, tick, lastErrorTimeTemplate, lastErrorTemplate) {
        val parts = mutableListOf<String>()
        syncStatus.lastSuccessfulSyncMillis?.let {
            parts += "Last sync ${shortTimeAgo(Instant.ofEpochMilli(it))}"
        }
        syncStatus.lastError?.let { err ->
            val errTimeAgo = syncStatus.lastErrorMillis?.let { shortTimeAgo(Instant.ofEpochMilli(it)) }
            parts += if (errTimeAgo != null) String.format(lastErrorTimeTemplate, errTimeAgo, err) else String.format(lastErrorTemplate, err)
        }
        parts.joinToString("\n")
    }
    if (text.isNotBlank()) {
        Text(
            text = text,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
    }
}

/**
 * Renders a Nightscout URL in a form that fits in a Chip secondary label:
 * - empty → "Not set"
 * - http(s)://host/path → "host" (or host+short path if short enough)
 *
 * Keeps the host visible so the user can confirm which instance is configured
 * without the raw URL spilling into an unreadable multi-line wrap.
 */
@Composable
private fun compactUrlLabel(url: String): String {
    if (url.isBlank()) return stringResource(R.string.nightscout_not_set)
    val trimmed = url.trim().trimEnd('/')
    val afterScheme = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
    val host = afterScheme.substringBefore('/')
    return host.ifBlank { trimmed }
}
