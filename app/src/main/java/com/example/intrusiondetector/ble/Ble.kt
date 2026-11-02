package com.example.intrusiondetector.ble

import kotlinx.coroutines.flow.StateFlow

/** GATT-профіль охоронного хаба ID-Hub-13. */
object HubUuids {
    /** Сервіс хаба. */
    const val SERVICE = "0000a100-6964-4c13-9e2b-7a5c3f0d1e01"
    /** Read, Notify: статус хаба / підтвердження "ACK". */
    const val STATUS = "0000a102-6964-4c13-9e2b-7a5c3f0d1e01"
    /** Write: події від телефона ("ALARM=KNOCKS"). */
    const val COMMAND = "0000a103-6964-4c13-9e2b-7a5c3f0d1e01"
    /** Стандартний дескриптор, через який вмикається Notify. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

data class BleDevice(val name: String, val address: String, val rssi: Int)

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, DATA_TRANSFER }

interface IBleConnector {
    val state: StateFlow<BleState>
    /** Рядки терміналу: "12:05:31 TX → ALARM=KNOCKS". */
    val log: StateFlow<List<String>>
    suspend fun connect(device: BleDevice): Boolean
    fun disconnect()
    suspend fun sendData(data: String): Boolean
}
