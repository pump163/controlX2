@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.sync.xdrip.XdripBroadcastSender
import com.jwoglom.controlx2.sync.xdrip.XdripPayloadGroup
import com.jwoglom.controlx2.sync.xdrip.XdripSyncConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import com.jwoglom.controlx2.R

@Composable
fun XdripSettings(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    navigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = Prefs(context).prefs()
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(XdripSyncConfig.load(prefs)) }

    fun saveConfig(newConfig: XdripSyncConfig, showToastText: String? = null) {
        val oldConfig = config
        config = newConfig
        XdripSyncConfig.save(prefs, newConfig)

        if (newConfig.requiresReloadComparedTo(oldConfig)) {
            coroutineScope.launch {
                delay(250)
                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                delay(250)
                sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
            }
        }

        if (showToastText != null) {
            Toast.makeText(context, showToastText, Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePayload(payloadGroup: XdripPayloadGroup) {
        val payloadEnabled = config.isPayloadEnabled(payloadGroup)
        saveConfig(config.withPayloadEnabled(payloadGroup, !payloadEnabled))
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(context.getString(R.string.xdrip_x_drip_settings))
                Divider()
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(if (config.enabled) context.getString(R.string.xdrip_disable_x_drip_sync) else context.getString(R.string.xdrip_enable_x_drip_sync))
                    },
                    supportingContent = {
                        Text(
                            if (config.enabled) {
                                context.getString(R.string.xdrip_stop_pump_cgm_updates)
                            } else {
                                context.getString(R.string.xdrip_broadcast_selected_payload_groups)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (config.enabled) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = if (config.enabled) context.getString(R.string.xdrip_disable) else context.getString(R.string.xdrip_enable)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { saveConfig(config.copy(enabled = it)) }
                        )
                    },
                    modifier = Modifier.clickable {
                        val newEnabled = !config.enabled
                        saveConfig(
                            config.copy(enabled = newEnabled),
                            if (newEnabled) context.getString(R.string.xdrip_x_drip_sync_enabled) else context.getString(R.string.xdrip_x_drip_sync_disabled)
                        )
                    }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = context.getString(R.string.xdrip_send_sgv),
                    subtitle = context.getString(R.string.xdrip_broadcast_current_glucose),
                    enabled = config.sendCgmSgv,
                    onToggle = { togglePayload(XdripPayloadGroup.CGM) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = context.getString(R.string.xdrip_send_device_stat),
                    subtitle = context.getString(R.string.xdrip_broadcast_battery_iob_cartridge_basal),
                    enabled = config.sendPumpDeviceStatus,
                    onToggle = { togglePayload(XdripPayloadGroup.PUMP_DEVICE_STATUS) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = context.getString(R.string.xdrip_send_treatments),
                    subtitle = context.getString(R.string.xdrip_broadcast_bolus_treatments_to_x_drip),
                    enabled = config.sendTreatments,
                    onToggle = { togglePayload(XdripPayloadGroup.TREATMENTS) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = context.getString(R.string.xdrip_send_statusline),
                    subtitle = context.getString(R.string.xdrip_broadcast_one_line_pump_stat_text),
                    enabled = config.sendStatusLine,
                    onToggle = { togglePayload(XdripPayloadGroup.STATUS_LINE) }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.xdrip_send_diagnostics_test_payload)) },
                    supportingContent = {
                        Text(context.getString(R.string.xdrip_send_test_sgv_statusline))
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = context.getString(R.string.xdrip_diagnostics_icon)
                        )
                    },
                    modifier = Modifier.clickable {
                        sendDiagnosticsPayload(context)
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_back)) },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        }
    )
}

@Composable
private fun XdripPayloadToggleItem(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        },
        modifier = Modifier.clickable { onToggle() }
    )
}

private fun sendDiagnosticsPayload(context: Context) {
    val now = Instant.now()
    val sender = XdripBroadcastSender(context)
    val sgvSent = sender.sendSgv(
        JSONArray().put(
            JSONObject().apply {
                put("mgdl", 123)
                put("mills", now.toEpochMilli())
                put("direction", "Flat")
            }
        ).toString()
    )
    val statusSent = sender.sendExternalStatusline(context.getString(R.string.xdrip_ctrl_x2_test_statusline, now))

    val message = buildString {
        append(context.getString(R.string.xdrip_diagnostics_sent))
        append(if (sgvSent) context.getString(R.string.xdrip_sgv_ok) else context.getString(R.string.xdrip_sgv_skipped))
        append(if (statusSent) context.getString(R.string.xdrip_statusline_ok) else context.getString(R.string.xdrip_statusline_skipped))
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
