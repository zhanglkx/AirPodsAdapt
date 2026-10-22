// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.aap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 创建经典蓝牙（BR/EDR）L2CAP socket。
 *
 * 公开 API 只能建 RFCOMM 和 LE 的 L2CAP，AirPods 的 AAP 用的是经典蓝牙 L2CAP，
 * 只能通过反射调用 `BluetoothSocket` 的非公开构造函数。不同 Android 版本签名不同，依次尝试。
 */
object L2capSocketFactory {
    private const val TYPE_L2CAP = 3

    private class Signature(val label: String, val types: Array<Class<*>>, val args: (Ctx) -> Array<Any?>)
    private class Ctx(val adapter: BluetoothAdapter, val device: BluetoothDevice, val psm: Int, val uuid: ParcelUuid)

    private val INT = Int::class.javaPrimitiveType!!
    private val BOOL = Boolean::class.javaPrimitiveType!!
    private val ADAPTER = BluetoothAdapter::class.java
    private val DEVICE = BluetoothDevice::class.java
    private val UUID = ParcelUuid::class.java

    private val signatures = listOf(
        // Android 16 QPR3 及之后（含 L2CAP 修复的版本）
        Signature("(adapter, device, type, auth, encrypt, port, uuid)", arrayOf(ADAPTER, DEVICE, INT, BOOL, BOOL, INT, UUID)) {
            arrayOf(it.adapter, it.device, TYPE_L2CAP, true, true, it.psm, it.uuid)
        },
        // Android 12 ~ 16
        Signature("(type, auth, encrypt, device, port, uuid)", arrayOf(INT, BOOL, BOOL, DEVICE, INT, UUID)) {
            arrayOf(TYPE_L2CAP, true, true, it.device, it.psm, it.uuid)
        },
        Signature("(device, type, auth, encrypt, port, uuid)", arrayOf(DEVICE, INT, BOOL, BOOL, INT, UUID)) {
            arrayOf(it.device, TYPE_L2CAP, true, true, it.psm, it.uuid)
        },
        Signature("(type, fd, auth, encrypt, device, port, uuid)", arrayOf(INT, INT, BOOL, BOOL, DEVICE, INT, UUID)) {
            arrayOf(TYPE_L2CAP, -1, true, true, it.device, it.psm, it.uuid)
        },
    )

    @Volatile private var exempted = false

    /**
     * Android 9 起系统会拦截 App 通过反射访问非公开 API（日志里的 "hiddenapi: ... denied"）。
     * 只对 BluetoothSocket / BluetoothDevice 两个类解除限制，其余非公开 API 仍保持拦截。
     */
    private fun ensureExempted(log: (String) -> Unit) {
        if (exempted) return
        exempted = HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/bluetooth/BluetoothSocket;",
            "Landroid/bluetooth/BluetoothDevice;",
        )
        log(if (exempted) "已解除蓝牙 socket 的反射限制" else "解除反射限制失败")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun create(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        psm: Int,
        uuid: ParcelUuid,
        log: (String) -> Unit,
    ): BluetoothSocket {
        ensureExempted(log)
        val ctx = Ctx(adapter, device, psm, uuid)
        val available = BluetoothSocket::class.java.declaredConstructors
            .joinToString("; ") { c -> c.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
        log("BluetoothSocket 构造函数: $available")

        var last: Throwable? = null
        for (sig in signatures) {
            try {
                val ctor = BluetoothSocket::class.java.getDeclaredConstructor(*sig.types)
                ctor.isAccessible = true
                val socket = ctor.newInstance(*sig.args(ctx)) as BluetoothSocket
                log("使用构造函数 ${sig.label}")
                return socket
            } catch (e: NoSuchMethodException) {
                last = e
            } catch (e: ReflectiveOperationException) {
                last = e.cause ?: e
                log("构造函数 ${sig.label} 调用失败: ${last.message}")
            } catch (e: SecurityException) {
                last = e
                log("构造函数 ${sig.label} 被拒绝: ${e.message}")
            }
        }

        // 兜底：BluetoothDevice 的隐藏方法 createL2capSocket(int)
        try {
            val m = BluetoothDevice::class.java.getMethod("createL2capSocket", INT)
            val socket = m.invoke(device, psm) as BluetoothSocket
            log("使用 BluetoothDevice.createL2capSocket")
            return socket
        } catch (e: ReflectiveOperationException) {
            last = e.cause ?: e
            log("createL2capSocket 失败: ${last.message}")
        }
        throw IllegalStateException("无法创建 L2CAP socket（系统不支持任何已知签名）", last)
    }
}
