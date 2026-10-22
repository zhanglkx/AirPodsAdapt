// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 排查连接问题需要的系统信息。 */
object SystemInfo {
    const val XIAOMI_BT_PACKAGE = "com.xiaomi.bluetooth"
    const val ADB_DISABLE_XIAOMI_BT = "adb shell pm disable-user --user 0 $XIAOMI_BT_PACKAGE"
    const val ADB_ENABLE_XIAOMI_BT = "adb shell pm enable $XIAOMI_BT_PACKAGE"

    enum class PackageState(val label: String) {
        NOT_INSTALLED("未安装"),
        ENABLED("已启用"),
        DISABLED("已停用"),
    }

    fun xiaomiBluetoothState(context: Context): PackageState = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                XIAOMI_BT_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(XIAOMI_BT_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        if (info.enabled) PackageState.ENABLED else PackageState.DISABLED
    } catch (e: PackageManager.NameNotFoundException) {
        PackageState.NOT_INSTALLED
    }

    fun describe(context: Context): String = buildString {
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
        appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("系统版本：${Build.DISPLAY}")
        appendLine("增量版本：${Build.VERSION.INCREMENTAL}")
        appendLine("安全补丁：${Build.VERSION.SECURITY_PATCH}")
        append("$XIAOMI_BT_PACKAGE：${xiaomiBluetoothState(context).label}")
    }
}
