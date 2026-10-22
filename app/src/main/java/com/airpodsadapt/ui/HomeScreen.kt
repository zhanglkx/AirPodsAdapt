// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package com.airpodsadapt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airpodsadapt.core.AirPodsState
import com.airpodsadapt.core.AppPrefs
import com.airpodsadapt.core.Link

@Composable
fun HomeScreen(state: AirPodsState, prefs: AppPrefs, onOpenDiagnostics: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirPods 助手") },
                actions = { TextButton(onClick = onOpenDiagnostics) { Text("诊断") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(state) }
            if (state.audioConnected && state.link == Link.FAILED) {
                item { TroubleshootCard(state) }
            }
            item { ListeningModeCard(state) }
            item { HeadphoneFeaturesCard(state) }
            item { PressAndHoldCard(state) }
            item { MicrophoneCard(state) }
            item { PhoneFeaturesCard(prefs) }
            item { DeviceInfoCard(state) }
            item { BackgroundCard() }
        }
    }
}
