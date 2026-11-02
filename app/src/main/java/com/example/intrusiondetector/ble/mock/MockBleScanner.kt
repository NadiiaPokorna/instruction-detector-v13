package com.example.intrusiondetector.ble.mock

import com.example.intrusiondetector.ble.BleDevice
import com.example.intrusiondetector.ble.BleLogger
import com.example.intrusiondetector.ble.HubProfile
import com.example.intrusiondetector.ble.IBleScanner
import com.example.intrusiondetector.ble.ScanEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Імітація сканування: через 2 с «знаходить» хаб, через 3–4 с — кілька чужих пристроїв.
 * Пристрої рекламуються кожні [ADVERTISING_MS] мс, повторна реклама оновлює RSSI.
 */
class MockBleScanner(
    private val logger: BleLogger,
    private val random: Random = Random.Default,
) : IBleScanner {

    /** Пристрій в ефірі: з'являється через [appearsAtMs] від початку сканування. */
    private class Advertiser(
        val name: String,
        val address: String,
        val rssi: Int,
        val isTarget: Boolean,
        val appearsAtMs: Long,
    )

    private val air = listOf(
        Advertiser(HubProfile.HUB_NAME, HubProfile.HUB_ADDRESS, -55, isTarget = true, appearsAtMs = 2_000),
        Advertiser("Mi Band 7", "C8:47:8C:2A:91:3E", -71, isTarget = false, appearsAtMs = 3_000),
        Advertiser("JBL Flip 5", "00:42:79:B1:5C:07", -80, isTarget = false, appearsAtMs = 3_500),
        Advertiser("[невідомий]", "5F:12:E4:9A:03:C1", -92, isTarget = false, appearsAtMs = 4_000),
    )

    override fun scan(timeoutMs: Long): Flow<ScanEvent> = flow {
        logger.sys("Сканування запущено, таймаут ${timeoutMs / 1000} с")
        emit(ScanEvent.Started)
        // Ключ — MAC: повторна реклама того самого пристрою оновлює запис, а не додає новий.
        val found = LinkedHashMap<String, BleDevice>()
        var elapsed = 0L
        try {
            while (elapsed < timeoutMs) {
                val step = minOf(ADVERTISING_MS, timeoutMs - elapsed)
                delay(step)
                elapsed += step
                val visible = air.filter { elapsed >= it.appearsAtMs }
                if (visible.isEmpty()) continue
                for (a in visible) {
                    val device = BleDevice(a.name, a.address, a.rssi + random.nextInt(-3, 4), a.isTarget)
                    if (found.put(a.address, device) == null) {
                        val mark = if (a.isTarget) " — Intrusion Hub" else ""
                        logger.sys("Знайдено ${a.name} (${a.address}), ${device.rssi} dBm$mark")
                    }
                }
                emit(ScanEvent.Devices(found.values.toList()))
            }
        } catch (e: CancellationException) {
            logger.sys("Сканування зупинено")
            throw e
        }
        logger.sys("Сканування завершено за таймаутом, знайдено ${found.size}")
        emit(ScanEvent.Finished("таймаут ${timeoutMs / 1000} с"))
    }

    private companion object {
        const val ADVERTISING_MS = 500L
    }
}
