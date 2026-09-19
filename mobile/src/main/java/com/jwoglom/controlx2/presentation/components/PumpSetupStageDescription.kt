@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)
package com.jwoglom.controlx2.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.presentation.screens.PumpSetupStage
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType
import timber.log.Timber

const val TroubleshootingStepsThresholdSeconds = 15

@Composable
fun PumpSetupStageDescription(
    initialSetup: Boolean = false,
    pairingCodeStage: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val coroutineScope = rememberCoroutineScope()

    val setupStage = ds.pumpSetupStage.observeAsState()
    val pumpFinderPumps = ds.pumpFinderPumps.observeAsState()
    val setupDeviceName = ds.setupDeviceName.observeAsState()
    val pumpReadyState = ds.pumpReadyState.observeAsState()
    val setupDeviceModel = ds.setupDeviceModel.observeAsState()
    val pumpCriticalError = ds.pumpCriticalError.observeAsState()

    var pumpConnectionWaitingSeconds by remember { mutableStateOf(0) }

    LaunchedEffect (intervalOf(1)) {
        if (setupStage.value == PumpSetupStage.PUMPX2_PUMP_CONNECTED) {
            if (pumpConnectionWaitingSeconds > 0) {
                Timber.d("PumpSetupStageProgress pumpConnectionWaitingSeconds=$pumpConnectionWaitingSeconds")
                pumpConnectionWaitingSeconds = 0
            }
        } else {
            pumpConnectionWaitingSeconds += 1
            if (pumpConnectionWaitingSeconds % 20 == 0) {
                Timber.d("PumpSetupStageProgress pumpConnectionWaitingSeconds=$pumpConnectionWaitingSeconds")
            }
        }
    }

    when (setupStage.value) {
        PumpSetupStage.PERMISSIONS_NOT_GRANTED -> {
            Line("未授予通知权限，远程大剂量功能需要此权限。")
        }
        PumpSetupStage.WAITING_PUMP_FINDER_INIT -> {
            if (Prefs(context).pumpFinderServiceEnabled()) {
                Line("正在等待 PumpFinder 库初始化...")
            }
        }
        PumpSetupStage.PUMP_FINDER_SEARCHING_FOR_PUMPS, PumpSetupStage.PUMPX2_SEARCHING_FOR_PUMP -> {
            if (initialSetup) {
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append("对于 t:slim X2：")
                    }
                    append("打开您的胰岛素泵并选择：")
                })
                Line("选项 > 设备设置 > 蓝牙设置", bold = true)
                Line("启用'移动连接'选项并点击'配对设备'。如果已配对，请先点击'取消配对'。")
                Spacer(Modifier.height(16.dp))
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append("对于 Mobi：")
                    }
                    append("将胰岛素泵放在无线充电器上。")
                })
                Line("确保 Mobi 已开机并正在充电。")
                Line("将 Mobi 从充电器取下再放回。")
                Line("请使用 USB-A 转 USB-C 线缆。")
            } else {
                Line("正在搜索胰岛素泵...")
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append("对于 t:slim X2：")
                    }
                    append("如果您的胰岛素泵未显示，请打开它并选择：")
                })
                Line("选项 > 设备设置 > 蓝牙设置", bold = true)
                Line("确保'移动连接'选项已启用。")
            }
        }
        PumpSetupStage.PUMP_FINDER_SELECT_PUMP -> {
            Line("选择要连接的胰岛素泵：")
            Line("")
            pumpFinderPumps.value?.forEach {
                Button(
                    onClick = {
                        Prefs(context).setPumpFinderPumpMac(it.second)
                        val nextStage = when (determinePumpModel(it.first)) {
                            KnownDeviceModel.MOBI -> PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD
                            else -> PumpSetupStage.PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE
                        }
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(nextStage)
                        ds.setupDeviceName.value = it.first
                    }
                ) {
                    Text("${it.first} (MAC: ${it.second})")
                }
                Line("")
            }
            Line("")
        }
        PumpSetupStage.PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE -> {
            Line("选择正确的配对码类型：")
            Line("")
            Button(
                onClick = {
                    Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.LONG_16CHAR.label)
                    ds.setupPairingCodeType.value = PairingCodeType.LONG_16CHAR
                    ds.pumpSetupStage.value =
                        ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE)
                }
            ) {
                Text("长码：16 位字母数字字符")
            }

            Line("")

            Button(
                onClick = {
                    Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                    ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                    ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE)
                }
            ) {
                Text("短码：6 位数字")
            }

            Line("")
            when (ds.setupDeviceName.value?.let { determinePumpModel(it) }) {
                KnownDeviceModel.TSLIM_X2 -> {
                    Line(buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append("对于 t:slim X2：")
                        }
                        append("现在请打开胰岛素泵上蓝牙设置 > 配对码中生成的配对码。")
                    })
                }
                else -> {}
            }
        }
        PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD -> {
            Line(buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append("对于 Mobi：")
                }
                append("将您的 Mobi 放在充电板上。")
            })
            Line("确保它已开机并正在充电。")

            LaunchedEffect(pumpReadyState.value) {
                when {
                    pumpReadyState.value?.shouldPickUpAndTap() == true -> {
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_PICK_UP_AND_TAP)
                    }
                    pumpReadyState.value?.shouldEnterPinCode() == true -> {
                        Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                        ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE)
                    }
                    else -> {}
                }
            }
        }
        PumpSetupStage.PUMP_FINDER_MOBI_PICK_UP_AND_TAP -> {
            Line(buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append("对于 Mobi：")
                }
                append("已检测到您的胰岛素泵。")
            })
            Line("拿起胰岛素泵，稍等一秒，然后双击 T 按钮。")

            LaunchedEffect(pumpReadyState.value) {
                when {
                    pumpReadyState.value?.shouldPlaceOnChargingPad() == true -> {
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD)
                    }
                    pumpReadyState.value?.shouldEnterPinCode() == true -> {
                        Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                        ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE)
                    }
                    else -> {}
                }
            }
        }
        PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE,
        PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE,
        PumpSetupStage.PUMPX2_WAITING_FOR_PAIRING_CODE,
        PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE -> {
            if (initialSetup) {
                pairingCodeStage()
            } else {
                if (setupStage.value == PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE) {
                    Line(buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append("配对码无效。")
                        }
                        append("配对码输入错误或已超时。请确保胰岛素泵上的'配对设备'对话框已打开。")
                        if (initialSetup) {
                            withStyle(
                                style = SpanStyle(
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append("\n\n要解决此问题，请点击下方的重试按钮并输入正确的配对码。")
                            }
                        } else {
                            withStyle(
                                style = SpanStyle(
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append("\n\n要解决此问题，您必须在设置 > 重新配置胰岛素泵中重新配对应用。")
                            }
                        }
                    })
                } else {
                    Line("已与 ${setupDeviceName.value} 建立初始连接，正在尝试配对...")
                }
            }
        }
        PumpSetupStage.WAITING_PUMPX2_INIT -> {
            if (Prefs(context).serviceEnabled()) {
                Line("正在等待库初始化...")
            }
        }
        PumpSetupStage.PUMPX2_PUMP_DISCONNECTED -> {
            Line("已断开与'${setupDeviceName.value}'的连接，正在重新连接...")
        }
        PumpSetupStage.PUMPX2_PUMP_DISCOVERED -> {
            Line("正在连接 ${setupDeviceName.value}")
        }
        PumpSetupStage.PUMPX2_PUMP_MODEL_METADATA -> {
            Line("正在连接 ${setupDeviceName.value}（${setupDeviceModel.value}）")
        }
        PumpSetupStage.PUMPX2_INITIAL_PUMP_CONNECTION -> {
            Line("已与 ${setupDeviceName.value} 建立初始连接")
        }
        PumpSetupStage.PUMPX2_PUMP_CONNECTED -> {
            if (initialSetup) {
                Line("已连接到 ${setupDeviceName.value}！", bold = true)
                Spacer(modifier = Modifier.height(16.dp))
                Line("点击'下一步'继续。")
            }
        }
        else -> {}
    }

    if (setupStage.value != PumpSetupStage.PUMPX2_PUMP_CONNECTED) {
        if (pumpConnectionWaitingSeconds > TroubleshootingStepsThresholdSeconds) {
            Spacer(Modifier.height(16.dp))
            Line("故障排除步骤：", bold = true)
            when (setupStage.value) {
                PumpSetupStage.WAITING_PUMPX2_INIT -> {
                    if (!Prefs(context).serviceEnabled()) {
                        Line("0. 启用 ControlX2 服务（设置 > 启用 ControlX2 服务）")
                    }
                    Line("1. 开关一次蓝牙。")
                    Line("2. 重启 ControlX2 应用：打开应用切换器，长按应用图标打开应用信息页面，然后点击'强制停止'，再点击'打开'")
                    Line("3. 确保 ControlX2 应用具有足够权限：在 ControlX2 的应用信息页面，确保蓝牙/已连接设备相关权限已授予")
                    Line("4. 如果仍然无法工作，请在应用信息页面点击'清除数据'，这将重置应用设置")
                }
                else -> {
                    Line("1. 开关一次蓝牙。")
                    Line("2. 如果 t:connect Android 应用已打开，请强制停止：长按应用，选择应用信息，然后点击'强制停止'")
                }
            }
        }
        if (pumpCriticalError.value != null) {
            Spacer(Modifier.height(16.dp))
            Line("连接错误${pumpCriticalError.value?.second?.let { " ${shortTimeAgo(it)}" }}：", bold = true)
            Line("${pumpCriticalError.value?.first}")
        }
        Spacer(Modifier.height(16.dp))
        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        Spacer(Modifier.height(16.dp))
    }
}
