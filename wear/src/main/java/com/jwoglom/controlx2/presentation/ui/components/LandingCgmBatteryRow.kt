package com.jwoglom.controlx2.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.presentation.components.LineInfoChip
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun LandingCgmBatteryRow() {
    val ds = LocalDataStore.current
    val status = ds.cgmTransmitterStatus.observeAsState().value
    LineInfoChip(stringResource(R.string.dashboard_cgm_battery), status?.toString() ?: "?")
}

@Preview
@OptIn(ExperimentalWearMaterialApi::class)
@Composable
private fun LandingCgmBatteryRowPreview() {
    LandingCgmBatteryRow()
}
