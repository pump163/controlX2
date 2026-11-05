package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusValidationError
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 第 2 步：剂量与扩展。
 *
 * 界面上不出现形状名：只有「总量」+「扩展大剂量」开关；打开后按「立即 : 延后」的百分比
 * 表达拆分（预设快捷项来自 [BolusLimits.extendedPercentPresets]），所有换算走 [BolusForm]。
 */
@Composable
fun BolusShapePanel(
    state: BolusFlowState,
    errors: List<BolusValidationError>,
    ciqEnabled: Boolean?,
    onChange: (BolusFlowState) -> Unit,
) {
    Column(Modifier) {
        DecimalField(
            label = stringResource(R.string.bolus_v2_field_total),
            value = state.totalMilliunits,
            scale = 1000,
            onCommit = { onChange(state.withTotal(it)) },
        )

        Spacer(Modifier.height(12.dp))

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
            Switch(
                checked = state.shape != BolusShape.NORMAL,
                onCheckedChange = { onChange(state.withExtended(it)) },
            )
        }

        if (state.shape != BolusShape.NORMAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.bolus_v2_split_summary,
                    formatScaled(state.nowMilliunits, 1000),
                    formatScaled(state.laterMilliunits, 1000),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BolusLimits.Default.extendedPercentPresets.forEach { preset ->
                    FilterChip(
                        selected = state.percent == preset,
                        onClick = { onChange(state.withPercent(preset)) },
                        label = { Text("$preset : ${100 - preset}") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            PercentField(value = state.percent, onCommit = { onChange(state.withPercent(it)) })

            Spacer(Modifier.height(8.dp))
            val maxHours = BolusForm.maxExtendedSeconds(ciqEnabled) / 3600
            DecimalField(
                label = stringResource(R.string.bolus_v2_field_duration, maxHours),
                value = state.extendedSeconds,
                scale = 3600,
                onCommit = { onChange(state.withDurationSeconds(it)) },
            )
        }

        errors.forEach { error ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = errorText(error, ciqEnabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun errorText(error: BolusValidationError, ciqEnabled: Boolean?): String = when (error) {
    BolusValidationError.NEGATIVE_AMOUNT -> stringResource(R.string.bolus_v2_error_negative)
    BolusValidationError.AMOUNT_BELOW_MINIMUM -> stringResource(R.string.bolus_v2_error_below_min)
    BolusValidationError.AMOUNT_ABOVE_MAXIMUM -> stringResource(R.string.bolus_v2_error_above_max)
    BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM ->
        stringResource(R.string.bolus_v2_error_extended_total)
    BolusValidationError.INCREMENT_NOT_ALIGNED -> stringResource(R.string.bolus_v2_error_increment)
    BolusValidationError.LATER_REQUIRED -> stringResource(R.string.bolus_v2_error_later_required)
    BolusValidationError.DURATION_OUT_OF_RANGE ->
        stringResource(R.string.bolus_v2_error_duration, BolusForm.maxExtendedSeconds(ciqEnabled) / 3600)
    BolusValidationError.SHAPE_INCONSISTENT -> stringResource(R.string.bolus_v2_error_shape)
}

/** 数值字段：模型存整型 milliunits，输入按 0.025U 步进量化，显示最多三位小数（去掉末尾 0）。 */
@Composable
internal fun DecimalField(
    label: String,
    value: Int,
    scale: Int,
    onCommit: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(if (value == 0) "" else formatScaled(value, scale)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(value, editing) {
        // 输入过程中不打断；失焦后把文本对齐到量化后的模型值。
        if (!editing && parseScaled(text, scale) != value) {
            text = if (value == 0) "" else formatScaled(value, scale)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' }
            text = filtered
            val parsed = parseScaled(filtered, scale)
            if (parsed != null) {
                // milliunits 必须落在 0.025U 网格上。
                val quantized = if (scale == 1000) BolusForm.quantizeToIncrement(parsed) else parsed
                onCommit(quantized)
            } else if (filtered.isBlank()) {
                onCommit(0)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { editing = it.isFocused },
    )
}

/** 百分比字段：0–100 的整数，模型里与 [BolusFlowState.percent] 同源。 */
@Composable
internal fun PercentField(value: Int, onCommit: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(3)
            text = filtered
            val parsed = filtered.toIntOrNull()
            if (parsed != null) onCommit(parsed.coerceIn(0, 100)) else if (filtered.isBlank()) onCommit(0)
        },
        label = { Text(stringResource(R.string.bolus_v2_field_percent)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatScaled(value: Int, scale: Int): String =
    String.format(Locale.US, "%.3f", value.toDouble() / scale).trimEnd('0').trimEnd('.')

private fun parseScaled(text: String, scale: Int): Int? =
    text.toDoubleOrNull()?.let { (it * scale).roundToInt() }
