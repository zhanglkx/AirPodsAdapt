// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.aap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * 最近一次收到数据包（或连接建立）的时刻（SystemClock.elapsedRealtime）。
     * 注意：耳机状态不变时本来就不发任何数据，静默不代表连接已坏。
     */
    @Volatile var lastPacketAt = 0L
        private set
    private var readJob: Job? = null
    private val writeLock = Any()
    /** 收到第一个业务包（`04 00 04 00 …`）时完成；握手被拒或连接被关时以异常完成。 */
    private val sessionReady = CompletableDeferred<Unit>()

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
        lastPacketAt = android.os.SystemClock.elapsedRealtime()
        log("L2CAP 已连接")

        readJob = scope.launch(Dispatchers.IO) { readLoop(s) }

        // L2CAP 连上不等于会话可用。耳机刚连上手机时，本应用的第一条连接实测必定不被理睬
        // （12 次全中，与连接时机无关，第二条必定成功）；在同一 socket 上重发握手只换来拒绝（状态 0x85）。
        // 健康会话 8~20ms 内回握手，所以只等一小会儿，等不到就判失败，由上层换新 socket 立即重试。
        send(AapProtocol.handshake())
        val ok = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { sessionReady.await() }
        if (ok == null) throw IOException("耳机未响应握手")
        sendInitSequence()
        log("会话已就绪")
    }

    /** 握手之后的初始化序列。 */
    private suspend fun sendInitSequence() {
        delay(150)
        send(AapProtocol.featureFlags())
        delay(150)
        send(AapProtocol.requestNotifications())
        delay(150)
        // 请求成为连接主控，从系统（com.xiaomi.bluetooth）手中接管通知流与控制权
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
                lastPacketAt = android.os.SystemClock.elapsedRealtime()
                log("← ${AapProtocol.toHex(packet)}")
                checkSession(packet)
                val event = AapParser.parse(packet)
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
            sessionReady.completeExceptionally(IOException(error?.message ?: "耳机关闭了连接"))
            if (!closedByUs) {
                log("连接断开：${error?.message ?: "未知原因"}")
                onClosed(error)
            }
        }
    }

    /**
     * 握手回包 `01 00 04 00 [状态 2 字节] …`：状态 0 表示接受；实测出现过 0x85，之后耳机不再发任何数据。
     * 收到业务包（`04 00 04 00 …`）同样说明会话可用。
     */
    private fun checkSession(p: ByteArray) {
        if (sessionReady.isCompleted || p.size < 6 || p[1].toInt() != 0 || p[2].toInt() != 4 || p[3].toInt() != 0) return
        when (p[0].toInt()) {
            0x04 -> sessionReady.complete(Unit)
            0x01 -> {
                val status = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
                if (status == 0) {
                    sessionReady.complete(Unit)
                } else {
                    sessionReady.completeExceptionally(IOException("耳机拒绝握手（状态 0x%04X）".format(status)))
                }
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
        /**
         * 实测成功的 connect() 在 0.2~2.4s 内返回。设备不在小米 L2CAP 白名单时，蓝牙栈协商失败后
         * 既不报错也不回调，只能等超时——超时越短，输掉这场竞速后越快进入下一次重试。
         */
        const val CONNECT_TIMEOUT_MS = 4000L

        /** 发出握手后等回包的时间。实测健康会话 8~20ms 内回复，留足余量。 */
        const val HANDSHAKE_TIMEOUT_MS = 400L
    }
}
