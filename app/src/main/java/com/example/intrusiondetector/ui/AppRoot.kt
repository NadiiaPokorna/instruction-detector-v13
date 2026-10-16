package com.example.intrusiondetector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.ui.ble.HubScreen

private enum class AppTab(val label: String, val icon: ImageVector) {
    SENSOR("Сенсор", AppIcons.Vibration),
    HUB("Хаб BLE", AppIcons.Bluetooth),
}

/** Дві вкладки з нижньою навігацією: екран сенсора з ЛР1 і екран хаба BLE. */
@Composable
fun AppRoot(sensorState: IntrusionUiState, onRearm: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(AppTab.SENSOR) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, contentDescription = null) },
                        label = { Text(it.label) },
                    )
                }
            }
        },
        // Верхні відступи обробляють TopAppBar вкладених екранів.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                AppTab.SENSOR -> IntrusionScreen(state = sensorState, onRearm = onRearm)
                AppTab.HUB -> HubScreen()
            }
        }
    }
}
