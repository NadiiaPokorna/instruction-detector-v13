package com.example.intrusiondetector.ble

/**
 * Машина станів з'єднання:
 * Disconnected → Scanning → Connecting → DiscoveringServices → Connected ⇄ DataTransfer
 * → Disconnecting → Disconnected, плюс Reconnecting і Error.
 */
sealed interface BleConnectionState {
    /** [message] — причина, якщо з'єднання втрачено не з волі користувача. */
    data class Disconnected(val message: String? = null) : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val device: BleDevice) : BleConnectionState
    data class DiscoveringServices(val device: BleDevice) : BleConnectionState
    data class Connected(val device: BleDevice) : BleConnectionState

    /** Пакет відправлено, чекаємо ACK. */
    data class DataTransfer(val device: BleDevice) : BleConnectionState
    data object Disconnecting : BleConnectionState
    data class Reconnecting(val device: BleDevice, val attempt: Int) : BleConnectionState
    data class Error(val reason: String) : BleConnectionState
}

/** Чи можна зараз надсилати дані. */
val BleConnectionState.isLinkUp: Boolean
    get() = this is BleConnectionState.Connected || this is BleConnectionState.DataTransfer

/** Пристрій, з яким встановлено (або встановлюється) з'єднання. */
val BleConnectionState.device: BleDevice?
    get() = when (this) {
        is BleConnectionState.Connecting -> device
        is BleConnectionState.DiscoveringServices -> device
        is BleConnectionState.Connected -> device
        is BleConnectionState.DataTransfer -> device
        is BleConnectionState.Reconnecting -> device
        else -> null
    }
