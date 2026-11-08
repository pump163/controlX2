@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.components.DialogScreen

private enum class SelfCheckState { OK, MISSING, NOT_APPLICABLE }

private data class SelfCheckRow(
    val titleRes: Int,
    val hintRes: Int,
    val state: SelfCheckState,
)

private val OkColor = Color(0xFF4CAF50)

/**
 * 只读的「设置自检」清单。
 *
 * 三项原则：
 * 1) 只列程序能真实检测的项，检测不到的不列（厂商自启动/后台清理无公开 API）；
 * 2) 不提供任何跳转——厂商 ROM 页面组件名不稳定，跳错页会造出假绿灯；
 * 3) 每次进入和每次点「重新检测」都重新求值，不缓存结果（系统升级会重置白名单）。
 */
@Composable
fun SettingsSelfCheck(navController: NavHostController? = null) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val rows = remember(refreshToken) { evaluateSelfChecks(context) }

    DialogScreen(
        title = stringResource(R.string.settings_self_check),
        buttonContent = {
            TextButton(onClick = { refreshToken++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.self_check_refresh))
            }
            TextButton(onClick = { navController?.popBackStack() }) {
                Text(stringResource(R.string.self_check_back))
            }
        },
    ) {
        items(rows.size) { index ->
            val row = rows[index]
            ListItem(
                headlineContent = {
                    Text(stringResource(row.titleRes))
                },
                supportingContent = {
                    Text(
                        text = stringResource(row.hintRes),
                        color = if (row.state == SelfCheckState.MISSING) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = {
                    Text(
                        text = stringResource(
                            when (row.state) {
                                SelfCheckState.OK -> R.string.self_check_status_ok
                                SelfCheckState.MISSING -> R.string.self_check_status_missing
                                SelfCheckState.NOT_APPLICABLE -> R.string.self_check_status_not_applicable
                            }
                        ),
                        color = when (row.state) {
                            SelfCheckState.OK -> OkColor
                            SelfCheckState.MISSING -> MaterialTheme.colorScheme.error
                            SelfCheckState.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.self_check_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun evaluateSelfChecks(context: Context): List<SelfCheckRow> {
    val powerManager = context.getSystemService(PowerManager::class.java)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    val locationManager = context.getSystemService(LocationManager::class.java)

    val bluetoothOk = bluetoothManager?.adapter?.isEnabled == true
    val notificationOk = notificationManager?.areNotificationsEnabled() == true
    val batteryOk = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    val powerSaveOk = powerManager?.isPowerSaveMode == false

    // Android 11 及以下：BLE 扫描依赖位置权限与定位总开关。
    // Android 12 起改用 BLUETOOTH_SCAN，不再需要位置相关设置。
    val legacyScan = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
    val locationPermissionOk = legacyScan &&
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val locationSwitchOk = legacyScan && isLocationEnabled(locationManager)
    val locationHint = if (legacyScan) {
        R.string.self_check_hint_location
    } else {
        R.string.self_check_hint_location_not_applicable
    }

    return listOf(
        SelfCheckRow(
            R.string.self_check_item_bluetooth,
            R.string.self_check_hint_bluetooth,
            stateOf(bluetoothOk),
        ),
        SelfCheckRow(
            R.string.self_check_item_notification,
            R.string.self_check_hint_notification,
            stateOf(notificationOk),
        ),
        SelfCheckRow(
            R.string.self_check_item_battery,
            R.string.self_check_hint_battery,
            stateOf(batteryOk),
        ),
        SelfCheckRow(
            R.string.self_check_item_power_save,
            R.string.self_check_hint_power_save,
            stateOf(powerSaveOk),
        ),
        SelfCheckRow(
            R.string.self_check_item_location_permission,
            locationHint,
            if (legacyScan) stateOf(locationPermissionOk) else SelfCheckState.NOT_APPLICABLE,
        ),
        SelfCheckRow(
            R.string.self_check_item_location_switch,
            locationHint,
            if (legacyScan) stateOf(locationSwitchOk) else SelfCheckState.NOT_APPLICABLE,
        ),
    )
}

private fun stateOf(ok: Boolean): SelfCheckState =
    if (ok) SelfCheckState.OK else SelfCheckState.MISSING

private fun isLocationEnabled(locationManager: LocationManager?): Boolean {
    if (locationManager == null) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        locationManager.isLocationEnabled
    } else {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
