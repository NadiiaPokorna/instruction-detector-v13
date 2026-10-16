package com.example.intrusiondetector.ble.mock

import com.example.intrusiondetector.ble.BleLogger
import com.example.intrusiondetector.ble.ConnectorTiming
import com.example.intrusiondetector.ble.HubConnector
import com.example.intrusiondetector.ble.HubSimulator
import com.example.intrusiondetector.ble.IBleConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Конектор до віртуального хаба: надійний [HubConnector] поверх [MockGattLink].
 * Для реального BLE достатньо передати в [HubConnector] інший [com.example.intrusiondetector.ble.GattLink].
 */
class MockBleConnector(
    val link: MockGattLink,
    logger: BleLogger,
    scope: CoroutineScope,
    timing: ConnectorTiming = ConnectorTiming(),
) : IBleConnector by HubConnector(link, logger, scope, timing), HubSimulator {

    constructor(
        logger: BleLogger,
        scope: CoroutineScope,
        config: MockLinkConfig = MockLinkConfig(),
        random: Random = Random.Default,
    ) : this(MockGattLink(VirtualHub(), MutableStateFlow(config), logger, scope, random), logger, scope)

    val hub: VirtualHub get() = link.hub

    private val _interference = MutableStateFlow(link.config.value.packetLoss > 0)
    override val interference: StateFlow<Boolean> = _interference.asStateFlow()

    override fun setInterference(enabled: Boolean) {
        link.config.value = if (enabled) MockLinkConfig.Interference else MockLinkConfig()
        _interference.value = enabled
    }

    override fun pressArmButton() = link.pressHubArmButton()
}
