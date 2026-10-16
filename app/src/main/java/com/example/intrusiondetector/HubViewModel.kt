package com.example.intrusiondetector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.ble.BleConnectionState
import com.example.intrusiondetector.ble.BleDevice
import com.example.intrusiondetector.ble.BleLogger
import com.example.intrusiondetector.ble.HubCommand
import com.example.intrusiondetector.ble.HubEvent
import com.example.intrusiondetector.ble.HubProtocol
import com.example.intrusiondetector.ble.HubSimulator
import com.example.intrusiondetector.ble.HubStatus
import com.example.intrusiondetector.ble.IBleConnector
import com.example.intrusiondetector.ble.IBleScanner
import com.example.intrusiondetector.ble.LogLine
import com.example.intrusiondetector.ble.ProtocolResult
import com.example.intrusiondetector.ble.ScanEvent
import com.example.intrusiondetector.ble.device
import com.example.intrusiondetector.ble.send
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HubUiState(
    val connection: BleConnectionState = BleConnectionState.Disconnected(),
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    /** Останній відомий стан хаба (з Notify або читання). */
    val hubStatus: HubStatus? = null,
    val log: List<LogLine> = emptyList(),
    /** Є віртуальний хаб, яким можна керувати (Mock). */
    val simulatorAvailable: Boolean = false,
    val interference: Boolean = false,
    /** Помилка введення в терміналі, напр. пакет > 20 байт. */
    val inputError: String? = null,
) {
    /** Стан для бейджа: сканування ведеться у ViewModel, а не в конекторі. */
    val displayState: BleConnectionState
        get() = if (scanning && connection.device == null) {
            BleConnectionState.Scanning
        } else {
            connection
        }
}

/** Локальна частина стану, яка не приходить із конектора. */
private data class HubLocalState(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val hubStatus: HubStatus? = null,
    val inputError: String? = null,
)

/**
 * Екран «Хаб BLE»: сканування, підключення, команди і термінал.
 * Працює лише з інтерфейсами [IBleScanner] та [IBleConnector].
 */
class HubViewModel(
    private val scanner: IBleScanner,
    private val connector: IBleConnector,
    private val logger: BleLogger,
    private val simulator: HubSimulator?,
) : ViewModel() {

    private val local = MutableStateFlow(HubLocalState())
    private var scanJob: Job? = null

    val uiState: StateFlow<HubUiState> = combine(
        connector.connectionState,
        local,
        logger.lines,
        simulator?.interference ?: flowOf(false),
    ) { connection, l, log, interference ->
        HubUiState(
            connection = connection,
            scanning = l.scanning,
            devices = l.devices,
            hubStatus = l.hubStatus,
            log = log,
            simulatorAvailable = simulator != null,
            interference = interference,
            inputError = l.inputError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HubUiState(simulatorAvailable = simulator != null))

    init {
        viewModelScope.launch {
            connector.incoming.collect { event ->
                if (event is HubEvent.Status) local.update { it.copy(hubStatus = event.status) }
            }
        }
    }

    /** Запускати лише після отримання дозволів BLUETOOTH_SCAN / BLUETOOTH_CONNECT. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            local.update { it.copy(scanning = true, devices = emptyList()) }
            try {
                scanner.scan().collect { event ->
                    if (event is ScanEvent.Devices) local.update { it.copy(devices = event.devices) }
                }
            } finally {
                local.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(device: BleDevice) {
        // Як і в реальному BLE, перед підключенням сканування зупиняємо.
        stopScan()
        viewModelScope.launch {
            if (connector.connect(device).isSuccess) connector.readStatus()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connector.disconnect()
            local.update { it.copy(hubStatus = null) }
        }
    }

    fun ping() = sendCommand(HubCommand.Ping)

    fun setSiren(on: Boolean) = sendCommand(HubCommand.Siren(on))

    fun readStatus() {
        viewModelScope.launch { connector.readStatus() }
    }

    /**
     * Команда `CUSTOM` з терміналу. Повертає `false`, якщо пакет не влазить у 20 байт
     * (поле вводу тоді не очищується).
     */
    fun sendCustom(text: String): Boolean {
        val command = HubCommand.Custom(text.trim())
        // Номер завжди двоцифровий, тому довжину можна перевірити заздалегідь.
        val check = HubProtocol.encode(0, command)
        if (check is ProtocolResult.Error) {
            local.update { it.copy(inputError = "Не надіслано: ${check.reason}") }
            return false
        }
        local.update { it.copy(inputError = null) }
        sendCommand(command)
        return true
    }

    fun clearInputError() = local.update { it.copy(inputError = null) }

    fun clearLog() = logger.clear()

    fun setInterference(enabled: Boolean) {
        simulator?.setInterference(enabled)
        logger.sys(if (enabled) "Перешкоди увімкнено: 30% втрат, випадкові розриви" else "Перешкоди вимкнено")
    }

    fun pressHubArmButton() = simulator?.pressArmButton()

    private fun sendCommand(command: HubCommand) {
        // Результат (ACK, повтори, помилка) видно в терміналі.
        viewModelScope.launch { connector.send(command) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as IntrusionApp).container
                HubViewModel(
                    container.bleScanner,
                    container.bleConnector,
                    container.bleLogger,
                    container.hubSimulator,
                )
            }
        }
    }
}
