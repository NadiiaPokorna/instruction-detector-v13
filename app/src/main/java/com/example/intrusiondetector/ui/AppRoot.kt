package com.example.intrusiondetector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import com.example.intrusiondetector.ble.HubViewModel
import com.example.intrusiondetector.cloud.CloudViewModel

/** Дві вкладки: детектор (ЛР1) і BLE-хаб (ЛР2). */
@Composable
fun AppRoot(
    intrusionViewModel: IntrusionViewModel,
    hubViewModel: HubViewModel,
    cloudViewModel: CloudViewModel,
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
            }
        },
    ) { padding ->
        // consumeWindowInsets: внутрішній Scaffold екрана не додає відступи вдруге
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (tab == 0) {
                val state by intrusionViewModel.uiState.collectAsStateWithLifecycle()
                val cloud by cloudViewModel.uiState.collectAsStateWithLifecycle()
                IntrusionScreen(state = state, cloud = cloud, onRearm = intrusionViewModel::rearm)
            } else {
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
