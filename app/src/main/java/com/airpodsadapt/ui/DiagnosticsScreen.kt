// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package com.airpodsadapt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.airpodsadapt.aap.AapProtocol
import com.airpodsadapt.core.AirPodsHub
import com.airpodsadapt.core.AirPodsState
import com.airpodsadapt.core.SystemInfo

@Composable
fun DiagnosticsScreen(state: AirPodsState, logs: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val system = remember { SystemInfo.describe(context) }
    val summary = stateSummary(state)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard("系统") { Text(system, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                SectionCard("当前状态") { Text(summary, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString("$system\n\n$summary\n\n" + logs.joinToString("\n")))
                    }) { Text("复制全部") }
                    OutlinedButton(onClick = AirPodsHub::clearLogs) { Text("清空日志") }
                    OutlinedButton(onClick = AirPodsHub::reconnect) { Text("重连") }
                }
            }
            item { Text("日志（最新在上）", style = MaterialTheme.typography.titleSmall) }
            items(logs.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun stateSummary(s: AirPodsState): String = buildString {
    appendLine("蓝牙：${if (s.bluetoothOn) "开" else "关"}  音频连接：${if (s.audioConnected) "是" else "否"}")
    appendLine("控制通道：${s.link}${s.linkError?.let { "（$it）" } ?: ""}")
    appendLine("设备：${s.systemName ?: "—"}  ${s.deviceAddress ?: ""}")
    appendLine("电量来源：${s.battery.source}")
    append("控制项：")
    append(
        s.controls.entries.sortedBy { it.key }.joinToString(" ") { (id, v) ->
            "%02X=%s".format(id, AapProtocol.toHex(v.map { it.toByte() }.toByteArray()))
        }.ifEmpty { "—" }
    )
}
