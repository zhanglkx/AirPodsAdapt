// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.aap

import android.os.ParcelUuid

/**
 * Apple Accessory Protocol（AAP）常量与发包构造。
 *
 * 所有业务包格式：`04 00 04 00 [opcode 低字节] [opcode 高字节] [data...]`。
 * 参考公开的逆向文档（LibrePods docs/AAP Definitions.md），本文件为独立实现。
 */
object AapProtocol {
    /** AAP 跑在经典蓝牙 L2CAP 上的 PSM（端口号）。 */
    const val PSM = 0x1001

    /** AirPods 在 SDP 中广告的 AAP 服务 UUID，用来识别已配对设备是否支持 AAP。 */
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

    private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    object Opcode {
        const val BATTERY = 0x04
        const val EAR_DETECTION = 0x06
        const val CONTROL = 0x09
        const val REQUEST_NOTIFICATIONS = 0x0F
        const val RENAME = 0x1A
        const val DEVICE_INFO = 0x1D
        const val CONVERSATION_AWARENESS = 0x4B
        const val FEATURE_FLAGS = 0x4D
    }

    /** 控制命令（opcode 0x09）的标识符。值语义见各字段注释。 */
    object ControlId {
        /** 麦克风：0 自动 / 1 右耳 / 2 左耳 */
        const val MIC_MODE = 0x01
        /** 耳机端自动入耳检测：1 开 / 2 关 */
        const val EAR_DETECTION = 0x0A
        /** 成为连接主控（争夺通知流与控制权）：1 拥有 / 0 不拥有 */
        const val OWNS_CONNECTION = 0x06
        /** 聆听模式：见 [ListeningMode] */
        const val LISTENING_MODE = 0x0D
        /** 按住动作：两个字节（右、左）1 = 噪声控制 / 5 = Siri */
        const val CLICK_HOLD_MODE = 0x16
        /** 按压速度：0 默认 / 1 较慢 / 2 最慢 */
        const val DOUBLE_CLICK_INTERVAL = 0x17
        /** 按住时长：0 默认 / 1 较短 / 2 最短（与 iPhone 设置项对应） */
        const val CLICK_HOLD_INTERVAL = 0x18
        /** 按住时循环的模式组合：位掩码，见 [ListeningMode.mask] */
        const val LISTENING_MODE_CONFIGS = 0x1A
        /** 单耳降噪：1 开 / 2 关 */
        const val ONE_BUD_ANC = 0x1B
        /** 音量轻扫间隔：0 默认 / 1 较长 / 2 最长 */
        const val VOLUME_SWIPE_INTERVAL = 0x23
        /** 音量轻扫：1 开 / 2 关 */
        const val VOLUME_SWIPE = 0x25
        /** 个性化音量：1 开 / 2 关 */
        const val ADAPTIVE_VOLUME = 0x26
        /** 对话感知：1 开 / 2 关 */
        const val CONVERSATION_DETECT = 0x28
        /** 自适应强度：0..100，越大越降噪 */
        const val ADAPTIVE_STRENGTH = 0x2E
        /** 允许“关闭”聆听模式：1 允许 / 2 不允许（新固件切到关闭前必须先允许） */
        const val ALLOW_OFF = 0x34
    }

    const val ON = 0x01
    const val OFF = 0x02

    /** 连接后必须首先发送的握手包，否则耳机不响应任何请求。 */
    fun handshake(): ByteArray =
        byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00) + ByteArray(8)

    /** 声明主机能力：开启播放时的对话感知、自适应通透等功能。 */
    fun featureFlags(): ByteArray =
        packet(Opcode.FEATURE_FLAGS, byteArrayOf(0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0))

    /** 订阅全部通知（电量、入耳、模式变化、对话感知等）。 */
    fun requestNotifications(): ByteArray =
        packet(Opcode.REQUEST_NOTIFICATIONS, byteArrayOf(-1, -1, -1, -1))

    /** 控制命令：固定 1 字节标识 + 4 字节数据，不足补 0。 */
    fun control(id: Int, vararg values: Int): ByteArray {
        require(values.size in 1..4) { "control command takes 1..4 values" }
        val data = ByteArray(5)
        data[0] = id.toByte()
        values.forEachIndexed { i, v -> data[1 + i] = v.toByte() }
        return packet(Opcode.CONTROL, data)
    }

    const val MAX_NAME_BYTES = 32

    /** 改名包：`1A 00 01 [len] 00 [UTF-8 name]`。 */
    fun rename(name: String): ByteArray {
        val bytes = name.trim().toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_NAME_BYTES) { "name must be 1..$MAX_NAME_BYTES bytes" }
        return packet(Opcode.RENAME, byteArrayOf(0x01, bytes.size.toByte(), 0x00) + bytes)
    }

    fun packet(opcode: Int, data: ByteArray): ByteArray =
        HEADER + byteArrayOf((opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte()) + data

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}

/** 聆听模式。[value] 为 0x0D 控制命令的取值，[mask] 为 0x1A 按住循环配置的位。 */
enum class ListeningMode(val value: Int, val mask: Int, val label: String) {
    OFF(1, 0x01, "关闭"),
    ANC(2, 0x02, "降噪"),
    TRANSPARENCY(3, 0x04, "通透"),
    ADAPTIVE(4, 0x08, "自适应");

    companion object {
        fun fromValue(v: Int): ListeningMode? = entries.firstOrNull { it.value == v }
    }
}
