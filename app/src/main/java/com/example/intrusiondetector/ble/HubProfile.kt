package com.example.intrusiondetector.ble

import java.util.Locale
import java.util.UUID

/**
 * GATT-профіль охоронного смарт-хаба «ID-Hub-13».
 *
 * Усі UUID побудовані з одного базового 128-бітного UUID, у якому змінюється
 * лише 16-бітна частина (як у стандартних UUID Bluetooth SIG).
 */
object HubProfile {
    private const val BASE_SUFFIX = "-6964-4c13-9e2b-7a5c3f0d1e01"

    /** UUID з базового шаблону за 16-бітним ідентифікатором. */
    fun uuid16(short: Int): UUID =
        UUID.fromString(String.format(Locale.US, "0000%04x", short) + BASE_SUFFIX)

    /** Основний сервіс хаба. */
    val SERVICE: UUID = uuid16(0xA100)

    /** Read, хаб → телефон: назва і версія прошивки, `ID-Hub-13;fw1.0.2`. */
    val DEVICE_INFO: UUID = uuid16(0xA101)

    /** Read + Notify, хаб → телефон: стан хаба, `ARM=1;SIR=0;BAT=87`. */
    val HUB_STATUS: UUID = uuid16(0xA102)

    /** Write, телефон → хаб: команди та події від телефона. */
    val COMMAND: UUID = uuid16(0xA103)

    /** Notify, хаб → телефон: ACK/NAK і віддалені команди. */
    val REMOTE_EVENT: UUID = uuid16(0xA104)

    /** Client Characteristic Configuration Descriptor — вмикає Notify. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Значення CCCD для ввімкнення Notify (як BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE). */
    val ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)

    /** Стандартний MTU 23 мінус 3 байти заголовка ATT. */
    const val MAX_PAYLOAD = 20

    const val HUB_NAME = "ID-Hub-13"
    const val HUB_ADDRESS = "D4:13:A1:00:1E:13"
    const val FIRMWARE = "fw1.0.2"

    /** Коротка назва характеристики для логів: `A103`. */
    fun shortId(uuid: UUID): String =
        uuid.toString().substring(4, 8).uppercase(Locale.US)
}
