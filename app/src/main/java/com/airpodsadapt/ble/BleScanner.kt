// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import androidx.annotation.RequiresPermission

/**
 * 扫描 AirPods 的 BLE 广播。使用硬件过滤（只要 Apple 厂商数据且类型为 0x07），
 * 熄屏后系统仍允许带过滤条件的扫描，耗电较低。
 */
class BleScanner(
    private val adapter: BluetoothAdapter,
    private val log: (String) -> Unit,
    private val onStatus: (ProximityStatus) -> Unit,
) {
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun start() {
        if (callback != null || !adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                ProximityParser.APPLE_COMPANY_ID,
                byteArrayOf(ProximityParser.TYPE_PROXIMITY_PAIRING.toByte(), ProximityParser.LENGTH.toByte()),
                byteArrayOf(-1, -1),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                log("BLE 扫描失败，错误码 $errorCode")
                callback = null
            }
        }
        try {
            scanner.startScan(listOf(filter), settings, cb)
            callback = cb
            log("BLE 扫描已启动")
        } catch (e: IllegalStateException) {
            log("BLE 扫描启动失败：${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            adapter.bluetoothLeScanner?.stopScan(cb)
            log("BLE 扫描已停止")
        } catch (e: IllegalStateException) {
            log("BLE 扫描停止失败：${e.message}")
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID) ?: return
        val status = ProximityParser.parse(result.device.address, result.rssi, data, SystemClock.elapsedRealtime())
            ?: return
        onStatus(status)
    }
}
