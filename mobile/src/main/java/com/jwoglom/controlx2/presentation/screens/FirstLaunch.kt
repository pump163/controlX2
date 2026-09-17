package com.jwoglom.controlx2.presentation.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.presentation.components.DialogScreen
import com.jwoglom.controlx2.presentation.navigation.Screen
import com.jwoglom.controlx2.presentation.screens.sections.components.VersionInfo
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import kotlin.system.exitProcess

@Composable
fun FirstLaunch(
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
) {
    val context = LocalContext.current

    DialogScreen(
        "健康与安全警告",
        buttonContent = {
            Button(
                onClick = {
                    Prefs(context).setTosAccepted(false)
                    exitProcess(0)
                }
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    navController?.navigate(Screen.PumpSetup.route)
                    Prefs(context).setTosAccepted(true)
                    Prefs(context).setServiceEnabled(true)
                    Prefs(context).setPumpFinderServiceEnabled(true)
                    sendMessage(MessagePaths.TO_SERVER_START_PUMP_FINDER, "".toByteArray())
                }
            ) {
                Text("同意")
            }
        }
    ) {
        item {
            Text(
                text = """
                            本应用仅供实验用途，可用于修改胰岛素泵的活跃胰岛素输送。

                            本应用与 Tandem、Dexcom 或任何其他制造商均无隶属关系，亦未获得其支持。本应用未经过官方批准，仅作为研究工具提供。

                            使用本软件不承担任何明示或暗示的保证。对于任何故障、错误或胰岛素输送操作，您自行承担全部风险。
                        """.trimIndent(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
            )
        }
        item {
            Text("")
        }
        item {
            VersionInfo(context)
        }
    }
}


@Preview(showBackground = true)
@Composable
internal fun FirstLaunchDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            FirstLaunch(
                sendMessage = {_, _ -> },
            )
        }
    }
}