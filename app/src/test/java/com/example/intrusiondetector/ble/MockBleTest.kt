package com.example.intrusiondetector.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тести моку у віртуальному часі: delay() не чекає насправді. */
@OptIn(ExperimentalCoroutinesApi::class)
class MockBleTest {

    private val hub = BleDevice(HUB_NAME, HUB_ADDRESS, -55)

    @Test
    fun scan_returnsHubAfterTwoSeconds() = runTest {
        val devices = MockBleScanner().scan()
        assertEquals(2000L, currentTime)
        assertTrue(devices.any { it.name == HUB_NAME && it.address == HUB_ADDRESS })
    }

    @Test
    fun connect_becomesConnectedAfterOneSecond() = runTest {
        val connector = MockBleConnector()
        assertTrue(connector.connect(hub))
        assertEquals(1000L, currentTime)
        assertEquals(BleState.CONNECTED, connector.state.value)
    }

    @Test
    fun connect_toForeignDevice_fails() = runTest {
        val connector = MockBleConnector()
        assertFalse(connector.connect(BleDevice("Mi Band 7", "C8:47:8C:2A:91:3E", -78)))
        assertEquals(BleState.DISCONNECTED, connector.state.value)
    }

    @Test
    fun sendData_withoutConnection_fails() = runTest {
        val connector = MockBleConnector()
        assertFalse(connector.sendData("ALARM=KNOCKS"))
        assertEquals(BleState.DISCONNECTED, connector.state.value)
    }
}
