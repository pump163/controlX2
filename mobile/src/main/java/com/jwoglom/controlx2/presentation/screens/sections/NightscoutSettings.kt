@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncConfig
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncStatusStore
import com.jwoglom.controlx2.sync.nightscout.ProcessorType
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncWorker
import com.jwoglom.controlx2.sync.nightscout.normalizeNightscoutUrl
import com.jwoglom.controlx2.presentation.components.HeaderLine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.jwoglom.controlx2.R

@Composable
fun NightscoutSettings(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    pumpSid: Int = 0,
    navigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("controlx2", android.content.Context.MODE_PRIVATE)
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(NightscoutSyncConfig.load(prefs)) }
    var syncStatus by remember { mutableStateOf(NightscoutSyncStatusStore.load(prefs)) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showApiSecretDialog by remember { mutableStateOf(false) }
    var showProcessorsDialog by remember { mutableStateOf(false) }
    var showLookbackDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(context.getString(R.string.nightscout_settings))
                Divider()
            }

            // Enable/Disable Nightscout sync
            item {
                ListItem(
                    headlineContent = {
                        Text(if (config.enabled) context.getString(R.string.nightscout_disable_nightscout_sync) else context.getString(R.string.nightscout_enable_nightscout_sync))
                    },
                    supportingContent = {
                        Text(
                            if (config.enabled) {
                                context.getString(R.string.nightscout_stops_auto_upload_to_nightscout)
                            } else {
                                context.getString(R.string.nightscout_start_auto_upload_pump_data)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (config.enabled) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = if (config.enabled) context.getString(R.string.nightscout_disable) else context.getString(R.string.nightscout_enable)
                        )
                    },
                    modifier = Modifier.clickable {
                        val newConfig = config.copy(enabled = !config.enabled)
                        config = newConfig
                        NightscoutSyncConfig.save(prefs, newConfig)

                        // Start/stop worker
                        if (newConfig.enabled) {
                            NightscoutSyncWorker.startIfEnabled(context, prefs, pumpSid)
                            Toast.makeText(context, context.getString(R.string.nightscout_sync_enabled), Toast.LENGTH_SHORT).show()
                        } else {
                            NightscoutSyncWorker.stopIfRunning()
                            Toast.makeText(context, context.getString(R.string.nightscout_sync_disabled), Toast.LENGTH_SHORT).show()
                        }

                        syncStatus = NightscoutSyncStatusStore.load(prefs)
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_last_successful_sync)) },
                    supportingContent = {
                        Text(
                            syncStatus.lastSuccessfulSyncMillis?.let { formatTimestamp(it) } ?: context.getString(R.string.nightscout_never)
                        )
                    }
                )
                Divider()
            }

            item {
                val connectionMessage = if (!config.enabled) {
                    context.getString(R.string.nightscout_sync_is_disabled)
                } else if (!config.isValid()) {
                    context.getString(R.string.nightscout_config_is_incomplete)
                } else if (!syncStatus.lastError.isNullOrBlank()) {
                    syncStatus.lastError ?: context.getString(R.string.nightscout_connection_error)
                } else {
                    context.getString(R.string.nightscout_connected)
                }

                val supportingMessage = if (config.enabled && !syncStatus.lastError.isNullOrBlank()) {
                    syncStatus.lastErrorMillis?.let { "Last failure: ${formatTimestamp(it)}" } ?: ""
                } else {
                    ""
                }

                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_connection_status)) },
                    supportingContent = {
                        Column {
                            Text(connectionMessage)
                            if (supportingMessage.isNotBlank()) {
                                Text(supportingMessage)
                            }
                        }
                    }
                )
                Divider()
            }

            // Nightscout URL
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_url)) },
                    supportingContent = {
                        Text(config.nightscoutUrl.ifBlank { context.getString(R.string.nightscout_not_configured) })
                    },
                    modifier = Modifier.clickable {
                        showUrlDialog = true
                    }
                )
                Divider()
            }

            // API Secret
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_api_secret)) },
                    supportingContent = {
                        Text(if (config.apiSecret.isNotBlank()) "••••••••" else context.getString(R.string.nightscout_not_configured))
                    },
                    modifier = Modifier.clickable {
                        showApiSecretDialog = true
                    }
                )
                Divider()
            }

            // Enabled Processors
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_data_types)) },
                    supportingContent = {
                        Text("${config.enabledProcessors.size} of ${ProcessorType.all().size} enabled")
                    },
                    modifier = Modifier.clickable {
                        showProcessorsDialog = true
                    }
                )
                Divider()
            }

            // Sync Interval
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_sync_interval)) },
                    supportingContent = { Text(context.getString(R.string.nightscout_minutes, config.syncIntervalMinutes)) },
                    modifier = Modifier.clickable {
                        showIntervalDialog = true
                    }
                )
                Divider()
            }

            // Lookback Period
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_initial_lookback)) },
                    supportingContent = { Text(context.getString(R.string.nightscout_hours_on_first_sync, config.initialLookbackHours)) },
                    modifier = Modifier.clickable {
                        showLookbackDialog = true
                    }
                )
                Divider()
            }

            // Sync Now button
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.nightscout_sync_now)) },
                    supportingContent = { Text(context.getString(R.string.nightscout_trigger_immediate_sync)) },
                    leadingContent = {
                        Icon(Icons.Filled.Refresh, contentDescription = context.getString(R.string.nightscout_sync))
                    },
                    modifier = Modifier.clickable {
                        if (config.enabled && config.isValid()) {
                            coroutineScope.launch {
                                NightscoutSyncWorker.getInstance(context, prefs, pumpSid).syncNow()
                                syncStatus = NightscoutSyncStatusStore.load(prefs)
                                Toast.makeText(context, context.getString(R.string.nightscout_sync_triggered), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.nightscout_enable_and_config_first),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("Back") },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        }
    )

    // URL Dialog
    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf(config.nightscoutUrl) }
        var urlError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(context.getString(R.string.nightscout_url)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            urlError = null
                        },
                        label = { Text(context.getString(R.string.nightscout_url_2)) },
                        placeholder = { Text("https://your-nightscout.herokuapp.com") },
                        isError = urlError != null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (urlError != null) {
                        Text(
                            text = urlError!!,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalizedUrl = normalizeNightscoutUrl(urlInput)
                    if (normalizedUrl == null) {
                        urlError = context.getString(R.string.nightscout_enter_valid_nightscout_url)
                        return@TextButton
                    }

                    val newConfig = config.copy(nightscoutUrl = normalizedUrl)
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showUrlDialog = false
                }) {
                    Text(context.getString(R.string.nightscout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(context.getString(R.string.nightscout_cancel))
                }
            }
        )
    }

    // API Secret Dialog
    if (showApiSecretDialog) {
        var secretInput by remember { mutableStateOf(config.apiSecret) }
        AlertDialog(
            onDismissRequest = { showApiSecretDialog = false },
            title = { Text(context.getString(R.string.nightscout_api_secret)) },
            text = {
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text(context.getString(R.string.nightscout_api_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newConfig = config.copy(apiSecret = secretInput.trim())
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showApiSecretDialog = false
                }) {
                    Text(context.getString(R.string.nightscout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiSecretDialog = false }) {
                    Text(context.getString(R.string.nightscout_cancel))
                }
            }
        )
    }

    // Processors Dialog
    if (showProcessorsDialog) {
        var selectedProcessors by remember { mutableStateOf(config.enabledProcessors) }
        AlertDialog(
            onDismissRequest = { showProcessorsDialog = false },
            title = { Text(context.getString(R.string.nightscout_select_data_types)) },
            text = {
                LazyColumn {
                    items(ProcessorType.all().toList()) { processorType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProcessors = if (selectedProcessors.contains(processorType)) {
                                        selectedProcessors - processorType
                                    } else {
                                        selectedProcessors + processorType
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedProcessors.contains(processorType),
                                onCheckedChange = null
                            )
                            Text(
                                text = processorType.displayName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newConfig = config.copy(enabledProcessors = selectedProcessors)
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showProcessorsDialog = false
                }) {
                    Text(context.getString(R.string.nightscout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showProcessorsDialog = false }) {
                    Text(context.getString(R.string.nightscout_cancel))
                }
            }
        )
    }

    // Sync Interval Dialog
    if (showIntervalDialog) {
        var intervalInput by remember { mutableStateOf(config.syncIntervalMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text(context.getString(R.string.nightscout_sync_interval)) },
            text = {
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    label = { Text(context.getString(R.string.nightscout_minutes_2)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = intervalInput.toIntOrNull() ?: 15
                    val newConfig = config.copy(syncIntervalMinutes = minutes.coerceIn(5, 1440))
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)

                    // Restart worker with new interval
                    if (config.enabled) {
                        NightscoutSyncWorker.stopIfRunning()
                        NightscoutSyncWorker.startIfEnabled(context, prefs, pumpSid)
                    }

                    showIntervalDialog = false
                }) {
                    Text(context.getString(R.string.nightscout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showIntervalDialog = false }) {
                    Text(context.getString(R.string.nightscout_cancel))
                }
            }
        )
    }

    // Lookback Dialog
    if (showLookbackDialog) {
        var lookbackInput by remember { mutableStateOf(config.initialLookbackHours.toString()) }
        AlertDialog(
            onDismissRequest = { showLookbackDialog = false },
            title = { Text(context.getString(R.string.nightscout_initial_lookback_period)) },
            text = {
                Column {
                    Text(context.getString(R.string.nightscout_initial_sync_lookback_hours))
                    OutlinedTextField(
                        value = lookbackInput,
                        onValueChange = { lookbackInput = it },
                        label = { Text(context.getString(R.string.nightscout_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = lookbackInput.toIntOrNull() ?: 24
                    val newConfig = config.copy(initialLookbackHours = hours.coerceIn(1, 720))
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showLookbackDialog = false
                }) {
                    Text(context.getString(R.string.nightscout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLookbackDialog = false }) {
                    Text(context.getString(R.string.nightscout_cancel))
                }
            }
        )
    }
}

private fun formatTimestamp(timestampMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
