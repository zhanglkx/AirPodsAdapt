// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.ble

import com.airpodsadapt.core.Battery
import com.airpodsadapt.core.BatteryLevel

/** 一条 AirPods BLE 广播（Apple Proximity Pairing 消息，类型 0x07）解析结果。 */
data class ProximityStatus(
    val address: String,
    val rssi: Int,
    val modelId: Int,
    val battery: Battery,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidOpen: Boolean,
    val timestamp: Long,
) {
    val modelName: String get() = MODEL_NAMES[modelId] ?: "AirPods (0x%04X)".format(modelId)

    companion object {
        val MODEL_NAMES = mapOf(
            0x0220 to "AirPods",
            0x0F20 to "AirPods 2",
            0x1320 to "AirPods 3",
            0x1920 to "AirPods 4",
            0x1B20 to "AirPods 4（主动降噪）",
            0x0E20 to "AirPods Pro",
            0x1420 to "AirPods Pro 2",
            0x2420 to "AirPods Pro 2（USB-C）",
            0x0A20 to "AirPods Max",
            0x1F20 to "AirPods Max（USB-C）",
        )
    }
}

/**
 * 解析未加密部分。广播里的电量是 4 bit 一档（0~10 档），所以精度只有 10%。
 *
 * 字节布局（不含 Apple 厂商 ID）：
 * [0]=0x07 类型 [1]=长度 [3..4]=型号 [5]=状态位 [6]=左右耳电量 [7]=充电位+盒电量 [8]=开盖计数位
 */
object ProximityParser {
    const val APPLE_COMPANY_ID = 76
    const val TYPE_PROXIMITY_PAIRING = 0x07
    const val LENGTH = 0x19

    fun parse(address: String, rssi: Int, data: ByteArray, now: Long): ProximityStatus? {
        if (data.size < 11 || data[0].u != TYPE_PROXIMITY_PAIRING || data[1].u != LENGTH) return null
        val modelId = (data[3].u shl 8) or data[4].u
        val status = data[5].u
        val pods = data[6].u
        val flagsCase = data[7].u
        val lid = data[8].u

        // 主耳是左耳时 bit5=1；广播方（发广播的那只耳机）在盒中时 bit6=1。两者决定左右字段是否对调。
        val primaryLeft = (status shr 5) and 1 == 1
        val thisInCase = (status shr 6) and 1 == 1
        val swapEar = primaryLeft xor thisInCase
        val leftInEar = if (swapEar) status and 0x08 != 0 else status and 0x02 != 0
        val rightInEar = if (swapEar) status and 0x02 != 0 else status and 0x08 != 0

        val flipped = !primaryLeft
        val leftNibble = if (flipped) pods shr 4 and 0x0F else pods and 0x0F
        val rightNibble = if (flipped) pods and 0x0F else pods shr 4 and 0x0F
        val caseNibble = flagsCase and 0x0F
        val flags = flagsCase shr 4 and 0x0F
        val leftCharging = if (flipped) flags and 0x02 != 0 else flags and 0x01 != 0
        val rightCharging = if (flipped) flags and 0x01 != 0 else flags and 0x02 != 0
        val caseCharging = flags and 0x04 != 0

        return ProximityStatus(
            address = address,
            rssi = rssi,
            modelId = modelId,
            battery = Battery(
                left = level(leftNibble, leftCharging),
                right = level(rightNibble, rightCharging),
                case = level(caseNibble, caseCharging),
                source = Battery.Source.BLE,
            ),
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            lidOpen = (lid shr 3) and 1 == 0,
            timestamp = now,
        )
    }

    /** 0x0~0x9 → 0~90%，0xA~0xE → 100%，0xF → 不可用。 */
    private fun level(n: Int, charging: Boolean): BatteryLevel? = when (n) {
        in 0x0..0x9 -> BatteryLevel(n * 10, charging)
        in 0xA..0xE -> BatteryLevel(100, charging)
        else -> null
    }

    private val Byte.u: Int get() = toInt() and 0xFF
}
