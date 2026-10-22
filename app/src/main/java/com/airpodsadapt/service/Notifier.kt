// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.airpodsadapt.MainActivity
import com.airpodsadapt.R
import com.airpodsadapt.aap.ListeningMode
import com.airpodsadapt.core.AirPodsState
import com.airpodsadapt.core.Battery
import com.airpodsadapt.core.BatteryLevel
import com.airpodsadapt.core.Link
import com.airpodsadapt.core.Permissions

class Notifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "耳机状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻显示电量和降噪模式"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_POPUP, "开盖弹窗", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "打开充电盒时弹出电量"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private val openApp: PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun buildStatus(state: AirPodsState): Notification {
        val title = when {
            !state.bluetoothOn -> "蓝牙已关闭"
            state.connected -> state.displayName
            state.audioConnected && state.link == Link.CONNECTING -> "${state.displayName} · 正在连接控制通道"
            state.audioConnected -> "${state.displayName} · 仅基础功能"
            else -> "AirPods 未连接"
        }
        val text = buildString {
            append(batteryText(state.battery))
            state.listeningMode?.takeIf { state.connected }?.let { append(" · ").append(it.label) }
        }.ifBlank { "打开 App 查看详情" }

        val b = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_airpods)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state.connected) {
            for (mode in listOf(ListeningMode.ANC, ListeningMode.ADAPTIVE, ListeningMode.TRANSPARENCY)) {
                val label = if (state.listeningMode == mode) "● ${mode.label}" else mode.label
                b.addAction(0, label, modeIntent(mode))
            }
        }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    fun updateStatus(state: AirPodsState) {
        if (!canNotify()) return
        nm.notify(ID_STATUS, buildStatus(state))
    }

    @SuppressLint("MissingPermission")
    fun showLidPopup(state: AirPodsState) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_POPUP)
            .setSmallIcon(R.drawable.ic_stat_airpods)
            .setContentTitle(state.displayName)
            .setContentText(batteryText(state.battery).ifBlank { "已打开充电盒" })
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(POPUP_TIMEOUT_MS)
            .build()
        nm.notify(ID_POPUP, n)
    }

    fun cancelLidPopup() = nm.cancel(ID_POPUP)

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            Permissions.has(context, android.Manifest.permission.POST_NOTIFICATIONS)

    private fun modeIntent(mode: ListeningMode): PendingIntent = PendingIntent.getService(
        context, mode.value,
        Intent(context, AirPodsService::class.java)
            .setAction(AirPodsService.ACTION_SET_MODE)
            .putExtra(AirPodsService.EXTRA_MODE, mode.value),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_POPUP = "lid_popup"
        const val ID_STATUS = 1
        const val ID_POPUP = 2
        private const val POPUP_TIMEOUT_MS = 20_000L

        fun batteryText(b: Battery): String = listOfNotNull(
            b.left?.let { "左 ${fmt(it)}" },
            b.right?.let { "右 ${fmt(it)}" },
            b.case?.let { "盒 ${fmt(it)}" },
        ).joinToString("  ")

        private fun fmt(l: BatteryLevel) = "${l.percent}%" + if (l.charging) "⚡" else ""
    }
}
