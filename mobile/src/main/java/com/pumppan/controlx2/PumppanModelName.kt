package com.pumppan.controlx2

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * 型号展示名：t:slim X2 / Mobi。
 * 所有面向用户的型号文案都必须走这里，不能直接传 KnownDeviceModel（会显示 TSLIM_X2 枚举名）。
 * 未知型号回退到枚举名，仅作兜底。
 */
fun pumpModelDisplayName(context: Context, model: KnownDeviceModel?): String = when (model) {
    KnownDeviceModel.TSLIM_X2 -> context.getString(R.string.pumppan_pump_model_slimx2)
    KnownDeviceModel.MOBI -> context.getString(R.string.pumppan_pump_model_mobi)
    null -> "?"
    else -> model.name
}

@Composable
fun pumpModelDisplayName(model: KnownDeviceModel?): String =
    pumpModelDisplayName(LocalContext.current, model)
