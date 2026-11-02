package com.example.intrusiondetector.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.intrusiondetector.ble.BleDevice
import com.example.intrusiondetector.ble.BleState
import com.example.intrusiondetector.ble.HubUiState
import com.example.intrusiondetector.ui.theme.CalibratingColor
import com.example.intrusiondetector.ui.theme.QuietColor
import com.example.intrusiondetector.ui.theme.VibrationColor

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

@Composable
fun HubScreen(
    state: HubUiState,
    onPermissionsResult: (Boolean) -> Unit,
    onConnect: (BleDevice) -> Unit,
    onSendState: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // Дозволи запитуються перед кожним пошуком; вже надані система повертає одразу.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> onPermissionsResult(result.values.all { it }) }

    val shownState = if (state.scanning) BleState.SCANNING else state.connection
    val connected = state.connection == BleState.CONNECTED

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(shownState.color(), CircleShape))
                Text("  ${shownState.name}", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Button(
                onClick = { permissionLauncher.launch(BLE_PERMISSIONS) },
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Пошук пристроїв") }
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.permissionDenied) {
                Text(
                    "Без дозволів Bluetooth пошук неможливий",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(state.devices) { device ->
            Card(Modifier.fillMaxWidth().clickable { onConnect(device) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text("${device.address}   RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSendState, enabled = connected) { Text("Надіслати стан") }
                OutlinedButton(onClick = onDisconnect, enabled = connected) { Text("Від'єднатися") }
            }
        }
        item { Text("Термінал", style = MaterialTheme.typography.titleSmall) }
        items(state.log) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun BleState.color() = when (this) {
    BleState.DISCONNECTED -> CalibratingColor
    BleState.SCANNING, BleState.CONNECTING -> VibrationColor
    BleState.CONNECTED, BleState.DATA_TRANSFER -> QuietColor
}
