// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package com.airpodsadapt.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.airpodsadapt.aap.AapProtocol
import com.airpodsadapt.aap.AapProtocol.ControlId
import com.airpodsadapt.aap.ListeningMode
import com.airpodsadapt.core.AirPodsHub
import com.airpodsadapt.core.AirPodsState
import com.airpodsadapt.core.AppPrefs
import com.airpodsadapt.core.AppSettings
import com.airpodsadapt.core.Battery
import com.airpodsadapt.core.Link
import com.airpodsadapt.core.PodPlacement
import com.airpodsadapt.core.SystemInfo
import kotlin.math.roundToInt

/** 界面上的模式顺序与 iPhone 一致。 */
private val MODE_ORDER = listOf(ListeningMode.ANC, ListeningMode.ADAPTIVE, ListeningMode.TRANSPARENCY, ListeningMode.OFF)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun StatusCard(state: AirPodsState) {
    SectionCard(title = state.displayName) {
        val model = state.info?.modelName?.takeIf { it.isNotBlank() } ?: state.bleModel
        if (model != null) Hint(model)
        Text(statusText(state), color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BatteryGauge("左耳", state.battery.left)
            BatteryGauge("右耳", state.battery.right)
            BatteryGauge(if (state.battery.caseIsLastKnown) "充电盒（上次）" else "充电盒", state.battery.case)
        }

        val ear = when {
            state.connected && state.primary != PodPlacement.UNKNOWN ->
                "主耳：${state.primary.label} · 副耳：${state.secondary.label}"
            state.bleLeftInEar != null && state.bleRightInEar != null ->
                "左耳：${if (state.bleLeftInEar) "佩戴中" else "未佩戴"} · 右耳：${if (state.bleRightInEar) "佩戴中" else "未佩戴"}"
            else -> null
        }
        val lid = state.lidOpen?.let { if (it) "充电盒：已打开" else "充电盒：已合上" }
        listOfNotNull(ear, lid).takeIf { it.isNotEmpty() }?.let { Hint(it.joinToString(" · ")) }
        if (state.battery.source == Battery.Source.BLE) Hint("电量来自蓝牙广播，精度为 10%")
        if (state.battery.caseIsLastKnown) {
            Hint("充电盒电量为${lastSeenText(state.battery.caseSavedAt)}的读数，耳机放回盒中后更新")
        }

        if (state.audioConnected && !state.connected && state.link != Link.CONNECTING) {
            Button(onClick = AirPodsHub::reconnect, modifier = Modifier.fillMaxWidth()) { Text("重新建立控制通道") }
        }
    }
}

/** “刚刚” / “5分钟前” / “3小时前”……使用系统本地化的相对时间。 */
private fun lastSeenText(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) "刚刚" else
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private fun statusText(s: AirPodsState): String = when {
    !s.bluetoothOn -> "蓝牙已关闭"
    s.connected -> "已连接 · 全部功能可用"
    s.link == Link.CONNECTING -> "正在建立控制通道…" + (s.linkError?.let { "（上次失败：$it）" } ?: "")
    s.audioConnected && s.link == Link.FAILED -> "音频已连接 · 控制通道不可用"
    s.audioConnected -> "音频已连接"
    s.bleSeenAt != 0L -> "在附近，但未连接到这台手机"
    else -> "未连接。请先在系统蓝牙中连接 AirPods"
}

@Composable
fun TroubleshootCard(state: AirPodsState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var xiaomiState by remember { mutableStateOf(SystemInfo.xiaomiBluetoothState(context)) }
    LifecycleResumeEffect(Unit) {
        xiaomiState = SystemInfo.xiaomiBluetoothState(context)
        onPauseOrDispose { }
    }

    SectionCard("无法建立控制通道") {
        state.linkError?.let { Text("原因：$it", color = MaterialTheme.colorScheme.error) }
        Text(
            "降噪切换、对话感知等功能需要和耳机建立一条专用的蓝牙数据通道（L2CAP）。常见原因：\n" +
                "① 系统蓝牙未包含 Google 2026 年 3 月的修复（Android 17 起都包含；当前为 Android ${Build.VERSION.RELEASE}）；\n" +
                "② 澎湃 OS 自带的 ${SystemInfo.XIAOMI_BT_PACKAGE} 抢先占用了这条通道。"
        )
        Text("${SystemInfo.XIAOMI_BT_PACKAGE}：${xiaomiState.label}")
        if (xiaomiState == SystemInfo.PackageState.ENABLED) {
            Hint(
                "可以在电脑上执行下面的命令停用它，然后在系统蓝牙中“忽略此设备”并重新配对 AirPods。" +
                    "代价：澎湃 OS 自带的 AirPods 开盖弹窗、控制中心降噪切换会失效。随时可用恢复命令还原。"
            )
            Text(SystemInfo.ADB_DISABLE_XIAOMI_BT, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SystemInfo.ADB_DISABLE_XIAOMI_BT)) }) { Text("复制停用命令") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SystemInfo.ADB_ENABLE_XIAOMI_BT)) }) { Text("复制恢复命令") }
            }
            TextButton(onClick = { openAppDetails(context, SystemInfo.XIAOMI_BT_PACKAGE) }) { Text("打开该组件的应用信息") }
        }
        Button(onClick = AirPodsHub::reconnect, modifier = Modifier.fillMaxWidth()) { Text("重试") }
    }
}

@Composable
fun ListeningModeCard(state: AirPodsState) {
    SectionCard("聆听模式") {
        val current = state.listeningMode
        ChoiceRow(
            title = null,
            options = MODE_ORDER.map { it.label },
            selected = MODE_ORDER.indexOf(current).takeIf { it >= 0 },
            enabled = state.connected,
        ) { AirPodsHub.setListeningMode(MODE_ORDER[it]) }
        if (!state.connected) Hint("建立控制通道后可用")

        if (current == ListeningMode.ADAPTIVE) {
            val remote = state.control(ControlId.ADAPTIVE_STRENGTH) ?: 50
            var value by remember(remote) { mutableFloatStateOf(remote.toFloat()) }
            Text("自适应强度：${value.roundToInt()}")
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { AirPodsHub.setControl(ControlId.ADAPTIVE_STRENGTH, value.roundToInt()) },
                valueRange = 0f..100f,
                enabled = state.connected,
                modifier = Modifier.semantics { contentDescription = "自适应强度" },
            )
            Row(Modifier.fillMaxWidth()) {
                Hint("更多环境声")
                Spacer(Modifier.weight(1f))
                Hint("更少环境声")
            }
        }
    }
}

@Composable
fun HeadphoneFeaturesCard(state: AirPodsState) {
    val items = listOf(
        Triple(ControlId.CONVERSATION_DETECT, "对话感知", "你开始说话时，降低媒体音量并让你听清面前的人"),
        Triple(ControlId.ADAPTIVE_VOLUME, "个性化音量", "根据周围环境自动调节媒体音量"),
        Triple(ControlId.EAR_DETECTION, "自动入耳检测", "关闭后，摘下耳机自动暂停也会失效"),
        Triple(ControlId.ONE_BUD_ANC, "单耳降噪", "只戴一只耳机时也能使用降噪"),
        Triple(ControlId.VOLUME_SWIPE, "音量轻扫", "在耳机柄上下轻扫调节音量"),
        Triple(ControlId.ALLOW_OFF, "允许“关闭”模式", "在聆听模式中提供“关闭”选项"),
    )
    SectionCard("耳机设置") {
        for ((id, title, subtitle) in items) {
            SwitchRow(title, subtitle, checked = state.toggle(id) == true, enabled = state.connected) {
                AirPodsHub.setToggle(id, it)
            }
        }
    }
}

@Composable
fun PressAndHoldCard(state: AirPodsState) {
    SectionCard("按压耳机柄") {
        Text("按住时循环切换的模式（至少两项）", style = MaterialTheme.typography.bodyLarge)
        val mask = state.control(ControlId.LISTENING_MODE_CONFIGS)
            ?: (ListeningMode.ANC.mask or ListeningMode.TRANSPARENCY.mask)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in MODE_ORDER) {
                FilterChip(
                    selected = mask and mode.mask != 0,
                    enabled = state.connected,
                    onClick = {
                        val next = mask xor mode.mask
                        if (Integer.bitCount(next) >= 2) {
                            if (mode == ListeningMode.OFF && next and mode.mask != 0) {
                                AirPodsHub.setToggle(ControlId.ALLOW_OFF, true)
                            }
                            AirPodsHub.setControl(ControlId.LISTENING_MODE_CONFIGS, next)
                        }
                    },
                    label = { Text(mode.label) },
                )
            }
        }
        ChoiceRow("按压速度", listOf("默认", "较慢", "最慢"), state.control(ControlId.DOUBLE_CLICK_INTERVAL), state.connected) {
            AirPodsHub.setControl(ControlId.DOUBLE_CLICK_INTERVAL, it)
        }
        ChoiceRow("按住时长", listOf("默认", "较短", "最短"), state.control(ControlId.CLICK_HOLD_INTERVAL), state.connected) {
            AirPodsHub.setControl(ControlId.CLICK_HOLD_INTERVAL, it)
        }
        ChoiceRow("音量轻扫间隔", listOf("默认", "较长", "最长"), state.control(ControlId.VOLUME_SWIPE_INTERVAL), state.connected) {
            AirPodsHub.setControl(ControlId.VOLUME_SWIPE_INTERVAL, it)
        }
    }
}

@Composable
fun MicrophoneCard(state: AirPodsState) {
    SectionCard("麦克风") {
        ChoiceRow(null, listOf("自动切换", "始终右耳", "始终左耳"), state.control(ControlId.MIC_MODE), state.connected) {
            AirPodsHub.setControl(ControlId.MIC_MODE, it)
        }
    }
}

@Composable
fun PhoneFeaturesCard(prefs: AppPrefs) {
    SectionCard("手机端功能") {
        SwitchRow("摘下耳机自动暂停", "摘下一只或两只耳机时暂停播放，戴回后继续", prefs.autoPause, true, AppSettings::setAutoPause)
        SwitchRow("对话时降低媒体音量", "配合“对话感知”：你说话时把媒体音量降到 30%", prefs.conversationDucking, true, AppSettings::setConversationDucking)
        SwitchRow("开盖弹出电量", "打开充电盒时用通知弹出电量", prefs.lidPopup, true, AppSettings::setLidPopup)
    }
}

@Composable
fun DeviceInfoCard(state: AirPodsState) {
    var renaming by remember { mutableStateOf(false) }
    SectionCard("名称与设备信息") {
        Row(Modifier.fillMaxWidth()) {
            Text(state.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { renaming = true }, enabled = state.connected) { Text("改名") }
        }
        val info = state.info
        if (info == null) {
            Hint("建立控制通道后显示型号、序列号和固件版本")
        } else {
            InfoRow("型号", info.modelName)
            InfoRow("型号编号", info.modelNumber)
            InfoRow("序列号", info.serialNumber)
            InfoRow("固件版本", info.firmware)
            InfoRow("左耳序列号", info.leftSerial)
            InfoRow("右耳序列号", info.rightSerial)
        }
    }
    if (renaming) {
        RenameDialog(initial = state.displayName, onDismiss = { renaming = false }) {
            AirPodsHub.rename(it)
            renaming = false
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val bytes = text.trim().toByteArray(Charsets.UTF_8).size
    val valid = bytes in 1..AapProtocol.MAX_NAME_BYTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名 AirPods") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("名称") },
                    isError = !valid,
                    supportingText = { Text("$bytes / ${AapProtocol.MAX_NAME_BYTES} 字节（一个汉字占 3 字节）") },
                )
                Hint("耳机里的名称会立即更新；手机蓝牙列表里的名称需要在系统蓝牙设置中修改，或重新配对后更新。")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BackgroundCard() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        ignoring = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    SectionCard("后台运行") {
        Text("澎湃 OS 会清理后台应用。为了自动连接和自动暂停一直可用，请：")
        Text("① 允许本 App 忽略电池优化" + if (ignoring) "（已允许）" else "")
        Text("② 在应用信息中开启“自启动”，并把“省电策略”设为“无限制”")
        if (!ignoring) {
            Button(onClick = { requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("允许忽略电池优化")
            }
        }
        OutlinedButton(onClick = { openAppDetails(context, context.packageName) }, modifier = Modifier.fillMaxWidth()) {
            Text("打开应用信息")
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openAppDetails(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        AirPodsHub.log("无法打开 $packageName 的应用信息：${e.message}")
    }
}
