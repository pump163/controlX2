package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusAdvance
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusSource

/**
 * 四步输入页：入口选择 → 剂量与形状 → 校验与确认 → 结局。
 * 本轮不接发送：确认页「输注」只推进到结局页并显示「尚未接入（待 S6）」，不调用任何发送通道。
 * 草稿用 rememberSaveable + BolusFlowState.Saver：旋转恢复；返回上一格时模型值保留。
 */
@Composable
fun BolusFlowScreen(
    innerPadding: PaddingValues,
    onExit: () -> Unit,
) {
    var flow by rememberSaveable(stateSaver = BolusFlowState.Saver) { mutableStateOf(BolusFlowState()) }
    // CIQ 状态暂无 UI 可读来源，按 fail-closed 取 null（时长上限 2h）。
    val ciqEnabled: Boolean? = null
    val errors = if (flow.step == BolusFlowStep.SHAPE) flow.validationErrors(ciqEnabled) else emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.bolus_v2_step_of, flow.step.ordinal + 1, BolusFlowStep.values().size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(stepTitle(flow.step)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        when (flow.step) {
            BolusFlowStep.ENTRY -> BolusEntryPanel(
                onSelect = { source: BolusSource -> flow = flow.selectSource(source) },
            )

            BolusFlowStep.SHAPE -> BolusShapePanel(
                state = flow,
                errors = errors,
                ciqEnabled = ciqEnabled,
                onChange = { flow = it },
            )

            BolusFlowStep.CONFIRM -> BolusConfirmPanel(
                state = flow,
                onDeliver = { flow = deliver(flow, ciqEnabled) },
                onBack = { flow = flow.back() },
            )

            BolusFlowStep.RESULT -> Column(Modifier) {
                Text(
                    text = stringResource(R.string.bolus_v2_send_not_wired),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (flow.step == BolusFlowStep.ENTRY) {
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_back))
            }
        } else if (flow.step == BolusFlowStep.SHAPE) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { flow = flow.back() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_back))
                }
                Button(
                    onClick = {
                        when (val result = flow.advance(ciqEnabled)) {
                            is BolusAdvance.Ok -> flow = result.state
                            is BolusAdvance.Invalid -> Unit // 就地错误已展示，不前进
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.bolus_v2_next))
                }
            }
        } else if (flow.step == BolusFlowStep.RESULT) {
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bolus_v2_done))
            }
        }
    }
}

/** 确认 → 结局：只推进状态，不发送（S6 才接发送通道）。 */
private fun deliver(state: BolusFlowState, ciqEnabled: Boolean?): BolusFlowState =
    when (val result = state.advance(ciqEnabled)) {
        is BolusAdvance.Ok -> result.state
        is BolusAdvance.Invalid -> state
    }

private fun stepTitle(step: BolusFlowStep): Int = when (step) {
    BolusFlowStep.ENTRY -> R.string.bolus_v2_step_entry
    BolusFlowStep.SHAPE -> R.string.bolus_v2_step_shape
    BolusFlowStep.CONFIRM -> R.string.bolus_v2_step_confirm
    BolusFlowStep.RESULT -> R.string.bolus_v2_step_result
}
