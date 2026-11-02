package com.example.intrusiondetector.ble

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

const val HUB_NAME = "ID-Hub-13"
const val HUB_ADDRESS = "D4:13:A1:00:1E:13"
const val MAX_LOG_LINES = 50

/** Імітація BLE-сканера: емулятор не має справжнього Bluetooth. */
class MockBleScanner {
    suspend fun scan(): List<BleDevice> {
        delay(2000) // типовий час сканування
        return listOf(
            BleDevice(HUB_NAME, HUB_ADDRESS, -55),
            BleDevice("Mi Band 7", "C8:47:8C:2A:91:3E", -78),
        )
    }
}

/** Імітація GATT-з'єднання з хабом. Усі затримки — delay(), потік не блокується. */
class MockBleConnector : IBleConnector {
    private val _state = MutableStateFlow(BleState.DISCONNECTED)
    override val state: StateFlow<BleState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    override val log: StateFlow<List<String>> = _log.asStateFlow()

    override suspend fun connect(device: BleDevice): Boolean {
        if (device.address != HUB_ADDRESS) {
            log("ERR ${device.name}: немає сервісу ${HubUuids.SERVICE}")
            return false
        }
        _state.value = BleState.CONNECTING
        log("Підключення до ${device.name} (${device.address})")
        delay(1000)
        _state.value = BleState.CONNECTED
        log("Знайдено сервіс ${HubUuids.SERVICE}, увімкнено Notify (CCCD)")
        return true
    }

    override fun disconnect() {
        _state.value = BleState.DISCONNECTED
        log("Від'єднано")
    }

    override suspend fun sendData(data: String): Boolean {
        if (_state.value != BleState.CONNECTED) {
            log("ERR немає з'єднання, не надіслано: $data")
            return false
        }
        _state.value = BleState.DATA_TRANSFER
        log("TX → COMMAND $data")
        delay(200)
        // Хаб підтверджує прийом через Notify характеристики STATUS
        log("RX ← STATUS ACK")
        _state.value = BleState.CONNECTED
        return true
    }

    private fun log(text: String) {
        Log.d("BleMock", text)
        val line = "${LocalTime.now().format(TIME)} $text"
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
