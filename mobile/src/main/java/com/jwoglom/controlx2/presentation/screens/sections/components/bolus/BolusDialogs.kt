package com.jwoglom.controlx2.presentation.screens.sections.components.bolus

import android.content.Context
import android.os.Handler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.snakeCaseToSpace
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces1000Unit
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.builders.LastBolusStatusRequestBuilder
import com.jwoglom.pumpx2.pump.messages.calculator.BolusCalcUnits
import com.jwoglom.pumpx2.pump.messages.calculator.BolusParameters
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownApiVersion
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.BolusCalcDataSnapshotResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.TimeSinceResetResponse
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun BolusDeliverActionRegion(
    refreshing: Boolean,
    bolusButtonEnabled: Boolean,
    bolusUnits: Double?,
    onPerformPermissionCheck: () -> Unit,
    onPrepareFinalParameters: () -> Unit,
    isValidBolus: () -> Boolean,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Button(
                onClick = {
                    if (isValidBolus()) {
                        onPrepareFinalParameters()
                        onPerformPermissionCheck()
                    }
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                enabled = bolusButtonEnabled,
                colors = if (isSystemInDarkTheme()) {
                    ButtonDefaults.filledTonalButtonColors(containerColor = Color.LightGray)
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Image(
                    painterResource(R.drawable.bolus_icon),
                    "大剂量图标",
                    Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    "输注 ${bolusUnits?.let { "${twoDecimalPlaces(it)}u " }}大剂量",
                    fontSize = 18.sp,
                    color = if (isSystemInDarkTheme()) Color.Black else Color.Unspecified
                )
            }
        }
    }
}

@Composable
fun BolusPermissionDialogRegion(
    showPermissionCheckDialog: Boolean,
    onDismiss: () -> Unit,
    onShowInProgress: () -> Unit,
    sendServiceBolusRequest: (Int, BolusParameters, BolusCalcUnits, BolusCalcDataSnapshotResponse, TimeSinceResetResponse) -> Unit,
) {
    if (!showPermissionCheckDialog) {
        return
    }
    val dataStore = LocalDataStore.current
    val bolusCurrentParameters = dataStore.bolusCurrentParameters.observeAsState()
    val bolusFinalParameters = dataStore.bolusFinalParameters.observeAsState()
    val bolusPermissionResponse = dataStore.bolusPermissionResponse.observeAsState()

    fun sendBolusRequest(
        bolusParameters: BolusParameters?,
        unitBreakdown: BolusCalcUnits?,
        dataSnapshot: BolusCalcDataSnapshotResponse?,
        timeSinceReset: TimeSinceResetResponse?
    ) {
        if (bolusParameters == null || dataStore.bolusPermissionResponse.value == null || dataStore.bolusCalcDataSnapshot.value == null || unitBreakdown == null || dataSnapshot == null || timeSinceReset == null) {
            Timber.w("sendBolusRequest: null parameters")
            return
        }

        val bolusId = dataStore.bolusPermissionResponse.value!!.bolusId

        Timber.i("sendBolusRequest: sending bolus request to phone: bolusId=$bolusId bolusParameters=$bolusParameters unitBreakdown=$unitBreakdown dataSnapshot=$dataSnapshot timeSinceReset=$timeSinceReset")
        sendServiceBolusRequest(bolusId, bolusParameters, unitBreakdown, dataSnapshot, timeSinceReset)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("输注 ${bolusCurrentParameters.value?.units?.let { "${twoDecimalPlaces(it)}u " }}大剂量？")
        },
        icon = {
            Image(
                if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon_secondary)
                else painterResource(R.drawable.bolus_icon),
                "大剂量图标",
                Modifier.size(ButtonDefaults.IconSize)
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    bolusFinalParameters.value?.let { finalParameters ->
                        bolusPermissionResponse.value?.let { permissionResponse ->
                            if (permissionResponse.isPermissionGranted && finalParameters.units >= 0.05) {
                                onShowInProgress()
                                sendBolusRequest(
                                    dataStore.bolusFinalParameters.value,
                                    dataStore.bolusFinalCalcUnits.value,
                                    dataStore.bolusCalcDataSnapshot.value,
                                    dataStore.timeSinceResetResponse.value,
                                )
                            }
                        }
                    }
                },
                enabled = (
                    bolusPermissionResponse.value?.isPermissionGranted == true &&
                        bolusFinalParameters.value?.takeIf { it.units >= 0.05 } != null
                    )
            ) {
                Text("输注")
            }
        }
    )
}

@Composable
fun InProgressDialogRegion(
    onShowApproved: () -> Unit,
    onShowCancelled: () -> Unit,
    onCancel: () -> Unit,
    context: Context,
) {
    val dataStore = LocalDataStore.current
    val bolusInitiateResponse = dataStore.bolusInitiateResponse.observeAsState()
    val bolusCancelResponse = dataStore.bolusCancelResponse.observeAsState()
    val bolusCurrentParameters = dataStore.bolusCurrentParameters.observeAsState()
    val bolusFinalParameters = dataStore.bolusFinalParameters.observeAsState()

    val bolusMinNotifyThreshold = Prefs(context).bolusConfirmationInsulinThreshold()

    LaunchedEffect(bolusInitiateResponse.value) {
        if (bolusInitiateResponse.value != null) {
            onShowApproved()
        }
    }

    LaunchedEffect(bolusCancelResponse.value) {
        if (bolusCancelResponse.value != null) {
            onShowCancelled()
        }
    }

    AlertDialog(
        onDismissRequest = {},
        icon = {
            Image(
                if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon_secondary)
                else painterResource(R.drawable.bolus_icon),
                "大剂量图标",
                Modifier.size(ButtonDefaults.IconSize)
            )
        },
        title = {
            Text("正在请求 ${bolusCurrentParameters.value?.units?.let { "${twoDecimalPlaces(it)}u " }}大剂量")
        },
        text = {
            Text(
                when {
                    bolusInitiateResponse.value != null -> "泵已收到大剂量请求，等待响应..."
                    bolusFinalParameters.value != null -> when {
                        bolusFinalParameters.value!!.units >= bolusMinNotifyThreshold -> "已发送通知以批准该请求。"
                        else -> "正在向泵发送请求..."
                    }
                    else -> "正在向泵发送请求..."
                }
            )
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("取消大剂量输注")
            }
        },
        confirmButton = {}
    )
}

@Composable
fun ApprovedDialogRegion(
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    refreshScopeLaunch: (suspend () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val dataStore = LocalDataStore.current
    val bolusInitiateResponse = dataStore.bolusInitiateResponse.observeAsState()
    val bolusCurrentResponse = dataStore.bolusCurrentResponse.observeAsState()
    val bolusFinalParameters = dataStore.bolusFinalParameters.observeAsState()

    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Image(
                if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon_secondary)
                else painterResource(R.drawable.bolus_icon),
                "大剂量图标",
                Modifier.size(ButtonDefaults.IconSize)
            )
        },
        title = {
            Text(
                when {
                    bolusInitiateResponse.value != null -> when {
                        bolusInitiateResponse.value!!.wasBolusInitiated() -> "大剂量已启动"
                        else -> "大剂量被泵拒绝"
                    }
                    else -> "正在获取大剂量状态..."
                }
            )
        },
        text = {
            LaunchedEffect(Unit) {
                sendPumpCommands(SendType.BUST_CACHE, listOf(CurrentBolusStatusRequest()))
                refreshScopeLaunch {
                    var requestCount = 0
                    while (requestCount < 60) {
                        delay(1000)
                        val currentResponse = dataStore.bolusCurrentResponse.value
                        if (currentResponse?.bolusId == 0) {
                            Timber.d("BolusWindow: bolusId is 0, stopping status polling")
                            break
                        }
                        sendPumpCommands(SendType.BUST_CACHE, listOf(CurrentBolusStatusRequest()))
                        requestCount++
                    }
                }
            }

            LaunchedEffect(bolusCurrentResponse.value) {
                Timber.i("bolusCurrentResponse: ${bolusCurrentResponse.value}")
                if (bolusCurrentResponse.value?.bolusId != 0) {
                    refreshScopeLaunch {
                        repeat(5) {
                            delay(1000)
                            val currentResponse = dataStore.bolusCurrentResponse.value
                            if (currentResponse?.bolusId == 0) {
                                Timber.d("BolusWindow: bolusId is 0, stopping status polling")
                                return@repeat
                            }
                            sendPumpCommands(SendType.BUST_CACHE, listOf(CurrentBolusStatusRequest()))
                        }
                    }
                }
            }

            Text(
                text = when {
                    bolusInitiateResponse.value != null -> when {
                        bolusInitiateResponse.value!!.wasBolusInitiated() -> "${bolusFinalParameters.value?.let { twoDecimalPlaces(it.units) }}u 大剂量 ${
                            when (bolusCurrentResponse.value) {
                                null -> "已请求。"
                                else -> when (bolusCurrentResponse.value!!.status) {
                                    CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING -> "正在准备。"
                                    CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING -> "正在输注。"
                                    else -> "已完成。"
                                }
                            }
                        }"
                        else -> "大剂量无法输注：${
                            bolusInitiateResponse.value?.let { snakeCaseToSpace(it.statusType.toString()) }
                        }"
                    }
                    else -> "大剂量状态未知。请检查您的胰岛素泵以确认大剂量状态。"
                }
            )
        },
        dismissButton = {
            val bolusStillActive = bolusCurrentResponse.value?.let {
                it.bolusId != 0 && (it.status == CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING ||
                    it.status == CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING)
            } ?: false
            if (bolusStillActive) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("取消大剂量输注")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    if (bolusCurrentResponse.value?.bolusId == 0 ||
                        bolusCurrentResponse.value?.status?.let {
                            it != CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING &&
                            it != CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING
                        } == true
                    ) "完成" else "确定"
                )
            }
        }
    )
}

@Composable
fun CancellingDialogRegion(
    onShowCancelled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dataStore = LocalDataStore.current
    val bolusCancelResponse = dataStore.bolusCancelResponse.observeAsState()

    LaunchedEffect(bolusCancelResponse.value) {
        if (bolusCancelResponse.value != null) {
            onShowCancelled()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon_secondary)
                else painterResource(R.drawable.bolus_icon),
                "大剂量图标",
                Modifier.size(ButtonDefaults.IconSize)
            )
        },
        title = {
            Text("正在取消..")
        },
        text = {
            Text("正在取消大剂量...")
        },
        confirmButton = {}
    )
}

@Composable
fun CancelledDialogRegion(
    mainHandler: Handler,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    onDismiss: () -> Unit,
) {
    val dataStore = LocalDataStore.current
    val bolusCancelResponse = dataStore.bolusCancelResponse.observeAsState()
    val bolusInitiateResponse = dataStore.bolusInitiateResponse.observeAsState()
    val lastBolusStatusResponse = dataStore.lastBolusStatusResponse.observeAsState()

    fun apiVersion(): ApiVersion = PumpState.getPumpAPIVersion() ?: KnownApiVersion.API_V2_5.get()
    fun lastBolusStatusRequest(): Message = LastBolusStatusRequestBuilder.create(apiVersion())

    LaunchedEffect (bolusCancelResponse.value, Unit) {
        Timber.d("showCancelledDialog querying LastBolusStatus")
        sendPumpCommands(SendType.STANDARD, listOf(lastBolusStatusRequest()))
        mainHandler.postDelayed({
            sendPumpCommands(SendType.STANDARD, listOf(lastBolusStatusRequest()))
        }, 500)
    }

    fun matchesBolusId(): Boolean? {
        lastBolusStatusResponse.value?.let { last ->
            bolusInitiateResponse.value?.let { initiate ->
                return (last.bolusId == initiate.bolusId)
            }
        }
        return null
    }

    LaunchedEffect (lastBolusStatusResponse.value) {
        Timber.d("showCancelledDialog lastBolusStatusResponse effect: ${matchesBolusId()}")
        if (matchesBolusId() == false) {
            mainHandler.postDelayed({
                Timber.d("showCancelledDialog lastBolusStatus postDelayed")
                sendPumpCommands(
                    SendType.STANDARD,
                    listOf(lastBolusStatusRequest())
                )
            }, 500)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                if (isSystemInDarkTheme()) painterResource(R.drawable.bolus_icon_secondary)
                else painterResource(R.drawable.bolus_icon),
                "大剂量图标",
                Modifier.size(ButtonDefaults.IconSize)
            )
        },
        title = {
            Text(when (bolusCancelResponse.value?.status) {
                CancelBolusResponse.CancelStatus.SUCCESS ->
                    "大剂量已取消"
                CancelBolusResponse.CancelStatus.FAILED ->
                    when (bolusInitiateResponse.value) {
                        null -> "大剂量已取消"
                        else -> "无法取消"
                    }
                else -> "大剂量状态未知"
            })
        },
        text = {
            Text(
                "${when (bolusCancelResponse.value?.status) {
                    CancelBolusResponse.CancelStatus.SUCCESS ->
                        "大剂量已取消。"
                    CancelBolusResponse.CancelStatus.FAILED ->
                        when (bolusInitiateResponse.value) {
                            null -> "未向泵发送大剂量请求，因此没有可取消的内容。"
                            else -> "大剂量无法取消：${
                                snakeCaseToSpace(
                                    bolusCancelResponse.value?.reason.toString()
                                )
                            }"
                        }
                    else -> "请检查您的胰岛素泵以确认大剂量是否已取消。"
                }}\n\n${when {
                    matchesBolusId() == true ->
                        lastBolusStatusResponse.value?.deliveredVolume?.let {
                            if (it == 0L) "大剂量已启动，但未输注胰岛素。" else "${twoDecimalPlaces1000Unit(it)}u 已输注。"
                        } ?: ""
                    matchesBolusId() == false -> "未输注胰岛素。"
                    else -> "正在检查是否已输注胰岛素..."
                }}"
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}
