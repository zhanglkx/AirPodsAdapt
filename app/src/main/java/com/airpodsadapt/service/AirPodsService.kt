// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.airpodsadapt.aap.AapConnection
import com.airpodsadapt.aap.AapEvent
import com.airpodsadapt.aap.AapProtocol
import com.airpodsadapt.aap.AapProtocol.ControlId
import com.airpodsadapt.aap.ListeningMode
import com.airpodsadapt.ble.BleScanner
import com.airpodsadapt.ble.ProximityStatus
import com.airpodsadapt.core.AirPodsHub
import com.airpodsadapt.core.AirPodsState
import com.airpodsadapt.core.AppSettings
import com.airpodsadapt.core.Battery
import com.airpodsadapt.core.Link
import com.airpodsadapt.core.Permissions
import com.airpodsadapt.core.PodPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 常驻前台服务：
 * 1. 监听 AirPods 音频连接，自动建立 AAP 控制通道（失败退避重试）；
 * 2. 熄屏前持续扫描 BLE 广播，作为电量来源的降级方案和开盖弹窗的触发器；
 * 3. 实现手机端功能：摘下暂停、对话感知降音量、常驻通知。
 *
 * 所有业务逻辑都在主线程串行执行（[scope] 使用 Main 调度器），只有 socket 读写在 IO 线程。
 */
@SuppressLint("MissingPermission") // 服务只在 BLUETOOTH_CONNECT 已授予时启动；BLE 扫描前单独检查 BLUETOOTH_SCAN
class AirPodsService : Service(), AirPodsHub.Controller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var adapter: BluetoothAdapter
    private lateinit var notifier: Notifier
    private lateinit var media: MediaAutomation
    private lateinit var ble: BleScanner
    private var ready = false

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null

    @Volatile private var connection: AapConnection? = null
    private var connectJob: Job? = null
    private var target: BluetoothDevice? = null
    private var attempts = 0

    private var bestBle: ProximityStatus? = null
    private var lastLidOpen: Boolean? = null
    private var lastPopupAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        notifier = Notifier(this)
        if (!Permissions.canConnect(this)) {
            // 不应发生：start() 已检查权限。此时调用 startForeground 会抛 SecurityException，直接退出
            AirPodsHub.log("缺少蓝牙连接权限，服务退出")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this, Notifier.ID_STATUS, notifier.buildStatus(AirPodsHub.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (btAdapter == null) {
            AirPodsHub.log("此设备没有蓝牙")
            stopSelf()
            return
        }
        adapter = btAdapter
        media = MediaAutomation(getSystemService(AudioManager::class.java))
        ble = BleScanner(adapter, AirPodsHub::log) { status -> scope.launch { onProximity(status) } }
        ready = true

        AirPodsHub.controller = this
        AirPodsHub.update { it.copy(bluetoothOn = adapter.isEnabled) }
        AirPodsHub.log("后台服务已启动")
        registerReceivers()
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        if (getSystemService(PowerManager::class.java).isInteractive) startBle()

        scope.launch {
            AirPodsHub.state
                .map { notificationKey(it) }
                .distinctUntilChanged()
                .collect { notifier.updateStatus(AirPodsHub.state.value) }
        }
        scope.launch { expireStaleBle() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_SET_MODE ->
                ListeningMode.fromValue(intent.getIntExtra(EXTRA_MODE, -1))?.let(AirPodsHub::setListeningMode)
            ACTION_RECONNECT -> reconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (ready) {
            AirPodsHub.controller = null
            connectJob?.cancel()
            connection?.close()
            connection = null
            media.restoreVolume()
            stopBle()
            runCatching { unregisterReceiver(receiver) }
            a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            AirPodsHub.update { it.copy(link = Link.IDLE) }
            AirPodsHub.log("后台服务已停止")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- Controller ----

    override fun send(packet: ByteArray): Boolean = connection?.send(packet) ?: false

    override fun reconnect() {
        scope.launch {
            AirPodsHub.log("手动重连")
            attempts = 0
            connectJob?.cancel()
            connection?.close()
            connection = null
            val device = findConnectedAirPods() ?: target
            if (device == null) {
                AirPodsHub.log("未找到已连接的 AirPods，请先在系统蓝牙中连接")
                AirPodsHub.update { it.copy(link = Link.IDLE, linkError = null) }
                return@launch
            }
            onAudioConnected(device, force = true)
        }
    }

    // ---- 蓝牙事件 ----

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    if (!isAirPods(device)) return
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> onAudioConnected(device)
                        BluetoothProfile.STATE_DISCONNECTED -> scope.launch {
                            // 等另一个 profile 的状态稳定后再判断
                            delay(500)
                            if (findConnectedAirPods() == null) AirPodsHub.update { it.copy(audioConnected = false) }
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.device() ?: return
                    if (device.address == target?.address) onAirPodsGone()
                }
                Intent.ACTION_SCREEN_ON -> startBle()
                Intent.ACTION_SCREEN_OFF -> stopBle()
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 蓝牙广播由蓝牙进程（非 system uid）发出，NOT_EXPORTED 可能收不到；这些都是受保护广播，其他 App 无法伪造
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
            scope.launch { findConnectedAirPods()?.let { onAudioConnected(it) } }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
        }
    }

    private fun onAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                AirPodsHub.update { it.copy(bluetoothOn = true) }
                startBle()
            }
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                AirPodsHub.update { it.copy(bluetoothOn = false) }
                onAirPodsGone()
                ble.stopIfPermitted()
            }
        }
    }

    private fun isAirPods(device: BluetoothDevice): Boolean =
        device.uuids?.any { it == AapProtocol.SERVICE_UUID } == true ||
            device.name?.contains("AirPods", ignoreCase = true) == true

    private fun findConnectedAirPods(): BluetoothDevice? =
        listOfNotNull(a2dp, headset).flatMap { it.connectedDevices }.firstOrNull(::isAirPods)

    private fun onAudioConnected(device: BluetoothDevice, force: Boolean = false) {
        AirPodsHub.update {
            it.copy(audioConnected = true, deviceAddress = device.address, systemName = device.name ?: it.systemName)
        }
        val sameDevice = target?.address == device.address
        if (!force && sameDevice && (connection?.isOpen == true || connectJob?.isActive == true)) return
        if (!sameDevice) {
            connection?.close()
            connection = null
        }
        target = device
        if (!force) attempts = 0
        AirPodsHub.log("检测到 AirPods 音频已连接：${device.name}")
        scheduleConnect(if (force) 0 else INITIAL_DELAY_MS)
    }

    private fun onAirPodsGone() {
        connectJob?.cancel()
        connection?.close()
        connection = null
        if (ready) {
            media.restoreVolume()
            media.resetEar()
        }
        AirPodsHub.update {
            it.copy(
                audioConnected = false, link = Link.IDLE, linkError = null,
                primary = PodPlacement.UNKNOWN, secondary = PodPlacement.UNKNOWN,
                controls = emptyMap(), conversationLevel = null,
                battery = if (it.battery.source == Battery.Source.AAP) Battery() else it.battery,
            )
        }
        AirPodsHub.log("AirPods 已断开")
    }

    // ---- AAP 连接 ----

    private fun scheduleConnect(delayMs: Long) {
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(delayMs)
            connectOnce()
        }
    }

    private suspend fun connectOnce() {
        val device = target ?: return
        if (!adapter.isEnabled) return
        attempts++
        AirPodsHub.update { it.copy(link = Link.CONNECTING) }
        AirPodsHub.log("第 $attempts 次尝试建立控制通道（${device.name}）")

        lateinit var conn: AapConnection
        conn = AapConnection(
            adapter = adapter,
            device = device,
            scope = scope,
            log = AirPodsHub::log,
            onEvent = { event -> scope.launch { onAapEvent(event) } },
            onClosed = { error -> scope.launch { onAapClosed(conn, error) } },
        )
        val failure: String = try {
            conn.open()
            // 读循环可能在初始化期间就因对端关闭而结束
            if (!conn.isOpen) throw IOException("连接建立后立即被耳机断开")
            connection = conn
            AirPodsHub.update { it.copy(link = Link.CONNECTED, linkError = null) }
            AirPodsHub.log("控制通道已建立")
            return
        } catch (e: CancellationException) {
            conn.close()
            throw e
        } catch (e: IOException) {
            e.message ?: "连接失败"
        } catch (e: IllegalStateException) {
            e.message ?: "无法创建连接"
        } catch (e: SecurityException) {
            "系统拒绝访问：${e.message}"
        } catch (e: RuntimeException) {
            // 反射调用的隐藏构造函数在个别 ROM 上可能抛出其他运行时异常
            "${e.javaClass.simpleName}：${e.message}"
        }
        conn.close()
        AirPodsHub.log("建立控制通道失败：$failure")
        retryOrFail(failure)
    }

    private fun retryOrFail(reason: String) {
        val audioUp = AirPodsHub.state.value.audioConnected
        if (audioUp && attempts <= RETRY_DELAYS_MS.size) {
            // attempts 可能为 0（会话正常后断开），此时按第一档等待
            val wait = RETRY_DELAYS_MS[(attempts - 1).coerceIn(0, RETRY_DELAYS_MS.size - 1)]
            AirPodsHub.update { it.copy(link = Link.CONNECTING, linkError = reason) }
            AirPodsHub.log("${wait / 1000} 秒后重试")
            scheduleConnect(wait)
        } else {
            AirPodsHub.update { it.copy(link = if (audioUp) Link.FAILED else Link.IDLE, linkError = reason) }
        }
    }

    private fun onAapClosed(conn: AapConnection, error: Throwable?) {
        if (connection !== conn) return
        connection = null
        media.restoreVolume()
        media.resetEar()
        AirPodsHub.update {
            it.copy(primary = PodPlacement.UNKNOWN, secondary = PodPlacement.UNKNOWN, conversationLevel = null)
        }
        retryOrFail("控制通道断开：${error?.message ?: "未知原因"}")
    }

    private fun onAapEvent(event: AapEvent) {
        // 收到有效数据说明会话正常，重置重试计数
        attempts = 0
        val prefs = AppSettings.state.value
        when (event) {
            is AapEvent.BatteryReport -> AirPodsHub.update { st ->
                // 耳机不在盒中时不会上报盒子电量，保留上一次的读数
                st.copy(battery = event.battery.copy(case = event.battery.case ?: st.battery.case))
            }
            is AapEvent.EarDetection -> {
                AirPodsHub.update { it.copy(primary = event.primary, secondary = event.secondary) }
                val pods = listOf(event.primary, event.secondary)
                media.onEarChanged(
                    inEar = pods.count { it == PodPlacement.IN_EAR },
                    allInCase = pods.all { it == PodPlacement.IN_CASE },
                    enabled = prefs.autoPause,
                )
            }
            is AapEvent.Control -> {
                AirPodsHub.update { it.copy(controls = it.controls + (event.id to event.values)) }
                if (event.id == ControlId.CONVERSATION_DETECT && event.values.first() == AapProtocol.OFF) {
                    media.restoreVolume()
                }
            }
            is AapEvent.ConversationAwareness -> {
                AirPodsHub.update { it.copy(conversationLevel = event.level) }
                media.onConversationLevel(event.level, prefs.conversationDucking)
            }
            is AapEvent.Info -> AirPodsHub.update { it.copy(info = event.info) }
            is AapEvent.Unknown -> Unit
        }
    }

    // ---- BLE ----

    private fun startBle() {
        if (ready && adapter.isEnabled && Permissions.canScan(this)) ble.start()
    }

    private fun stopBle() {
        if (ready) ble.stopIfPermitted()
    }

    private fun BleScanner.stopIfPermitted() {
        if (Permissions.canScan(this@AirPodsService)) stop()
    }

    private fun onProximity(p: ProximityStatus) {
        if (p.rssi < BLE_MIN_RSSI) return
        // 附近可能有别人的 AirPods：几秒内只跟随信号最强的那一副
        val best = bestBle
        if (best != null && best.address != p.address &&
            p.timestamp - best.timestamp < BLE_SWITCH_WINDOW_MS && p.rssi < best.rssi - 3
        ) return
        val reappeared = best == null || p.timestamp - best.timestamp > BLE_REAPPEAR_MS
        bestBle = p

        val prevLid = lastLidOpen
        lastLidOpen = p.lidOpen
        AirPodsHub.update { st ->
            val battery = if (st.connected && st.battery.source == Battery.Source.AAP) {
                st.battery.copy(case = st.battery.case ?: p.battery.case)
            } else {
                p.battery
            }
            st.copy(
                battery = battery, bleLeftInEar = p.leftInEar, bleRightInEar = p.rightInEar,
                lidOpen = p.lidOpen, bleSeenAt = p.timestamp, bleModel = p.modelName,
            )
        }

        val state = AirPodsHub.state.value
        val prefs = AppSettings.state.value
        // 控制通道不可用但音频已连接时，用广播里的入耳状态做自动暂停
        if (!state.connected && state.audioConnected) {
            media.onEarChanged(
                inEar = listOf(p.leftInEar, p.rightInEar).count { it },
                allInCase = false,
                enabled = prefs.autoPause,
            )
        }

        val podsOut = !p.leftInEar && !p.rightInEar
        if (p.lidOpen && (prevLid == false || reappeared) && podsOut && p.rssi >= POPUP_MIN_RSSI &&
            prefs.lidPopup && p.timestamp - lastPopupAt > POPUP_COOLDOWN_MS
        ) {
            lastPopupAt = p.timestamp
            AirPodsHub.log("检测到开盖（RSSI ${p.rssi}）")
            notifier.showLidPopup(state)
        } else if (!p.lidOpen && prevLid == true) {
            notifier.cancelLidPopup()
        }
    }

    /** 一分钟没收到广播就清掉 BLE 数据，避免显示过期电量。 */
    private suspend fun expireStaleBle() {
        while (scope.isActive) {
            delay(15_000)
            val st = AirPodsHub.state.value
            if (st.bleSeenAt != 0L && SystemClock.elapsedRealtime() - st.bleSeenAt > BLE_STALE_MS) {
                bestBle = null
                lastLidOpen = null
                AirPodsHub.update {
                    it.copy(
                        bleSeenAt = 0, bleLeftInEar = null, bleRightInEar = null, lidOpen = null,
                        battery = if (it.battery.source == Battery.Source.BLE) Battery() else it.battery,
                    )
                }
            }
        }
    }

    private fun notificationKey(s: AirPodsState) =
        listOf(s.bluetoothOn, s.link, s.audioConnected, s.displayName, s.battery, s.listeningMode)

    private fun Intent.device(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    companion object {
        const val ACTION_SET_MODE = "com.airpodsadapt.SET_MODE"
        const val ACTION_RECONNECT = "com.airpodsadapt.RECONNECT"
        const val EXTRA_MODE = "mode"

        private const val INITIAL_DELAY_MS = 1500L
        private val RETRY_DELAYS_MS = longArrayOf(3_000, 6_000, 12_000)
        private const val BLE_MIN_RSSI = -75
        private const val POPUP_MIN_RSSI = -65
        private const val BLE_SWITCH_WINDOW_MS = 5_000L
        private const val BLE_REAPPEAR_MS = 10_000L
        private const val BLE_STALE_MS = 60_000L
        private const val POPUP_COOLDOWN_MS = 30_000L

        /** 仅在已授予蓝牙连接权限时启动，否则前台服务会因缺少前置权限而崩溃。 */
        fun start(context: Context) {
            if (!Permissions.canConnect(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, AirPodsService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ 后台限制启动前台服务时抛 ForegroundServiceStartNotAllowedException
                AirPodsHub.log("无法在后台启动服务：${e.message}")
            }
        }
    }
}
