package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource
import com.pumppan.controlx2.bolus.state.canStartRemoteExtended

/**
 * 第 3 步：校验与确认。
 * - 来源类型行按 [BolusSource] 显示（手工 / 预设 / 碳水计算）。
 * - 延展相关行只在能力允许远程延展时渲染（X2 不显示这些行）。
 * - 超过泵的 Max Bolus 设置时**不硬拦**，只给「返回 / 继续」两个选择。
 */
@Composable
fun BolusConfirmPanel(
    state: BolusFlowState,
    limits: BolusLimits = BolusLimits.Default,
    experimental: Boolean = false,
    overMaxBolus: Boolean = false,
    maxBolusText: String? = null,
    /** 发起前「已有进行中」提示（§4.9.9）：只提示，不阻断，也不禁用按钮。 */
    inProgressHint: String? = null,
    errorText: String? = null,
    deliverEnabled: Boolean = true,
    onDeliver: () -> Unit,
    onBack: () -> Unit,
    /** false = 按钮由页面底部的共享底栏渲染（固定在屏幕底部，键盘弹出时不遮挡）。 */
    showActions: Boolean = true,
) {
    Column(Modifier) {
        SummaryRow(stringResource(R.string.bolus_v2_confirm_mode), modeName(state))
        SummaryRow(stringResource(R.string.bolus_v2_confirm_source), sourceName(state.source))
        SummaryRow(stringResource(R.string.bolus_v2_confirm_total), units(state.totalMilliunits, limits))

        // 向导的建议值被改写过：把对照留在确认页，避免用户改完就忘了改了什么。
        val suggested = state.suggestedMilliunits
        if (suggested != null && suggested != state.totalMilliunits) {
            SummaryRow(
                stringResource(R.string.bolus_v2_confirm_suggested_vs_actual),
                stringResource(
                    R.string.bolus_v2_wizard_suggested_vs_actual,
                    units(suggested, limits),
                    units(state.totalMilliunits, limits),
                ),
            )
        }

        if (limits.canStartRemoteExtended() && state.shape != BolusShape.NORMAL) {
            // 绝对值为主；百分比只作次要信息（派生显示，不进协议）。
            val percent = BolusForm.percentOf(state.nowMilliunits, state.totalMilliunits)
            SummaryRow(
                stringResource(R.string.bolus_v2_confirm_immediate),
                stringResource(R.string.bolus_v2_units_with_percent, units(state.nowMilliunits, limits), percent),
            )
            SummaryRow(stringResource(R.string.bolus_v2_confirm_extended), units(state.laterMilliunits, limits))
            SummaryRow(
                stringResource(R.string.bolus_v2_confirm_duration),
                stringResource(R.string.bolus_v2_duration_value, state.extendedSeconds / 60),
            )
        }

        if (experimental) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bolus_v2_extended_experimental),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!inProgressHint.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = inProgressHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!errorText.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (overMaxBolus) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.bolus_v2_confirm_over_max,
                    maxBolusText ?: units(limits.maxBolusMilliunits, limits),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        if (showActions) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_back))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDeliver,
                    enabled = deliverEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (overMaxBolus) R.string.bolus_v2_confirm_continue else R.string.bolus_v2_send,
                        ),
                    )
                }
            }
        }
    }
}

/** 模式：立即 / 延长 / 双波。 */
@Composable
private fun modeName(state: BolusFlowState): String = when (state.shape) {
    BolusShape.NORMAL -> stringResource(R.string.bolus_v2_mode_standard)
    BolusShape.SQUARE -> stringResource(R.string.bolus_v2_mode_extended)
    BolusShape.DUAL -> stringResource(R.string.bolus_v2_mode_dual)
}

/** 剂量显示：与输入页一致的步进格式 + U 单位。 */
@Composable
private fun units(milliunits: Int, limits: BolusLimits): String =
    stringResource(
        R.string.bolus_v2_units_value,
        formatDose(milliunits, limits.bolusIncrementMilliunits),
    )

/** 标签 / 值两列摘要行。 */
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 来源类型：手工 / 预设 / 碳水计算。 */
@Composable
private fun sourceName(source: BolusSource): String = when (source) {
    BolusSource.MANUAL -> stringResource(R.string.bolus_v2_source_manual)
    BolusSource.PRESET -> stringResource(R.string.bolus_v2_source_preset)
    BolusSource.CALC -> stringResource(R.string.bolus_v2_source_calc)
}
