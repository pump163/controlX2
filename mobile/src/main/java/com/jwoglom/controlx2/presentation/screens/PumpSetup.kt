@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.navigation.NavHostController
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.builders.PumpChallengeRequestBuilder
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.presentation.components.DialogScreen
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.Line
import com.jwoglom.controlx2.presentation.components.PumpSetupStageDescription
import com.jwoglom.controlx2.presentation.components.PumpSetupStageProgress
import com.jwoglom.controlx2.presentation.components.ServiceDisabledMessage
import com.jwoglom.controlx2.presentation.components.input.LongPairingCodeInput
import com.jwoglom.controlx2.presentation.components.input.ShortPairingCodeInput
import com.jwoglom.controlx2.presentation.navigation.Screen
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.controlx2.shared.util.triggerAppReload
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.jwoglom.controlx2.shared.MessagePaths
import timber.log.Timber

@Composable
fun PumpSetup(
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    val setupStage = ds.pumpSetupStage.observeAsState()

    var pairingCodeText by remember { mutableStateOf(when (PumpState.getPairingCode(context)) {
        null -> ""
        else -> PumpState.getPairingCode(context)
    }) }

    var showAdvancedPairingSettings by remember { mutableStateOf(false) }
    var pumpStateStatus by remember { mutableStateOf<String?>(null) }
    var pumpStateJson by remember { mutableStateOf(PumpState.exportState(context)) }

    fun resetPumpFinder(context: Context) {
        Prefs(context).setPumpSetupComplete(false)
        Prefs(context).setPumpFinderPumpMac("")
        Prefs(context).setPumpFinderPairingCodeType("")
        Prefs(context).setPumpFinderServiceEnabled(true)
        PumpState.setPairingCode(context, "")
        PumpState.setJpakeDerivedSecret(context, "")
        PumpState.setJpakeServerNonce(context, "")
        PumpState.setSavedBluetoothMAC(context, "")
        coroutineScope.launch {
            delay(500)
            sendMessage(
                MessagePaths.TO_SERVER_APP_RELOAD,
                "".toByteArray()
            )
            delay(500)
            navController?.navigate(Screen.PumpSetup.route)
        }
    }
    DialogScreen(
        "胰岛素泵设置",
        actionContent = {
            IconButton(onClick = {
                showAdvancedPairingSettings = true
            }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                )
            }
        },
        buttonContent = {
            when (setupStage.value) {
                PumpSetupStage.PERMISSIONS_NOT_GRANTED -> {
                    Button(
                        onClick = {
                            sendMessage(MessagePaths.TO_SERVER_START_PUMP_FINDER, "skip_notif_permission".toByteArray())
                        }
                    ) {
                        Text("无权限继续")
                    }
                }
                else -> {
                    val resettableStages = setOf(
                        PumpSetupStage.PUMP_FINDER_SEARCHING_FOR_PUMPS,
                        PumpSetupStage.PUMP_FINDER_MOBI_PICK_UP_AND_TAP,
                        PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD,
                        PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE,
                        PumpSetupStage.WAITING_PUMPX2_INIT,
                        PumpSetupStage.PUMPX2_SEARCHING_FOR_PUMP,
                        PumpSetupStage.PUMPX2_PUMP_DISCOVERED,
                        PumpSetupStage.PUMPX2_INITIAL_PUMP_CONNECTION,
                        PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE,
                        PumpSetupStage.PUMPX2_PUMP_MODEL_METADATA,
                        PumpSetupStage.PUMPX2_SENDING_PAIRING_CODE,
                        PumpSetupStage.WAITING_PUMP_FINDER_CLEANUP
                    )
                    Button(
                        onClick = {
                            when (ds.pumpSetupStage.value) {
                                PumpSetupStage.WAITING_PUMP_FINDER_INIT -> {
                                    if (navController?.popBackStack() == false) {
                                        navController.navigate(Screen.FirstLaunch.route)
                                    }
                                    Prefs(context).setPumpSetupComplete(false)
                                }
                                else -> {
                                    if (ds.pumpSetupStage.value in resettableStages) {
                                        resetPumpFinder(context)
                                    } else {
                                        ds.pumpSetupStage.value = setupStage.value?.previousStage()
                                    }
                                }
                            }
                        }
                    ) {
                        if (ds.pumpSetupStage.value in resettableStages) {
                            Text("重置")
                        } else {
                            Text("返回")
                        }
                    }
                }
            }
            when (setupStage.value) {
                PumpSetupStage.PERMISSIONS_NOT_GRANTED -> {
                    Button(
                        onClick = {
                            sendMessage(MessagePaths.TO_SERVER_START_PUMP_FINDER, "".toByteArray())
                        }
                    ) {
                        Text("重试")
                    }
                }
                PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE,
                PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE,
                PumpSetupStage.PUMPX2_WAITING_FOR_PAIRING_CODE -> {
                    Button(
                        onClick = {
                            try {
                                Timber.i("enterPairingCode: $pairingCodeText ${ds.setupPairingCodeType.value} (${PumpState.getPairingCode(context)})")
                                val code = PumpChallengeRequestBuilder.processPairingCode(pairingCodeText, ds.setupPairingCodeType.value)
                                if (setupStage.value != PumpSetupStage.PUMPX2_WAITING_FOR_PAIRING_CODE) {
                                    ds.pumpSetupStage.value = setupStage.value!!.nextStage(PumpSetupStage.WAITING_PUMP_FINDER_CLEANUP)
                                }
                                sendMessage(MessagePaths.TO_SERVER_SET_PAIRING_CODE, code.toByteArray())
                            } catch (e: Exception) {
                                Timber.w("pairingCodeInput: $e")
                                Toast.makeText(context, e.toString().replaceBefore("$", "").substring(1), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("配对")
                    }
                }
                PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE -> {
                    Button(
                        onClick = {
                            sendMessage(MessagePaths.TO_SERVER_RESTART_PUMP_FINDER, "".toByteArray())
                        }
                    ) {
                        Text("重试")
                    }
                }
                PumpSetupStage.PUMPX2_PUMP_CONNECTED -> {
                    Button(
                        onClick = {
                            navController?.navigate(Screen.AppSetup.route)
                            Prefs(context).setPumpSetupComplete(true)
                        }
                    ) {
                        Text("下一步")
                    }
                }
                else -> {
                    /*
                    Button(
                        onClick = {
                            ds.setupStage.value = SetupStage.values()[setupStage.value!!.ordinal + 1]
                        }
                    ) {
                        Text("Advance for testing")
                    }
                    */
                }
            }
        }
    ) {
        item {
            ServiceDisabledMessage(sendMessage = sendMessage)
        }
        item {

            if (showAdvancedPairingSettings) {
                Popup(
                    onDismissRequest = { showAdvancedPairingSettings = false }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopStart)
                            .background(Color.DarkGray.copy(alpha = 0.7f))
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            item {
                                Spacer(Modifier.height(64.dp))
                            }

                            item {
                                HeaderLine("高级配对")
                            }

                            if (pumpStateStatus != null) {
                                item {
                                    Line("${pumpStateStatus}")
                                }
                            }

                            item {
                                TextField(
                                    value = pumpStateJson,
                                    label = { Text("泵状态") },
                                    onValueChange = {v -> pumpStateJson = v},
                                    modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.White)
                                )
                            }

                            item {
                                LazyRow {
                                    item {
                                        Button(onClick = {
                                            clipboardManager.getText()?.text?.let {
                                                pumpStateJson = it
                                            }
                                        }) {
                                            Text("从剪贴板读取")
                                        }
                                    }
                                    item {
                                        Spacer(Modifier.width(16.dp))
                                    }
                                    item {
                                        Button(onClick = {
                                            try {
                                                PumpState.importState(context, pumpStateJson)
                                                pumpStateStatus =
                                                    "成功，应用即将以新状态重新加载..."
                                                coroutineScope.launch {
                                                    delay(1000)
                                                    triggerAppReload(context)
                                                }

                                            } catch (e: Exception) {
                                                pumpStateStatus = "导入状态错误：${e}"
                                            }
                                        }) {
                                            Text("应用")
                                        }
                                    }
                                    item {
                                        Spacer(Modifier.width(16.dp))
                                    }
                                    item {
                                        Button(onClick = {
                                            showAdvancedPairingSettings = false
                                        }) {
                                            Text("取消")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
        item {
            PumpSetupStageProgress(initialSetup = true)
        }
        item {
            val setupDeviceName = ds.setupDeviceName.observeAsState()
            val setupPairingCodeType = ds.setupPairingCodeType.observeAsState()
            PumpSetupStageDescription(
                initialSetup = true,
                pairingCodeStage = {
                    if (setupStage.value == PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE) {
                        LaunchedEffect(Unit) {
                            pairingCodeText = ""
                            PumpState.setPairingCode(context, "")
                        }
                        Line(buildAnnotatedString {
                            withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) {
                                append("配对码无效。")
                            }
                            append("配对码输入错误或已超时。")
                        })
                        Line(buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append("t:slim X2：")
                            }
                            append("请确保胰岛素泵上已打开「配对设备」对话框。")
                        })
                    } else {
                        Line("正在连接 '${setupDeviceName.value}'")
                    }
                    Spacer(Modifier.height(16.dp))
                    when (ds.setupDeviceName.value?.let { determinePumpModel(it) }) {
                        KnownDeviceModel.TSLIM_X2 -> {
                            Line(buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("t:slim X2：")
                                }

                                append("请输入以下位置显示的配对码：")
                            })
                            Line("蓝牙设置 > 配对设备", bold = true)
                        }
                        KnownDeviceModel.MOBI -> {
                            Line(buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("Mobi：")
                                }
                                append("输入储药器附近的配对 PIN 码。")
                            })
                        }
                        else -> {}
                    }

                    Spacer(Modifier.height(32.dp))


                    when (setupPairingCodeType.value) {
                        PairingCodeType.LONG_16CHAR -> {
                            LongPairingCodeInput(
                                value = pairingCodeText,
                                onValueChange = {
                                    pairingCodeText = it
                                    Timber.i("newPairingCode(LONG_16CHAR): $it")
                                }
                            )
                        }
                        PairingCodeType.SHORT_6CHAR -> {
                            ShortPairingCodeInput(
                                value = pairingCodeText,
                                onValueChange = {
                                    pairingCodeText = it
                                    Timber.i("newPairingCode(SHORT_6CHAR): $it")
                                },
                                onClearSavedPin = {
                                    pairingCodeText = ""
                                    PumpState.setPairingCode(context, "")
                                }
                            )
                        }
                        null -> {}
                    }
                }
            )
        }
    }
}

enum class PumpSetupStage(val step: Int, val description: String) {
    PERMISSIONS_NOT_GRANTED(0, "权限未授予"),
    WAITING_PUMP_FINDER_INIT(1, "等待 PumpFinder 初始化"),
    PUMP_FINDER_SEARCHING_FOR_PUMPS(1, "正在搜索 Tandem 胰岛素泵"),
    PUMP_FINDER_SELECT_PUMP(2, "选择要连接的胰岛素泵"),

    PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE(3, "t:slim X2：选择配对码类型"),
    PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE(4, "t:slim X2：输入配对码"),

    PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD(3, "Mobi：将胰岛素泵放在充电座上"),
    PUMP_FINDER_MOBI_PICK_UP_AND_TAP(4, "Mobi：拿起并双击"),
    PUMP_FINDER_MOBI_ENTER_PAIRING_CODE(5, "Mobi：输入配对 PIN 码"),
    WAITING_PUMP_FINDER_CLEANUP(5, "正在建立连接：等待 PumpFinder 清理"),
    WAITING_PUMPX2_INIT(5, "正在建立连接：等待 PumpX2 初始化"),
    PUMPX2_SEARCHING_FOR_PUMP(5, "正在建立连接：正在搜索胰岛素泵"),
    PUMPX2_PUMP_DISCONNECTED(5, "胰岛素泵已断开，正在重新连接"),
    PUMPX2_PUMP_DISCOVERED(5, "正在建立连接：已发现胰岛素泵，正在连接"),
    PUMPX2_PUMP_MODEL_METADATA(5, "正在建立连接：已接收初始胰岛素泵元数据"),
    PUMPX2_INITIAL_PUMP_CONNECTION(6, "正在建立连接：初始连接已建立"),
    PUMPX2_WAITING_FOR_PAIRING_CODE(6, "正在建立连接：等待发送配对码"),
    PUMPX2_SENDING_PAIRING_CODE(6, "正在建立连接：正在发送配对码"),
    PUMPX2_INVALID_PAIRING_CODE(4, "配对码无效"),
    PUMPX2_PUMP_CONNECTED(7, "配对码已接受"),
    ;

    fun nextStage(stage: PumpSetupStage): PumpSetupStage {
        Timber.d("PumpSetupStage.nextStage(%s) from %s", stage, this)
        if (stage == PUMPX2_INVALID_PAIRING_CODE) {
            return stage
        }

        if (this == PUMPX2_SENDING_PAIRING_CODE && stage == PUMPX2_PUMP_DISCONNECTED) {
            return PUMPX2_INVALID_PAIRING_CODE
        }

        if (this == PUMPX2_INVALID_PAIRING_CODE && stage.ordinal < PUMPX2_INVALID_PAIRING_CODE.ordinal) {
            return this
        }

        if (stage == PUMPX2_PUMP_DISCONNECTED) {
            return stage
        }

        if (ordinal < stage.ordinal) {
            return stage
        }

        return this
    }

    fun previousStage(): PumpSetupStage {
        return when (this) {
            PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE -> PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE
            PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD -> PUMP_FINDER_SELECT_PUMP
            PUMP_FINDER_MOBI_PICK_UP_AND_TAP -> PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD
            PUMP_FINDER_MOBI_ENTER_PAIRING_CODE -> PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD
            else -> values().getOrElse(ordinal - 1) { this }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun PumpSetupDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            LocalDataStore.current.pumpSetupStage.value = PumpSetupStage.PUMPX2_WAITING_FOR_PAIRING_CODE
            PumpSetup(
                sendMessage = {_, _ -> },
            )
        }
    }
}
