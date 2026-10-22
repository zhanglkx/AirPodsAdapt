// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.aap

import com.airpodsadapt.aap.AapProtocol.Opcode
import com.airpodsadapt.core.Battery
import com.airpodsadapt.core.BatteryLevel
import com.airpodsadapt.core.DeviceInfo
import com.airpodsadapt.core.PodPlacement

/** 从耳机收到的一个 AAP 包解析后的结果。 */
sealed interface AapEvent {
    data class BatteryReport(val battery: Battery) : AapEvent

    /** 主/副耳的位置。主耳是当前负责麦克风的那只，左右会随佩戴情况交换。 */
    data class EarDetection(val primary: PodPlacement, val secondary: PodPlacement) : AapEvent

    /** 控制命令回报：耳机在订阅通知后会把各项当前设置逐条发过来，变更时也会发。 */
    data class Control(val id: Int, val values: List<Int>) : AapEvent

    /** 对话感知音量级别：1~2 开始说话（大幅降低音量），3~7 渐变，8~9 恢复正常。 */
    data class ConversationAwareness(val level: Int) : AapEvent

    data class Info(val info: DeviceInfo) : AapEvent

    data class Unknown(val opcode: Int) : AapEvent
}

object AapParser {

    /** 返回 null 表示不是业务包（例如握手回应）或包体不完整。 */
    fun parse(p: ByteArray): AapEvent? {
        if (p.size < 6 || p[0].u != 0x04 || p[1].u != 0x00 || p[2].u != 0x04 || p[3].u != 0x00) return null
        val opcode = p[4].u or (p[5].u shl 8)
        return when (opcode) {
            Opcode.BATTERY -> parseBattery(p)
            Opcode.EAR_DETECTION ->
                if (p.size >= 8) AapEvent.EarDetection(PodPlacement.fromAap(p[6].u), PodPlacement.fromAap(p[7].u)) else null
            Opcode.CONTROL ->
                if (p.size >= 11) AapEvent.Control(p[6].u, listOf(p[7].u, p[8].u, p[9].u, p[10].u)) else null
            Opcode.CONVERSATION_AWARENESS ->
                if (p.size >= 10) AapEvent.ConversationAwareness(p[9].u) else null
            Opcode.DEVICE_INFO -> parseInfo(p)
            else -> AapEvent.Unknown(opcode)
        }
    }

    /** `04 00 04 00 04 00 [count] ([component] 01 [level] [status] 01) × count` */
    private fun parseBattery(p: ByteArray): AapEvent? {
        if (p.size < 7) return null
        val count = p[6].u
        var left: BatteryLevel? = null
        var right: BatteryLevel? = null
        var case: BatteryLevel? = null
        for (i in 0 until count) {
            val off = 7 + i * 5
            if (off + 3 >= p.size) break
            val component = p[off].u
            val level = p[off + 2].u
            val status = p[off + 3].u
            // 状态：1 充电 / 2 放电 / 4 未连接 / 5 优化充电（暂停在 80%）
            if (status == STATUS_DISCONNECTED || level > 100) continue
            val reading = BatteryLevel(level, charging = status == STATUS_CHARGING || status == STATUS_OPTIMIZED)
            when (component) {
                COMPONENT_LEFT -> left = reading
                COMPONENT_RIGHT -> right = reading
                COMPONENT_CASE -> case = reading
            }
        }
        return AapEvent.BatteryReport(Battery(left, right, case, Battery.Source.AAP))
    }

    /**
     * 设备信息：从第 6 字节起是一串以 0x00 分隔的 UTF-8 字符串，
     * 前面有几个二进制字节、后面有加密数据，丢弃含控制字符的片段。
     */
    private fun parseInfo(p: ByteArray): AapEvent {
        val segments = mutableListOf<ByteArray>()
        var start = 6
        for (i in 6..p.size) {
            if (i == p.size || p[i].u == 0) {
                if (i > start) segments += p.copyOfRange(start, i)
                start = i + 1
            }
        }
        val strings = segments
            .dropWhile { seg -> seg.any { it.u < 0x20 } }
            .map { it.toString(Charsets.UTF_8) }
        return AapEvent.Info(
            DeviceInfo(
                name = strings.getOrElse(0) { "" },
                modelNumber = strings.getOrElse(1) { "" },
                manufacturer = strings.getOrElse(2) { "" },
                serialNumber = strings.getOrElse(3) { "" },
                firmware = strings.getOrElse(4) { "" },
                leftSerial = strings.getOrElse(8) { "" },
                rightSerial = strings.getOrElse(9) { "" },
            )
        )
    }

    private const val COMPONENT_LEFT = 0x04
    private const val COMPONENT_RIGHT = 0x02
    private const val COMPONENT_CASE = 0x08
    private const val STATUS_CHARGING = 1
    private const val STATUS_DISCONNECTED = 4
    private const val STATUS_OPTIMIZED = 5

    private val Byte.u: Int get() = toInt() and 0xFF
}
