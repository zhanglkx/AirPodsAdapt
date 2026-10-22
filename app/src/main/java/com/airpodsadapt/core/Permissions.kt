// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    /** 首次启动需要申请的运行时权限。 */
    val required: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canConnect(context: Context) = has(context, Manifest.permission.BLUETOOTH_CONNECT)
    fun canScan(context: Context) = has(context, Manifest.permission.BLUETOOTH_SCAN)
}
