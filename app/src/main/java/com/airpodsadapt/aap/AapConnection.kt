// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.aap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条到 AirPods 的 AAP 会话：建立 L2CAP 连接 → 发送初始化序列 → 后台读循环。
 *
 * 读到的包解析后通过 [onEvent] 回调；连接意外断开时回调 [onClosed]（主动 [close] 不回调）。
 */
class AapConnection(
    private val adapter: BluetoothAdapter,
    val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onEvent: (AapEvent) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closedByUs = false
    @Volatile var packetsReceived = 0
        private set
    private var readJob: Job? = null
    private val writeLock = Any()

    val isOpen: Boolean get() = socket?.isConnected == true && !closedByUs

    /** 阻塞直到连接成功或失败；失败抛出异常，异常消息可直接展示给用户。 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun open() = withContext(Dispatchers.IO) {
        val s = L2capSocketFactory.create(adapter, device, AapProtocol.PSM, AapProtocol.SERVICE_UUID, log)
        socket = s

        // connect() 是阻塞调用且不响应协程取消，超时后关闭 socket 让它抛出异常
        val timedOut = AtomicBoolean(false)
        val watchdog = scope.launch(Dispatchers.IO) {
            delay(CONNECT_TIMEOUT_MS)
            timedOut.set(true)
            runCatching { s.close() }
        }
        try {
            log("正在连接 L2CAP PSM 0x${AapProtocol.PSM.toString(16)} …")
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            socket = null
            val reason = if (timedOut.get()) "连接超时（${CONNECT_TIMEOUT_MS / 1000}s 无响应）" else "连接被拒绝：${e.message}"
            throw IOException(reason, e)
        } finally {
            watchdog.cancel()
        }
        log("L2CAP 已连接")

        readJob = scope.launch(Dispatchers.IO) { readLoop(s) }
        sendInitSequence()

        // 部分固件首包可能丢失，1.5 秒内没收到任何回包就重发一次初始化序列（socket 写入是阻塞的，放 IO 线程）
        scope.launch(Dispatchers.IO) {
            delay(1500)
            if (isOpen && packetsReceived == 0) {
                log("未收到回包，重发初始化序列")
                sendInitSequence()
            }
        }
    }

    private suspend fun sendInitSequence() {
        send(AapProtocol.handshake())
        delay(150)
        send(AapProtocol.featureFlags())
        delay(150)
        send(AapProtocol.requestNotifications())
        delay(150)
        // 实验：请求成为连接主控，尝试从系统（com.xiaomi.bluetooth）手中接管通知流与控制权
        send(AapProtocol.control(AapProtocol.ControlId.OWNS_CONNECTION, AapProtocol.ON))
    }

    private fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(2048)
        var error: Throwable? = null
        try {
            val input = s.inputStream
            while (scope.isActive && !closedByUs) {
                // L2CAP 是面向包的：每次 read 返回一个完整的包
                val n = input.read(buf)
                if (n < 0) throw IOException("耳机关闭了连接")
                if (n == 0) continue
                val packet = buf.copyOf(n)
                packetsReceived++
                val event = AapParser.parse(packet)
                log("← ${AapProtocol.toHex(packet)}")
                if (event != null) {
                    try {
                        onEvent(event)
                    } catch (e: RuntimeException) {
                        log("处理数据包出错：${e.message}")
                    }
                }
            }
        } catch (e: IOException) {
            error = e
        } catch (e: RuntimeException) {
            // 隐藏 API 在个别 ROM 上可能抛出非 IO 异常；在这里兜住，避免整个 App 崩溃
            error = e
        } finally {
            runCatching { s.close() }
            if (!closedByUs) {
                log("连接断开：${error?.message ?: "未知原因"}")
                onClosed(error)
            }
        }
    }

    /** 线程安全；在 IO 线程调用。 */
    fun send(packet: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            synchronized(writeLock) {
                s.outputStream.write(packet)
                s.outputStream.flush()
            }
            log("→ ${AapProtocol.toHex(packet)}")
            true
        } catch (e: IOException) {
            log("发送失败：${e.message}")
            false
        }
    }

    fun close() {
        closedByUs = true
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 6000L
    }
}
