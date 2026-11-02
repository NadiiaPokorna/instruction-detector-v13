package com.example.intrusiondetector.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Керування віртуальним хабом з UI. Є лише в Mock-реалізації; для реального BLE
 * контейнер повертає `null`, і відповідні елементи інтерфейсу не показуються.
 */
interface HubSimulator {
    /** Увімкнено імітацію перешкод (втрати пакетів, розриви). */
    val interference: StateFlow<Boolean>

    fun setInterference(enabled: Boolean)

    /** Натискання кнопки на хабі: хаб вимикає сирену і надсилає `CMD|ARM`. */
    fun pressArmButton()
}
