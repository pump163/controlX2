package com.pumppan.controlx2.bolus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.preset.BolusPreset
import com.pumppan.controlx2.bolus.preset.BolusPresetSpec
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusLimits
import java.util.Locale

/** 编辑一条预设：改名 + 改数值；不做任何输注状态判断。 */
@Composable
fun BolusPresetEditDialog(
    preset: BolusPreset,
    limits: BolusLimits,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (String, BolusPresetSpec) -> Unit,
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var total by remember(preset.id) { mutableStateOf(preset.totalMilliunits) }
    var extended by remember(preset.id) { mutableStateOf(preset.extendedEnabled) }
    var percent by remember(preset.id) { mutableStateOf(preset.nowPercent) }
    var seconds by remember(preset.id) { mutableStateOf(preset.extendedSeconds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bolus_v2_preset_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PresetNameField(name = name, onNameChange = { name = it })
                Spacer(Modifier.height(8.dp))
                ExtendedSwitch(
                    checked = extended,
                    onCheckedChange = { enabled ->
                        extended = enabled
                        if (enabled) {
                            if (seconds <= 0) seconds = BolusFlowState.DEFAULT_EXTENDED_SECONDS
                            if (percent >= 100) percent = BolusFlowState.DEFAULT_PERCENT
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                DecimalField(
                    label = stringResource(R.string.bolus_v2_field_total),
                    value = total,
                    scale = 1000,
                    increment = limits.bolusIncrementMilliunits,
                    onCommit = { total = it },
                )
                if (extended) {
                    Spacer(Modifier.height(8.dp))
                    PercentField(value = percent, onCommit = { percent = it.coerceIn(0, 100) })
                    Spacer(Modifier.height(8.dp))
                    DecimalField(
                        label = stringResource(R.string.bolus_v2_field_duration, 8),
                        value = seconds,
                        scale = 3600,
                        onCommit = { seconds = it },
                    )
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, specOf(total, extended, percent, seconds)) }) {
                Text(stringResource(R.string.setup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dashboard_cancel))
            }
        },
    )
}

/** 确认页「保存为预设」：可改名，选择新建或覆盖已有条目。 */
@Composable
fun BolusSavePresetDialog(
    presets: List<BolusPreset>,
    initialName: String,
    initialSpec: BolusPresetSpec,
    limits: BolusLimits,
    errorText: String?,
    onDismiss: () -> Unit,
    onSaveNew: (String, BolusPresetSpec) -> Unit,
    onOverwrite: (Long, String, BolusPresetSpec) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var overwrite by remember { mutableStateOf(false) }
    var overwriteId by remember { mutableStateOf(presets.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bolus_v2_preset_save_as)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PresetNameField(name = name, onNameChange = { name = it })
                Spacer(Modifier.height(6.dp))
                PresetSpecSummary(initialSpec, limits)

                Spacer(Modifier.height(12.dp))
                ModeRow(
                    selected = !overwrite,
                    label = stringResource(R.string.bolus_v2_preset_mode_new),
                    onClick = { overwrite = false },
                )
                ModeRow(
                    selected = overwrite,
                    label = stringResource(R.string.bolus_v2_preset_mode_overwrite),
                    enabled = presets.isNotEmpty(),
                    onClick = { if (presets.isNotEmpty()) overwrite = true },
                )
                if (overwrite) {
                    Text(
                        text = stringResource(R.string.bolus_v2_preset_select),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        presets.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { overwriteId = preset.id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = overwriteId == preset.id,
                                    onClick = { overwriteId = preset.id },
                                )
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = if (overwrite) overwriteId else null
                    if (target != null) onOverwrite(target, name, initialSpec) else onSaveNew(name, initialSpec)
                },
                enabled = !overwrite || overwriteId != null,
            ) {
                Text(stringResource(R.string.setup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dashboard_cancel))
            }
        },
    )
}

@Composable
private fun PresetNameField(name: String, onNameChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.bolus_v2_preset_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 保存对话框里的只读摘要：要存的数值就是确认页这份草稿。 */
@Composable
private fun PresetSpecSummary(spec: BolusPresetSpec, limits: BolusLimits) {
    val dose = formatDose(spec.totalMilliunits, limits.bolusIncrementMilliunits)
    val mode = stringResource(
        if (spec.extendedEnabled) R.string.bolus_v2_mode_extended else R.string.bolus_v2_mode_standard,
    )
    val detail = if (spec.extendedEnabled) {
        val hours = stringResource(
            R.string.bolus_v2_duration_value,
            String.format(Locale.US, "%.1f", spec.extendedSeconds / 3600.0),
        )
        " · ${spec.nowPercent}:${100 - spec.nowPercent} · $hours"
    } else {
        ""
    }
    Text(
        text = "$dose U · $mode$detail",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExtendedSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.bolus_v2_extended_toggle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun specOf(
    totalMilliunits: Int,
    extendedEnabled: Boolean,
    nowPercent: Int,
    extendedSeconds: Int,
): BolusPresetSpec = BolusPresetSpec(
    totalMilliunits = totalMilliunits,
    extendedEnabled = extendedEnabled,
    nowPercent = if (extendedEnabled) nowPercent.coerceIn(0, 100) else 100,
    extendedSeconds = if (extendedEnabled) extendedSeconds else 0,
)
