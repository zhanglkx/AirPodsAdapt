// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import android.util.Log
import com.airpodsadapt.aap.AapProtocol
import com.airpodsadapt.aap.AapProtocol.ControlId
import com.airpodsadapt.aap.ListeningMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内单例：状态源 + 控制入口 + 诊断日志。
 *
 * 后台服务启动时把自己注册为 [controller]，界面和磁贴只跟这里打交道，不直接持有服务。
 */
object AirPodsHub {
    const val TAG = "AirPodsAdapt"
    private const val MAX_LOG_LINES = 500

    /** 后台服务实现的控制接口。 */
    interface Controller {
        /** 发送一个原始 AAP 包；未连接时返回 false。会阻塞，需在 IO 线程调用。 */
        fun send(packet: ByteArray): Boolean
        /** 清空重试计数并立即重连。 */
        fun reconnect()
    }

    @Volatile var controller: Controller? = null

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = _state.asStateFlow()

    fun update(transform: (AirPodsState) -> AirPodsState) = _state.update(transform)

    // ---- 诊断日志 ----

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        Log.d(TAG, message)
        val line = synchronized(timeFormat) { timeFormat.format(Date()) } + "  " + message
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    fun clearLogs() = _logs.update { emptyList() }

    // ---- 控制 ----

    /** 发送控制命令，并先乐观更新本地状态，让界面立即响应。 */
    fun setControl(id: Int, vararg values: Int) {
        val padded = (values.toList() + List(4) { 0 }).take(4)
        update { it.copy(controls = it.controls + (id to padded)) }
        sendAsync(AapProtocol.control(id, *values))
    }

    fun setToggle(id: Int, on: Boolean) = setControl(id, if (on) AapProtocol.ON else AapProtocol.OFF)

    fun setListeningMode(mode: ListeningMode) {
        // 新固件要求先允许“关闭”选项，否则切到关闭会被耳机拒绝
        if (mode == ListeningMode.OFF && state.value.control(ControlId.ALLOW_OFF) != AapProtocol.ON) {
            setToggle(ControlId.ALLOW_OFF, true)
        }
        setControl(ControlId.LISTENING_MODE, mode.value)
    }

    fun rename(name: String) {
        val packet = AapProtocol.rename(name)
        update { st -> st.copy(info = (st.info ?: DeviceInfo()).copy(name = name.trim())) }
        sendAsync(packet)
    }

    fun reconnect() {
        val c = controller
        if (c == null) log("后台服务未运行，无法重连") else c.reconnect()
    }

    private fun sendAsync(packet: ByteArray) {
        if (controller == null) {
            log("后台服务未运行，命令未发送")
            return
        }
        outbox.trySend(packet)
    }

    /** 单一消费者按顺序发送，保证“先允许关闭、再切到关闭”这类组合命令的顺序。 */
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED).also { channel ->
        io.launch {
            for (packet in channel) {
                val c = controller
                if (c == null || !c.send(packet)) log("命令未发送：控制通道未连接")
            }
        }
    }
}
