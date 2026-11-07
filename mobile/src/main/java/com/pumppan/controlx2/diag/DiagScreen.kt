package com.pumppan.controlx2.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.shared.diag.DiagMonitor
import kotlinx.coroutines.delay

/**
 * DIAG-TEMP：断连临时监控页。用完删除整个包与 Landing/Debug 中的入口。
 */
@Composable
fun DiagScreen(
    innerPadding: PaddingValues = PaddingValues(),
    navigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick += 1
        }
    }

    val snap = remember(tick) { DiagMonitor.snapshot() }
    val events = remember(tick) { DiagMonitor.recentEvents(40) }

    LazyColumn(
        contentPadding = innerPadding,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = navigateBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text("断连监控（临时）", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("只在调试页可见；用完即删", fontSize = 12.sp)
                }
            }
            HorizontalDivider()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text("采集开关", fontWeight = FontWeight.SemiBold)
                    Text("关闭时不占线程、不写盘", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = snap.enabled,
                    onCheckedChange = { DiagMonitor.setEnabled(context, it) },
                )
            }
            HorizontalDivider()
        }

        item { StateCard(snap) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { DiagMonitor.freezeNow("MANUAL") }, enabled = snap.enabled) {
                    Text("冻结当前")
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(DiagMonitor.exportSummary()))
                    },
                    enabled = snap.enabled,
                ) {
                    Text("复制摘要")
                }
            }
            HorizontalDivider()
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("取回", fontWeight = FontWeight.SemiBold)
                Text(
                    "HTTP（Basic auth）：\n" +
                        "GET /api/diag/state\n" +
                        "GET /api/diag/files\n" +
                        "GET /api/diag/incident/latest\n" +
                        "GET /api/diag/live?tail=500",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("目录：${snap.dirPath.ifEmpty { "files/diag（尚未初始化）" }}", fontSize = 12.sp)
            }
            HorizontalDivider()
        }

        item {
            Text(
                "最近事件",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        if (events.isEmpty()) {
            item {
                Text(
                    "暂无事件（未开启采集或刚开始）",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        } else {
            items(events.reversed()) { e ->
                Text(
                    text = "${
                        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                            .format(java.util.Date(e.wallMs))
                    }  ${e.kind}" +
                        (e.source?.let { " [${it}]" } ?: "") +
                        (e.detail?.let { "  ${it.take(180)}" } ?: ""),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun StateCard(snap: DiagMonitor.DiagSnapshot) {
    val (label, color) = when (snap.linkState) {
        "LINK_ALIVE" -> "链路活：已连接且有真实回包" to MaterialTheme.colorScheme.primary
        "LINK_SILENT" -> "链路在但没数据：已连接，长时间无真实回包" to MaterialTheme.colorScheme.tertiary
        "DISCONNECTED" -> "已断开" to MaterialTheme.colorScheme.error
        "NO_LINK" -> "从未连上" to MaterialTheme.colorScheme.outline
        else -> "采集未启用" to MaterialTheme.colorScheme.outline
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
                Text(
                    "进程 pid=${snap.pid} 存活 ${fmtDuration(snap.processAgeMs)}  " +
                        "(会话 ${snap.sessionId})",
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        KeyValue("连接", if (snap.connected) "已连接 ${fmtAgo(snap.connectedForMs)}" else "未连接")
        KeyValue(
            "最近断开",
            if (snap.lastDisconnectAgoMs != null) {
                "${fmtAgo(snap.lastDisconnectAgoMs)}前  status=${snap.lastDisconnectStatus ?: "null"}  src=${snap.lastDisconnectSource ?: "?"}"
            } else "本进程内无断开记录",
        )
        KeyValue("真实 BLE 收", fmtAgo(snap.lastRxAgoMs))
        KeyValue("真实 BLE 发", fmtAgo(snap.lastTxAgoMs))
        KeyValue("心跳", fmtAgo(snap.heartbeatAgoMs))
        KeyValue("前台服务", if (snap.fgsRunning) "在跑" else "未运行")
        KeyValue(
            "系统态",
            "屏幕=${if (snap.screenOn) "亮" else "灭"} Doze=${snap.deviceIdle} " +
                "省电=${snap.powerSave} 白名单=${snap.batteryWhitelisted} 电量=${snap.batteryPct ?: "?"}%",
        )
        KeyValue("深睡累计", fmtDuration(snap.deepSleepMs) + "（elapsed-uptime）")
        KeyValue("最近一分钟", "rx=${snap.rxLastMinute} tx=${snap.txLastMinute}")
        KeyValue(
            "流量来源",
            snap.txBySourceLastMinute.entries.joinToString(" ") { "${it.key}=${it.value}" }
                .ifEmpty { "无" },
        )
        KeyValue(
            "bolus 1s 轮询",
            "近10秒 ${snap.bolusPollLast10s} 次（≈${snap.bolusPollLast10s / 10.0} 条循环）",
        )
        KeyValue(
            "写失败",
            snap.writeFailures.entries.joinToString(" ") { "${it.key}×${it.value}" }.ifEmpty { "无" },
        )
        KeyValue("快照文件", "${snap.incidentCount} 份 / live ${snap.liveBytes} bytes")
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(key, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun fmtAgo(ms: Long?): String {
    if (ms == null || ms < 0) return "无记录"
    return if (ms < 1_000) "${ms}ms" else fmtDuration(ms)
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1_000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${s % 60}s"
        else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}
