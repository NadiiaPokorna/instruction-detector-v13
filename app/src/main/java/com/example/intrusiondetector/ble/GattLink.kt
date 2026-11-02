package com.example.intrusiondetector.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Notify від пристрою: характеристика і сире значення. */
class GattNotification(val characteristic: UUID, val value: ByteArray)

/** Помилка GATT-операції (аналог статусу != GATT_SUCCESS у колбеках BluetoothGatt). */
class GattException(message: String) : Exception(message)

/**
 * Низькорівневий GATT-канал: те, що в Android дає `BluetoothGatt` + `BluetoothGattCallback`,
 * але у вигляді suspend-функцій. Кожна функція завершується, коли прийшов відповідний колбек,
 * або кидає [GattException].
 *
 * Реалізації: [com.example.intrusiondetector.ble.mock.MockGattLink] зараз,
 * `AndroidGattLink` на `BluetoothGatt` — у майбутньому. Надійність (ACK, повтори, черга,
 * перепідключення) живе рівнем вище, у [HubConnector], і від реалізації не залежить.
 */
interface GattLink {
    /** `onCharacteristicChanged` — Notify від пристрою. */
    val notifications: Flow<GattNotification>

    /** Неочікуваний розрив (`onConnectionStateChange` → `STATE_DISCONNECTED`), з причиною. */
    val linkLost: Flow<String>

    /** `connectGatt()` → `STATE_CONNECTED`. */
    suspend fun connect(device: BleDevice)

    /** `discoverServices()` → `onServicesDiscovered`. Повертає UUID знайдених сервісів. */
    suspend fun discoverServices(): List<UUID>

    /** `writeDescriptor()` — для CCCD вмикає Notify характеристики. */
    suspend fun writeDescriptor(characteristic: UUID, descriptor: UUID, value: ByteArray)

    /** `writeCharacteristic()` → `onCharacteristicWrite`. */
    suspend fun write(characteristic: UUID, value: ByteArray)

    /** `readCharacteristic()` → `onCharacteristicRead`. */
    suspend fun read(characteristic: UUID): ByteArray

    /** Ручне від'єднання: [linkLost] при цьому не надсилається. */
    suspend fun disconnect()
}
