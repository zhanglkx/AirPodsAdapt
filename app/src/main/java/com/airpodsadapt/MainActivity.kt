// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airpodsadapt.core.AirPodsHub
import com.airpodsadapt.core.AppSettings
import com.airpodsadapt.core.Permissions
import com.airpodsadapt.service.AirPodsService
import com.airpodsadapt.ui.DiagnosticsScreen
import com.airpodsadapt.ui.HomeScreen
import com.airpodsadapt.ui.PermissionScreen
import com.airpodsadapt.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)
        setContent { AppTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // App 在前台时启动前台服务不受后台限制；服务已在运行时这是空操作
        AirPodsService.start(this)
    }
}

private enum class Screen { HOME, DIAGNOSTICS }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    fun hasBluetooth() = Permissions.canConnect(context) && Permissions.canScan(context)

    var granted by remember { mutableStateOf(hasBluetooth()) }
    LifecycleResumeEffect(Unit) {
        granted = hasBluetooth()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBluetooth()
        AirPodsService.start(context)
    }

    if (!granted) {
        PermissionScreen(onRequest = { launcher.launch(Permissions.required) })
        return
    }

    val state by AirPodsHub.state.collectAsStateWithLifecycle()
    val prefs by AppSettings.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(state, prefs, onOpenDiagnostics = { screen = Screen.DIAGNOSTICS })
        Screen.DIAGNOSTICS -> {
            val logs by AirPodsHub.logs.collectAsStateWithLifecycle()
            BackHandler { screen = Screen.HOME }
            DiagnosticsScreen(state, logs, onBack = { screen = Screen.HOME })
        }
    }
}
