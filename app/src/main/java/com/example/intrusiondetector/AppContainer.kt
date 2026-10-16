package com.example.intrusiondetector

import com.example.intrusiondetector.ble.BleLogger
import com.example.intrusiondetector.ble.HubSimulator
import com.example.intrusiondetector.ble.IBleConnector
import com.example.intrusiondetector.ble.IBleScanner
import com.example.intrusiondetector.ble.mock.MockBleConnector
import com.example.intrusiondetector.ble.mock.MockBleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Простий контейнер залежностей: один екземпляр сканера, конектора і логера на весь застосунок.
 * Щоб перейти на реальний BLE, достатньо замінити тут Mock-реалізації.
 */
class AppContainer {
    /** Область застосунку: з'єднання з хабом переживає Activity і поворот екрана. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleLogger = BleLogger()

    val bleScanner: IBleScanner = MockBleScanner(bleLogger)

    private val mockConnector = MockBleConnector(bleLogger, appScope)

    val bleConnector: IBleConnector = mockConnector

    /** Керування віртуальним хабом; для реального BLE — `null`. */
    val hubSimulator: HubSimulator? = mockConnector
}
