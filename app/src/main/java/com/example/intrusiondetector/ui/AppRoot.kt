package com.example.intrusiondetector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.intrusiondetector.IntrusionViewModel
import com.example.intrusiondetector.actuator.ControlViewModel
import com.example.intrusiondetector.ble.HubViewModel
import com.example.intrusiondetector.cloud.CloudViewModel

/** Три вкладки: детектор (ЛР1), BLE-хаб (ЛР2) і керування актуаторами (ЛР4). */
@Composable
fun AppRoot(
    intrusionViewModel: IntrusionViewModel,
    hubViewModel: HubViewModel,
    cloudViewModel: CloudViewModel,
    controlViewModel: ControlViewModel,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Детектор") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    label = { Text("Хаб BLE") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Керування") },
                )
            }
        },
    ) { padding ->
        // consumeWindowInsets: внутрішній Scaffold екрана не додає відступи вдруге
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> {
                    val state by intrusionViewModel.uiState.collectAsStateWithLifecycle()
                    val cloud by cloudViewModel.uiState.collectAsStateWithLifecycle()
                    IntrusionScreen(state = state, cloud = cloud, onRearm = intrusionViewModel::rearm)
                }
                2 -> {
                    val state by controlViewModel.uiState.collectAsStateWithLifecycle()
                    val actuator by controlViewModel.actuatorState.collectAsStateWithLifecycle()
                    ControlScreen(
                        state = state,
                        actuator = actuator,
                        onToggleTorch = controlViewModel::toggleTorch,
                        onAlarm = controlViewModel::alarm,
                        onAutoMode = controlViewModel::setAutoMode,
                    )
                }
                else -> {
                    val state by hubViewModel.uiState.collectAsStateWithLifecycle()
                    HubScreen(
                        state = state,
                        onPermissionsResult = hubViewModel::onPermissionsResult,
                        onConnect = hubViewModel::connect,
                        onSendState = hubViewModel::sendState,
                        onDisconnect = hubViewModel::disconnect,
                    )
                }
            }
        }
    }
}
