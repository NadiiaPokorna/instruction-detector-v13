package com.example.intrusiondetector.ui.ble

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.intrusiondetector.HubUiState
import com.example.intrusiondetector.HubViewModel
import com.example.intrusiondetector.ble.BleConnectionState
import com.example.intrusiondetector.ble.BleDevice
import com.example.intrusiondetector.ble.HubProfile
import com.example.intrusiondetector.ble.LogDir
import com.example.intrusiondetector.ble.LogLine
import com.example.intrusiondetector.ble.device
import com.example.intrusiondetector.ble.isLinkUp
import com.example.intrusiondetector.ui.AppIcons
import com.example.intrusiondetector.ui.theme.BleBusyColor
import com.example.intrusiondetector.ui.theme.BleConnectedColor
import com.example.intrusiondetector.ui.theme.BleErrorColor
import com.example.intrusiondetector.ui.theme.BleIdleColor
import com.example.intrusiondetector.ui.theme.BleReconnectingColor
import com.example.intrusiondetector.ui.theme.BleTransferColor
import com.example.intrusiondetector.ui.theme.TerminalBackground
import com.example.intrusiondetector.ui.theme.TerminalErr
import com.example.intrusiondetector.ui.theme.TerminalRx
import com.example.intrusiondetector.ui.theme.TerminalSys
import com.example.intrusiondetector.ui.theme.TerminalText
import com.example.intrusiondetector.ui.theme.TerminalTx
import com.example.intrusiondetector.ui.theme.TerminalWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun BleConnectionState.label(): String = when (this) {
    is BleConnectionState.Disconnected -> "Disconnected"
    BleConnectionState.Scanning -> "Scanning"
    is BleConnectionState.Connecting -> "Connecting"
    is BleConnectionState.DiscoveringServices -> "Discovering services"
    is BleConnectionState.Connected -> "Connected"
    is BleConnectionState.DataTransfer -> "Data transfer"
    BleConnectionState.Disconnecting -> "Disconnecting"
    is BleConnectionState.Reconnecting -> "Reconnecting $attempt/3"
    is BleConnectionState.Error -> "Error"
}

private fun BleConnectionState.color(): Color = when (this) {
    is BleConnectionState.Disconnected, BleConnectionState.Disconnecting -> BleIdleColor
    BleConnectionState.Scanning, is BleConnectionState.Connecting, is BleConnectionState.DiscoveringServices -> BleBusyColor
    is BleConnectionState.Connected -> BleConnectedColor
    is BleConnectionState.DataTransfer -> BleTransferColor
    is BleConnectionState.Reconnecting -> BleReconnectingColor
    is BleConnectionState.Error -> BleErrorColor
}

/** Пояснення стану українською під бейджем. */
private fun BleConnectionState.hint(): String? = when (this) {
    is BleConnectionState.Disconnected -> message ?: "Немає з'єднання з хабом"
    BleConnectionState.Scanning -> "Пошук пристроїв поблизу…"
    is BleConnectionState.Connecting -> "Встановлення з'єднання…"
    is BleConnectionState.DiscoveringServices -> "Пошук сервісів і ввімкнення Notify…"
    is BleConnectionState.Connected -> null
    is BleConnectionState.DataTransfer -> "Пакет надіслано, очікування ACK…"
    BleConnectionState.Disconnecting -> "Від'єднання…"
    is BleConnectionState.Reconnecting -> "З'єднання втрачено, спроба $attempt з 3"
    is BleConnectionState.Error -> "Помилка: $reason"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(viewModel: HubViewModel = viewModel(factory = HubViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Сканування не повинне тривати, коли екран не видно: перемкнули вкладку або згорнули застосунок.
    DisposableEffect(viewModel) { onDispose { viewModel.stopScan() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopScan() }

    val permissions = rememberBlePermissionGate(onGranted = viewModel::startScan)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Хаб BLE") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(state, onDisconnect = viewModel::disconnect)
            ScanCard(
                state = state,
                permissionDenied = permissions.denied,
                onScan = { if (permissions.granted) viewModel.startScan() else permissions.request() },
                onStop = viewModel::stopScan,
                onRequestPermission = permissions::request,
                onConnect = viewModel::connect,
            )
            if (state.connection.isLinkUp) {
                HubStatusCard(
                    state = state,
                    onPing = viewModel::ping,
                    onSiren = viewModel::setSiren,
                    onReadStatus = viewModel::readStatus,
                    onHubButton = viewModel::pressHubArmButton,
                )
            }
            TerminalCard(
                lines = state.log,
                inputError = state.inputError,
                enabled = state.connection.isLinkUp,
                onSend = viewModel::sendCustom,
                onInputChanged = viewModel::clearInputError,
                onClear = viewModel::clearLog,
            )
            if (state.simulatorAvailable) {
                InterferenceCard(state.interference, viewModel::setInterference)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** 1. Статус з'єднання. */
@Composable
private fun ConnectionCard(state: HubUiState, onDisconnect: () -> Unit) {
    val display = state.displayState
    val badgeColor by animateColorAsState(display.color(), label = "bleState")
    val device = state.connection.device
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .background(badgeColor, RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(display.label(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            display.hint()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (display is BleConnectionState.Error) BleErrorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (device != null) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onDisconnect) { Text("Від'єднатися") }
            }
        }
    }
}

/** 2–3. Пошук і список знайдених пристроїв. */
@Composable
private fun ScanCard(
    state: HubUiState,
    permissionDenied: Boolean,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
    onConnect: (BleDevice) -> Unit,
) {
    val busy = state.connection is BleConnectionState.Connecting ||
        state.connection is BleConnectionState.DiscoveringServices ||
        state.connection is BleConnectionState.Reconnecting
    SectionCard("Пошук пристроїв") {
        if (state.scanning) {
            OutlinedButton(onClick = onStop) { Text("Зупинити") }
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = BleBusyColor)
        } else {
            Button(onClick = onScan, enabled = !busy) {
                Icon(AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Пошук пристроїв")
            }
        }
        if (permissionDenied) BlePermissionRationale(onRetry = onRequestPermission)

        if (state.devices.isEmpty()) {
            Text(
                if (state.scanning) "Поки нічого не знайдено…" else "Натисніть «Пошук пристроїв», щоб знайти хаб",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val connectedAddress = state.connection.device?.address
        state.devices.forEachIndexed { index, device ->
            if (index > 0) HorizontalDivider()
            DeviceRow(
                device = device,
                connected = device.address == connectedAddress,
                enabled = !busy && device.address != connectedAddress,
                onClick = { onConnect(device) },
            )
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, connected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (device.isTarget) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Intrusion Hub", BleConnectedColor)
                }
                if (connected) {
                    Spacer(Modifier.width(6.dp))
                    Tag("підключено", BleTransferColor)
                }
            }
            Text(device.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        SignalBars(device.rssi)
        Spacer(Modifier.width(6.dp))
        Text("${device.rssi} dBm", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Рівень сигналу: 4 смужки, як у статус-барі. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
        val barW = size.width / 4 - 2.dp.toPx()
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                if (i < level) on else off,
                topLeft = Offset(i * (barW + 2.dp.toPx()), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** 4. Стан хаба і команди. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubStatusCard(
    state: HubUiState,
    onPing: () -> Unit,
    onSiren: (Boolean) -> Unit,
    onReadStatus: () -> Unit,
    onHubButton: () -> Unit,
) {
    val status = state.hubStatus
    SectionCard("Стан хаба [${HubProfile.shortId(HubProfile.HUB_STATUS)}]") {
        if (status == null) {
            Text("Статус ще не отримано", style = MaterialTheme.typography.bodySmall)
        } else {
            StatusRow("Охорона", if (status.armed) "увімкнена" else "вимкнена", if (status.armed) BleConnectedColor else BleIdleColor)
            StatusRow("Сирена", if (status.siren) "ON" else "OFF", if (status.siren) BleErrorColor else BleIdleColor)
            StatusRow("Заряд", "${status.battery}%", if (status.battery > 20) BleConnectedColor else BleReconnectingColor)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPing) { Text("PING") }
            val sirenOn = status?.siren == true
            FilledTonalButton(onClick = { onSiren(!sirenOn) }) { Text(if (sirenOn) "Сирена OFF" else "Сирена ON") }
            FilledTonalButton(onClick = onReadStatus) { Text("Прочитати статус") }
        }
        if (state.simulatorAvailable) {
            TextButton(onClick = onHubButton) { Text("Кнопка на хабі: CMD|ARM (імітація)") }
        }
    }
}

@Composable
private fun StatusRow(name: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name)
        Text(value, color = color, fontWeight = FontWeight.Bold)
    }
}

private fun LogLine.color(): Color = when {
    dir == LogDir.ERR -> TerminalErr
    warning -> TerminalWarn
    dir == LogDir.TX -> TerminalTx
    dir == LogDir.RX -> TerminalRx
    else -> TerminalSys
}

/** 5. Термінал: журнал BLE-операцій і ручна відправка. */
@Composable
private fun TerminalCard(
    lines: List<LogLine>,
    inputError: String?,
    enabled: Boolean,
    onSend: (String) -> Boolean,
    onInputChanged: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var input by rememberSaveable { mutableStateOf("") }

    // Автопрокрутка до останнього рядка.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    val send = {
        if (input.isNotBlank() && onSend(input)) input = ""
    }

    SectionCard("Термінал") {
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(TerminalBackground, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            if (lines.isEmpty()) {
                Text("Журнал порожній", color = TerminalSys, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Row {
                        Text(
                            timeFormat.format(Date(line.timeMillis)) + " ",
                            color = TerminalText.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Text(
                            "${line.dir.name.padEnd(3)} ${line.text}",
                            color = line.color(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    onInputChanged()
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                label = { Text("CUSTOM") },
                placeholder = { Text("до 10 символів ASCII") },
                isError = inputError != null,
                supportingText = inputError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = send, enabled = enabled && input.isNotBlank()) { Text("Надіслати") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Рядків: ${lines.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClear) { Text("Очистити") }
        }
    }
}

/** 6. Перемикач «Перешкоди». */
@Composable
private fun InterferenceCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Перешкоди", style = MaterialTheme.typography.titleMedium)
                Text(
                    "30% втрат пакетів і випадкові розриви: перевірка повторів і перепідключення",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}
