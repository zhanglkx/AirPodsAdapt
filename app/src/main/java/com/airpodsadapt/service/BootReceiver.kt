// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机、App 更新后，以及任意蓝牙设备连上时拉起后台服务（服务内部再判断是不是 AirPods）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothDevice.ACTION_ACL_CONNECTED -> AirPodsService.start(context)
        }
    }
}
