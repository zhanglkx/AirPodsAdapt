// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airpodsadapt.aap.ListeningMode
import com.airpodsadapt.core.AirPodsHub
import com.airpodsadapt.core.AirPodsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 控制中心磁贴：点击在 降噪 → 自适应 → 通透 之间循环。 */
class NoiseControlTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = scope.launch { AirPodsHub.state.collect(::render) }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val st = AirPodsHub.state.value
        if (!st.connected) {
            AirPodsService.start(this)
            AirPodsHub.reconnect()
            return
        }
        val next = when (st.listeningMode) {
            ListeningMode.ANC -> ListeningMode.ADAPTIVE
            ListeningMode.ADAPTIVE -> ListeningMode.TRANSPARENCY
            else -> ListeningMode.ANC
        }
        AirPodsHub.setListeningMode(next)
    }

    private fun render(st: AirPodsState) {
        val tile = qsTile ?: return
        val mode = st.listeningMode
        tile.label = if (st.connected) mode?.label ?: "AirPods" else "AirPods"
        tile.subtitle = when {
            st.connected -> st.displayName
            st.audioConnected -> "控制通道未连接"
            else -> "未连接"
        }
        tile.state = when {
            !st.connected -> Tile.STATE_INACTIVE
            mode == ListeningMode.OFF -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }
}
