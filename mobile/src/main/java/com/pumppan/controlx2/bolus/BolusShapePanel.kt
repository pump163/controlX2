package com.pumppan.controlx2.bolus

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusValidationError
import com.pumppan.controlx2.bolus.state.CiqVersion
import com.pumppan.controlx2.bolus.state.canStartRemoteExtended
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 第 2 步：剂量与扩展。
 *
 * 界面上不出现形状名：只有「总量」+「扩展大剂量」开关；打开后按「立即 : 延后」的百分比
 * 表达拆分（预设快捷项来自 [BolusLimits.extendedPercentPresets]），所有换算走 [BolusForm]。
 *
 * 扩展态下再分两态（仅型号支持远程发起延时出现）：
 * - **百分比**：输入总量 + 立即/延展两个百分比（输入一个即锁死另一个），换算结果只读；
 * - **剂量**：直接输入立即段 / 延后段的 U。
 */
@Composable
fun BolusShapePanel(
    state: BolusFlowState,
    errors: List<BolusValidationError>,
    ciqEnabled: Boolean?,
    limits: BolusLimits = BolusLimits.Default,
    ciqVersion: CiqVersion? = null,
    onChange: (BolusFlowState) -> Unit,
    /**
     * 是否按泵型号能力限制输入。
     * 大剂量流程（默认 true）按 [canStartRemoteExtended] 置灰开关、隐藏双态；
     * 预设编辑传入 false：只存数值、不预设泵型号，X2 上也能编辑方波/双波/百分比条目。
     */
    respectModelCapabilities: Boolean = true,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // 百分比快捷项：固定 3 个、只能改值不能增删（与 12 条剂量预设不共用存储）。
    var percentPresets by remember { mutableStateOf(prefs.bolusPercentPresets(limits.extendedPercentPresets)) }
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }
    // 双态：false = 百分比（左），true = 剂量（右）；默认百分比。
    var doseMode by rememberSaveable { mutableStateOf(false) }

    val extendedSupported = if (respectModelCapabilities) limits.canStartRemoteExtended() else true
    val extended = state.shape != BolusShape.NORMAL

    /** 百分比提交：100:0 等于没有延展，提示并切回普通形态，不留下「延展时长 0」的畸形状态。 */
    fun applyImmediatePercent(percent: Int) {
        val safe = percent.coerceIn(0, 100)
        if (safe >= 100) {
            Toast.makeText(context, R.string.bolus_v2_percent_immediate_full, Toast.LENGTH_SHORT).show()
            onChange(
                state.copy(
                    shape = BolusShape.NORMAL,
                    nowMilliunits = state.totalMilliunits,
                    laterMilliunits = 0,
                    extendedSeconds = 0,
                ),
            )
            return
        }
        onChange(state.withPercent(safe, limits))
    }

    Column(Modifier) {
        // 总量：普通/百分比模式输入；剂量模式下总量由两段相加，不再单独输入。
        if (!extended || !doseMode) {
            DecimalField(
                label = stringResource(R.string.bolus_v2_field_total),
                value = state.totalMilliunits,
                scale = 1000,
                increment = limits.bolusIncrementMilliunits,
                onCommit = { onChange(state.withTotal(it, limits)) },
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.bolus_v2_extended_toggle),
                style = MaterialTheme.typography.bodyLarge,
                color = if (extendedSupported) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = extended,
                enabled = extendedSupported,
                onCheckedChange = { if (extendedSupported) onChange(state.withExtended(it, limits)) },
            )
        }

        // 提示只在「本型号不支持远程发起延展」时出现（Mobi 支持 → unsupportedHint 为 null，整行不渲染）。
        // 型号名从 pump-capabilities.json 的 displayName 取；取不到回落「当前型号」，不在代码里写死型号。
        val unsupportedHint = limits.unsupportedHint
        if (!extendedSupported && unsupportedHint != null) {
            Spacer(Modifier.height(6.dp))
            val modelName = limits.modelDisplayName
                ?: stringResource(R.string.bolus_v2_current_model)
            Text(
                text = stringResource(R.string.bolus_v2_extended_unsupported_model, modelNameInSentence(modelName)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 双态开关与百分比视图只在型号支持远程发起延延时出现（X2 不渲染）。
        if (extended && extendedSupported) {
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.bolus_v2_mode_percent),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = doseMode, onCheckedChange = { doseMode = it })
                Text(
                    text = stringResource(R.string.bolus_v2_mode_dose),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            if (doseMode) {
                // 剂量态：直接按 U 输入两段；总量由两段相加，只读显示。
                DecimalField(
                    label = stringResource(R.string.bolus_v2_field_immediate),
                    value = state.nowMilliunits,
                    scale = 1000,
                    increment = limits.bolusIncrementMilliunits,
                    onCommit = { onChange(state.withNow(it)) },
                )
                Spacer(Modifier.height(8.dp))
                DecimalField(
                    label = stringResource(R.string.bolus_v2_field_later),
                    value = state.laterMilliunits,
                    scale = 1000,
                    increment = limits.bolusIncrementMilliunits,
                    onCommit = { onChange(state.withLater(it)) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.bolus_v2_total_summary,
                        formatDose(state.totalMilliunits, limits.bolusIncrementMilliunits),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 百分比态：总量已在上面输入，这里只调占比与时长。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    percentPresets.forEachIndexed { index, preset ->
                        PercentPresetChip(
                            percent = preset,
                            selected = state.percent == preset,
                            onClick = { applyImmediatePercent(preset) },
                            onLongClick = { editingPresetIndex = index },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PercentField(
                            label = stringResource(R.string.bolus_v2_field_percent),
                            value = state.percent,
                            onCommit = { applyImmediatePercent(it) },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        PercentField(
                            label = stringResource(R.string.bolus_v2_field_later_percent),
                            value = 100 - state.percent,
                            onCommit = { applyImmediatePercent(100 - it) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 换算结果只读：这就是落盘的绝对值（mU）。
                Text(
                    text = stringResource(
                        R.string.bolus_v2_split_summary,
                        formatDose(state.nowMilliunits, limits.bolusIncrementMilliunits),
                        formatDose(state.laterMilliunits, limits.bolusIncrementMilliunits),
                        state.percent,
                        100 - state.percent,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            // UI 以分钟输入（15–480、1 分钟步进、默认 2 小时）；wire 仍传秒。
            val maxMinutes = limits.maxExtendedSeconds(ciqEnabled, ciqVersion) / 60
            MinutesField(
                label = stringResource(R.string.bolus_v2_field_duration, maxMinutes),
                seconds = state.extendedSeconds,
                maxMinutes = maxMinutes,
                onCommit = { onChange(state.withDurationSeconds(it)) },
            )
        }

        errors.forEach { error ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = errorText(error, ciqEnabled, ciqVersion, limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    editingPresetIndex?.let { index ->
        val initial = percentPresets[index]
        var text by remember(index) { mutableStateOf(initial.toString()) }
        AlertDialog(
            onDismissRequest = { editingPresetIndex = null },
            title = { Text(stringResource(R.string.bolus_v2_percent_preset_edit_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.bolus_v2_field_percent)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = text.toIntOrNull()?.coerceIn(0, 100) ?: initial
                        prefs.setBolusPercentPreset(index, value)
                        percentPresets = prefs.bolusPercentPresets(limits.extendedPercentPresets)
                        editingPresetIndex = null
                    },
                ) {
                    Text(stringResource(R.string.setup_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPresetIndex = null }) {
                    Text(stringResource(R.string.dashboard_cancel))
                }
            },
        )
    }
}

/**
 * 型号名拼进句式：末位是字母或数字才补一个空格（`t:slim X2 不支持…`）。
 * 回落值「当前型号」以汉字结尾，不加空格（`当前型号不支持…`）。
 * 空格不写死在格式串里，两种取值读起来都自然。
 */
internal fun modelNameInSentence(modelName: String): String =
    if (modelName.lastOrNull()?.isLetterOrDigit() == true) "$modelName " else modelName

/** 百分比快捷 chip：短按选中，长按改值（只能改值，不能增删）。 */
@Composable
private fun PercentPresetChip(
    percent: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
            )
            .pointerInput(percent) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$percent : ${100 - percent}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun errorText(
    error: BolusValidationError,
    ciqEnabled: Boolean?,
    ciqVersion: CiqVersion? = null,
    limits: BolusLimits = BolusLimits.Default,
): String = when (error) {
    BolusValidationError.NEGATIVE_AMOUNT -> stringResource(R.string.bolus_v2_error_negative)
    BolusValidationError.AMOUNT_BELOW_MINIMUM -> stringResource(R.string.bolus_v2_error_below_min)
    BolusValidationError.AMOUNT_ABOVE_MAXIMUM -> stringResource(R.string.bolus_v2_error_above_max)
    BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM ->
        stringResource(R.string.bolus_v2_error_extended_total)
    BolusValidationError.IMMEDIATE_BELOW_MINIMUM ->
        stringResource(R.string.bolus_v2_error_immediate_min)
    BolusValidationError.REMOTE_EXTENDED_NOT_SUPPORTED ->
        stringResource(R.string.bolus_v2_extended_remote_unsupported)
    BolusValidationError.INCREMENT_NOT_ALIGNED -> stringResource(R.string.bolus_v2_error_increment)
    BolusValidationError.LATER_REQUIRED -> stringResource(R.string.bolus_v2_error_later_required)
    BolusValidationError.DURATION_OUT_OF_RANGE ->
        stringResource(
            R.string.bolus_v2_error_duration,
            BolusForm.maxExtendedSeconds(ciqEnabled, limits, ciqVersion) / 60,
        )
    BolusValidationError.SHAPE_INCONSISTENT -> stringResource(R.string.bolus_v2_error_shape)
}

/** 数值字段：模型存整型 milliunits，输入按配置的步进量化，显示位数随步进自适应。 */
@Composable
internal fun DecimalField(
    label: String,
    value: Int,
    scale: Int,
    increment: Int = BolusLimits.Default.bolusIncrementMilliunits,
    onCommit: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(if (value == 0) "" else formatScaled(value, scale, increment)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(value, editing) {
        // 输入过程中不打断；失焦后把文本对齐到量化后的模型值（含固定小数位）。
        if (!editing) {
            val canonical = if (value == 0) "" else formatScaled(value, scale, increment)
            if (text != canonical) text = canonical
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' }
            text = filtered
            val parsed = parseScaled(filtered, scale)
            if (parsed != null) {
                // milliunits 必须落在配置步进的网格上。
                val quantized = if (scale == 1000) BolusForm.quantizeToIncrement(parsed, increment) else parsed
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

/**
 * 延展时长字段：按**分钟**输入（1 分钟步进、上限随 CIQ 版本取 [maxMinutes]），
 * 模型仍存秒（协议字段是秒）。输入过程不打断，失焦后对齐到规范化分钟值。
 */
@Composable
internal fun MinutesField(
    label: String,
    seconds: Int,
    maxMinutes: Int,
    onCommit: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(if (seconds <= 0) "" else (seconds / 60).toString()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(seconds, editing) {
        if (!editing) {
            val canonical = if (seconds <= 0) "" else (seconds / 60).toString()
            if (text != canonical) text = canonical
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(3)
            text = filtered
            if (filtered.isBlank()) {
                onCommit(0)
            } else {
                filtered.toIntOrNull()?.let { onCommit(it.coerceIn(0, maxMinutes) * 60) }
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { editing = it.isFocused },
    )
}

/** 百分比字段：0–100 的整数，模型里与 [BolusFlowState.percent] 同源。 */
@Composable
internal fun PercentField(label: String, value: Int, onCommit: (Int) -> Unit) {
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
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 步进决定剂量小数位：25 → 3 位（0.025）、10/50 → 2 位（0.01 / 0.05）、100 → 1 位。 */
private fun doseDecimals(increment: Int): Int {
    var inc = increment
    var decimals = 3
    while (decimals > 1 && inc % 10 == 0) {
        inc /= 10
        decimals--
    }
    return decimals
}

/**
 * 剂量显示：按步进固定小数位、不裁尾 0（X2/Mobi → 50 网格，显示 0.05 / 0.10）。
 * 这样「显示的位数」与「泵能打出的最小步进」一致，用户不会看到 0.1 却不知道是 0.05 的倍数。
 */
internal fun formatDose(milliunits: Int, increment: Int): String =
    String.format(Locale.US, "%.${doseDecimals(increment)}f", milliunits / 1000.0)

private fun formatScaled(value: Int, scale: Int, increment: Int): String {
    if (scale == 1000) return formatDose(value, increment)
    // 其它字段（时长，scale=3600）仍按 3 位小数并裁掉尾 0。
    return String.format(Locale.US, "%.3f", value.toDouble() / scale)
        .trimEnd('0')
        .trimEnd('.')
}

private fun parseScaled(text: String, scale: Int): Int? =
    text.toDoubleOrNull()?.let { (it * scale).roundToInt() }
