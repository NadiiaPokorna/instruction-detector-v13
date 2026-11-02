package com.example.intrusiondetector.ble

/**
 * Знайдений BLE-пристрій.
 * [address] — MAC у форматі `AA:BB:CC:DD:EE:FF`, [isTarget] — пристрій рекламує [HubProfile.SERVICE].
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isTarget: Boolean,
)

/** Події сканування. */
sealed interface ScanEvent {
    data object Started : ScanEvent

    /** Актуальний список пристроїв: повторна реклама оновлює RSSI, а не додає рядок. */
    data class Devices(val devices: List<BleDevice>) : ScanEvent

    data class Finished(val reason: String) : ScanEvent
}
