// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

/** 单个电池（左耳 / 右耳 / 充电盒）的读数。 */
data class BatteryLevel(
    /** 0..100；精确到 1%（AAP）或 10%（BLE 广播）。 */
    val percent: Int,
    val charging: Boolean,
)

/** 三个电池的读数；null 表示当前不可用（例如耳机不在盒内时的充电盒）。 */
data class Battery(
    val left: BatteryLevel? = null,
    val right: BatteryLevel? = null,
    val case: BatteryLevel? = null,
    val source: Source = Source.NONE,
) {
    enum class Source { NONE, BLE, AAP }
}

/** 单只耳机的位置。 */
enum class PodPlacement(val label: String) {
    IN_EAR("佩戴中"),
    OUT_OF_EAR("未佩戴"),
    IN_CASE("在盒中"),
    UNKNOWN("未知");

    companion object {
        /** AAP 入耳包取值：0 入耳 / 1 出耳 / 2 在盒中。 */
        fun fromAap(v: Int): PodPlacement = when (v) {
            0 -> IN_EAR
            1 -> OUT_OF_EAR
            2 -> IN_CASE
            else -> UNKNOWN
        }
    }
}

/** 从耳机读到的设备信息（AAP 0x1D）。 */
data class DeviceInfo(
    val name: String = "",
    val modelNumber: String = "",
    val manufacturer: String = "",
    val serialNumber: String = "",
    val firmware: String = "",
    val leftSerial: String = "",
    val rightSerial: String = "",
) {
    val modelName: String
        get() = MODEL_NAMES.entries.firstOrNull { modelNumber in it.key }?.value ?: modelNumber

    companion object {
        private val MODEL_NAMES = mapOf(
            setOf("A2084", "A2083") to "AirPods Pro",
            setOf("A2931", "A2699", "A2698") to "AirPods Pro 2（Lightning）",
            setOf("A3047", "A3048", "A3049") to "AirPods Pro 2（USB-C）",
            setOf("A3063", "A3064", "A3065") to "AirPods Pro 3",
            setOf("A3056", "A3055", "A3057") to "AirPods 4（主动降噪）",
            setOf("A3053", "A3050", "A3054") to "AirPods 4",
        )
    }
}
