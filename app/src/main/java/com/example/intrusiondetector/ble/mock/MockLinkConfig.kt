package com.example.intrusiondetector.ble.mock

/** Параметри імітації радіоканалу. */
data class MockLinkConfig(
    /** Ймовірність втрати пакета в ефірі, 0..1: запис у Command і невдале підключення. */
    val packetLoss: Double = 0.0,
    /** Ймовірність втрати Notify від хаба (ACK, статус); за замовчуванням така сама. */
    val notifyLoss: Double = packetLoss,
    /** Ймовірність випадкового розриву при кожній перевірці раз на [faultCheckMs]. */
    val dropChance: Double = 0.0,
    val faultCheckMs: Long = 5_000,
    val connectMs: Long = 800,
    val discoverMs: Long = 500,
    /** Затримка відповіді хаба (ACK, Notify), мс. */
    val replyDelayMs: LongRange = 50L..150L,
    /** Як часто хаб перевіряє зв'язок командою `CMD|PING`. */
    val pingIntervalMs: Long = 15_000,
    /** Як часто розряджається батарея хаба на 1%. */
    val batteryDrainMs: Long = 60_000,
) {
    companion object {
        /** Перемикач «Перешкоди»: 30% втрат і рідкісні розриви. */
        val Interference = MockLinkConfig(packetLoss = 0.3, dropChance = 0.1)
    }
}
