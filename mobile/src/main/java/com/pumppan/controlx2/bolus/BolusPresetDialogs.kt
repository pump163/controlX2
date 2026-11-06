package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusLimits

/**
 * 编辑一条预设：改名 + 直接改**绝对值**（立即段 / 延后段 / 时长分钟）。
 * 预设只存绝对值，百分比仅作派生显示，不参与入库；不改任何输注状态。
 */
@Composable
fun BolusPresetEditDialog(
    preset: BolusPreset,
    limits: BolusLimits,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (String, BolusPresetSpec) -> Unit,
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var immediate by remember(preset.id) { mutableStateOf(preset.immediateMilliunits) }
    var later by remember(preset.id) { mutableStateOf(preset.laterMilliunits) }
    var minutes by remember(preset.id) { mutableStateOf(preset.durationMinutes) }
    var extended by remember(preset.id) { mutableStateOf(preset.extendedEnabled) }
    val maxMinutes = limits.maxExtendedSeconds(null, null) / 60

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
                            // 从普通切成延展：把现有剂量按 50/50 落到两段（百分比仅此处辅助）。
                            if (later <= 0) {
                                val half = BolusForm.quantizeToIncrement(
                                    (immediate + 1) / 2,
                                    limits.bolusIncrementMilliunits,
                                )
                                later = half
                                immediate -= half
                            }
                            if (minutes <= 0) minutes = BolusFlowState.DEFAULT_EXTENDED_SECONDS / 60
                        } else {
                            // 关掉延展：延后段并回立即段，时长归零。
                            immediate += later
                            later = 0
                            minutes = 0
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                DecimalField(
                    label = stringResource(R.string.bolus_v2_field_immediate),
                    value = immediate,
                    scale = 1000,
                    increment = limits.bolusIncrementMilliunits,
                    onCommit = { immediate = it },
                )
                if (extended) {
                    Spacer(Modifier.height(8.dp))
                    DecimalField(
                        label = stringResource(R.string.bolus_v2_field_later),
                        value = later,
                        scale = 1000,
                        increment = limits.bolusIncrementMilliunits,
                        onCommit = { later = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    MinutesField(
                        label = stringResource(R.string.bolus_v2_field_duration, maxMinutes),
                        seconds = minutes * 60,
                        maxMinutes = maxMinutes,
                        onCommit = { minutes = it / 60 },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.bolus_v2_split_summary,
                            formatDose(immediate, limits.bolusIncrementMilliunits),
                            formatDose(later, limits.bolusIncrementMilliunits),
                            BolusForm.percentOf(immediate, immediate + later),
                            100 - BolusForm.percentOf(immediate, immediate + later),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(
                onClick = {
                    onSave(
                        name,
                        BolusPresetSpec(
                            immediateMilliunits = immediate.coerceAtLeast(0),
                            laterMilliunits = if (extended) later.coerceAtLeast(0) else 0,
                            durationMinutes = if (extended) minutes.coerceAtLeast(0) else 0,
                        ),
                    )
                }
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
