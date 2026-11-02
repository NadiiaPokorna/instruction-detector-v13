package com.example.intrusiondetector.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.sensor.SecurityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HubUiState(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val permissionDenied: Boolean = false,
    val connection: BleState = BleState.DISCONNECTED,
    val log: List<String> = emptyList(),
)

/** Стан вкладки «Хаб BLE». Отримує стан детектора, щоб повідомляти хабу про тривогу. */
class HubViewModel(private val sensor: StateFlow<IntrusionUiState>) : ViewModel() {
    private val scanner = MockBleScanner()
    private val connector: IBleConnector = MockBleConnector()

    private val local = MutableStateFlow(HubUiState())

    val uiState: StateFlow<HubUiState> =
        combine(local, connector.state, connector.log) { s, connection, log ->
            s.copy(connection = connection, log = log)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HubUiState())

    init {
        // Тривога надсилається один раз — у момент переходу детектора в INTRUSION.
        viewModelScope.launch {
            var prev = sensor.value.snapshot.state
            sensor.collect {
                val snapshot = it.snapshot
                if (snapshot.state == SecurityState.INTRUSION && prev != SecurityState.INTRUSION &&
                    connector.state.value == BleState.CONNECTED
                ) {
                    connector.sendData("ALARM=${snapshot.cause.name}")
                }
                prev = snapshot.state
            }
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        local.update { it.copy(permissionDenied = !granted) }
        if (granted) scan()
    }

    fun scan() {
        if (local.value.scanning) return
        viewModelScope.launch {
            local.update { it.copy(scanning = true, devices = emptyList()) }
            val devices = scanner.scan()
            local.update { it.copy(scanning = false, devices = devices) }
        }
    }

    fun connect(device: BleDevice) {
        viewModelScope.launch { connector.connect(device) }
    }

    fun disconnect() = connector.disconnect()

    fun sendState() {
        viewModelScope.launch { connector.sendData("STATE=${sensor.value.snapshot.state.name}") }
    }

    companion object {
        fun factory(sensor: StateFlow<IntrusionUiState>) = viewModelFactory {
            initializer { HubViewModel(sensor) }
        }
    }
}
