package com.example.intrusiondetector.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * З'єднання з хабом. UI і ViewModel працюють лише з цим інтерфейсом,
 * тому Mock можна замінити реальною реалізацією на BluetoothGatt.
 */
interface IBleConnector {
    /** Поточний стан з'єднання — для UI. */
    val connectionState: StateFlow<BleConnectionState>

    /** Потік подій від пристрою (Notify з Remote Event та Hub Status). */
    val incoming: SharedFlow<HubEvent>

    suspend fun connect(device: BleDevice): Result<Unit>
    suspend fun disconnect()

    /**
     * Запис у характеристику Command. [data] — тіло пакета без номера (`ALARM|KNOCK`),
     * номер додає конектор. Повертає успіх, коли пристрій підтвердив пакет (ACK).
     */
    suspend fun sendData(data: String): Result<Unit>

    suspend fun readStatus(): Result<HubStatus>
    suspend fun readDeviceInfo(): Result<HubDeviceInfo>
}

/** Відправка типізованої команди. */
suspend fun IBleConnector.send(command: HubCommand): Result<Unit> = sendData(HubProtocol.body(command))
