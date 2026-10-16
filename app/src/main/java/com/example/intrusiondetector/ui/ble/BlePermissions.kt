package com.example.intrusiondetector.ui.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Дозволи, потрібні для сканування і підключення на Android 12+ (minSdk 31). */
val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/** Стан дозволів BLE і запит на них. */
@Stable
class BlePermissionGate(
    val granted: Boolean,
    /** Користувач відмовив — треба показати пояснення. */
    val denied: Boolean,
    private val launch: () -> Unit,
) {
    fun request() = launch()
}

private fun hasBlePermissions(context: Context) = BLE_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Запит дозволів під час виконання. [onGranted] викликається, коли користувач надав дозволи
 * у системному діалозі, — так сканування стартує одразу після згоди.
 */
@Composable
fun rememberBlePermissionGate(onGranted: () -> Unit): BlePermissionGate {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val currentOnGranted by rememberUpdatedState(onGranted)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        denied = !granted
        if (granted) currentOnGranted()
    }

    // Дозвіл могли надати або відкликати в налаштуваннях, поки застосунок був у фоні.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasBlePermissions(context)
        if (granted) denied = false
    }

    return BlePermissionGate(granted, denied && !granted) { launcher.launch(BLE_PERMISSIONS) }
}

/** Пояснення, навіщо потрібні дозволи, з повторним запитом. */
@Composable
fun BlePermissionRationale(onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Без дозволу «Пристрої поблизу» застосунок не може шукати хаб і підключатися до нього.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRetry) { Text("Надати дозвіл") }
            // Після повторної відмови система більше не показує діалог — лишаються налаштування.
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Налаштування") }
        }
    }
}
