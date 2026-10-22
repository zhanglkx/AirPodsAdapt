// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import com.airpodsadapt.aap.AapProtocol.ControlId
import com.airpodsadapt.aap.ListeningMode

/** AAP 控制通道的状态。 */
enum class Link {
    /** 没有可连接的 AirPods（未配对 / 未连上音频）。 */
    IDLE,
    CONNECTING,
    CONNECTED,
    /** 重试用尽仍连不上，此时只显示 BLE 广播数据。 */
    FAILED,
}

/** 全局唯一的界面状态，由后台服务写入、界面/通知/磁贴读取。 */
data class AirPodsState(
    val bluetoothOn: Boolean = true,
    val deviceAddress: String? = null,
    /** 系统里的蓝牙设备名。 */
    val systemName: String? = null,
    /** 音频（A2DP/HFP）是否已连接。 */
    val audioConnected: Boolean = false,
    val link: Link = Link.IDLE,
    val linkError: String? = null,
    val battery: Battery = Battery(),
    /** AAP 入耳状态（主耳 / 副耳）。 */
    val primary: PodPlacement = PodPlacement.UNKNOWN,
    val secondary: PodPlacement = PodPlacement.UNKNOWN,
    /** BLE 广播里的左右耳佩戴状态。 */
    val bleLeftInEar: Boolean? = null,
    val bleRightInEar: Boolean? = null,
    val bleModel: String? = null,
    val lidOpen: Boolean? = null,
    /** 最近一次收到我方 AirPods BLE 广播的时间（elapsedRealtime）。 */
    val bleSeenAt: Long = 0,
    /** 耳机回报的各项控制命令当前值：标识 → 4 字节取值。 */
    val controls: Map<Int, List<Int>> = emptyMap(),
    val info: DeviceInfo? = null,
    val conversationLevel: Int? = null,
) {
    val connected: Boolean get() = link == Link.CONNECTED

    val displayName: String
        get() = info?.name?.takeIf { it.isNotBlank() } ?: systemName ?: bleModel ?: "AirPods"

    fun control(id: Int): Int? = controls[id]?.firstOrNull()

    /** 开关类控制：1 = 开，2 = 关，未知为 null。 */
    fun toggle(id: Int): Boolean? = when (control(id)) {
        1 -> true
        2 -> false
        else -> null
    }

    val listeningMode: ListeningMode? get() = control(ControlId.LISTENING_MODE)?.let(ListeningMode::fromValue)

    /** 已佩戴的耳机数量；AAP 优先，否则用 BLE，未知为 null。 */
    val podsInEar: Int?
        get() = when {
            connected && primary != PodPlacement.UNKNOWN ->
                listOf(primary, secondary).count { it == PodPlacement.IN_EAR }
            bleLeftInEar != null && bleRightInEar != null ->
                listOf(bleLeftInEar, bleRightInEar).count { it }
            else -> null
        }
}
