package com.pumppan.controlx2.bolus

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.BolusStatusKind
import com.pumppan.controlx2.bolus.state.BolusStatusPresentation
import com.pumppan.controlx2.bolus.state.CancelRowState

/**
 * 状态文案的唯一映射：三个位置都调这里，禁止各自再写一份 `when`。
 * 容量差异（卡片多行 / 细条单行 / 段二一句话）只体现在排版，不体现在措辞。
 */
@Composable
internal fun bolusStatusText(presentation: BolusStatusPresentation): String =
    when (presentation.kind) {
        BolusStatusKind.IDLE -> stringResource(R.string.bolus_v2_status_idle)
        BolusStatusKind.PENDING -> stringResource(R.string.bolus_v2_page_pending)
        BolusStatusKind.DELIVERING -> stringResource(R.string.bolus_v2_page_delivering)
        BolusStatusKind.EXTENDED -> stringResource(R.string.bolus_v2_page_extended_running)
        BolusStatusKind.CANCELLING -> stringResource(R.string.bolus_v2_page_cancelling)
        BolusStatusKind.UNKNOWN -> stringResource(R.string.bolus_v2_page_unknown)
        BolusStatusKind.FINISHED_SUCCESS,
        BolusStatusKind.FINISHED_CANCELLED,
        BolusStatusKind.FINISHED_FAILED -> bolusOutcomeText(presentation.outcome)
    }

/** 结局文案（完成 / 取消 / 失败），完成页与空闲页共用同一个槽位。 */
@Composable
internal fun bolusOutcomeText(outcome: BolusOutcome): String = when (outcome) {
    is BolusOutcome.Cancelled -> stringResource(R.string.bolus_v2_outcome_cancelled)
    is BolusOutcome.Failed ->
        if (outcome.fromCancel) stringResource(R.string.bolus_v2_outcome_failed_cancel)
        else failureText(outcome.error)

    else -> stringResource(R.string.bolus_v2_outcome_success)
}

/**
 * 取消不可点时的原因（取消行五态与细条共用）。
 * READY（可点）与 HIDDEN（不展示取消）返回 null，由调用方决定是否补一句「进行中」。
 */
@Composable
internal fun bolusCancelBlockedReason(
    row: CancelRowState,
    deliveryState: BolusDeliveryState?,
    connected: Boolean,
): String? = when (row) {
    CancelRowState.RECONCILING -> stringResource(R.string.bolus_v2_cancel_reason_reconciling)
    CancelRowState.NEEDS_ID -> stringResource(R.string.bolus_v2_cancel_reason_needs_id)
    CancelRowState.READ_ONLY ->
        when {
            !connected -> stringResource(R.string.bolus_v2_cancel_reason_disconnected)
            deliveryState == BolusDeliveryState.UNKNOWN ->
                stringResource(R.string.bolus_v2_cancel_reason_unknown)

            else -> stringResource(R.string.bolus_v2_cancel_reason_disconnected)
        }

    CancelRowState.READY,
    CancelRowState.HIDDEN,
    -> null
}
