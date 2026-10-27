// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 按耳机蓝牙地址持久化最后一次实时读到的充电盒电量。
 *
 * AAP 只在至少一只耳机在盒内时上报盒子电量（否则状态为“未连接”）。耳机戴上后、
 * App 重启后、或耳机重新连接时，就靠这里的值补全显示。只写入 AAP 的读数：
 * BLE 广播无法确认是不是自己的耳机，写进来可能把别人的电量当成自己的。
 */
object CaseBatteryCache {
    private const val FILE = "case_battery"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun save(address: String, percent: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit {
            putInt(keyPercent(address), percent)
            putLong(keyAt(address), System.currentTimeMillis())
        }
    }

    /** 返回 (电量, 读数时刻)；没有记录时返回 null。 */
    fun load(address: String): Pair<BatteryLevel, Long>? {
        if (!::prefs.isInitialized) return null
        val percent = prefs.getInt(keyPercent(address), -1)
        val at = prefs.getLong(keyAt(address), 0)
        if (percent !in 0..100 || at == 0L) return null
        // 充电状态是瞬时信息，过期后不再可信，一律显示为未充电
        return BatteryLevel(percent, charging = false) to at
    }

    private fun keyPercent(address: String) = "$address.percent"
    private fun keyAt(address: String) = "$address.at"
}
