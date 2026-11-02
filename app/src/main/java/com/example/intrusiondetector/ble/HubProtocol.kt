package com.example.intrusiondetector.ble

import com.example.intrusiondetector.sensor.AlarmCause
import com.example.intrusiondetector.sensor.SecurityState
import java.util.Locale

/**
 * Текстовий протокол обміну з хабом (ASCII).
 *
 * Телефон → хаб (Command, A103):   `<seq>|<CMD>|<payload>`, напр. `07|ALARM|KNOCK`
 * Хаб → телефон (Remote Event, A104): `<EVENT>|<payload>`, напр. `ACK|07`, `CMD|PING`
 * Хаб → телефон (Hub Status, A102):  `ARM=1;SIR=0;BAT=87`
 *
 * Одна характеристика при MTU 23 вміщує не більше [HubProfile.MAX_PAYLOAD] байт,
 * тому довший пакет відхиляється, а не обрізається.
 */

/** Результат кодування або розбору пакета. */
sealed interface ProtocolResult<out T> {
    data class Ok<T>(val value: T) : ProtocolResult<T>
    data class Error(val reason: String) : ProtocolResult<Nothing>
}

/** Стан детектора у пакеті `STATE`. */
enum class HubState { CAL, QUIET, VIB, INTR }

/** Причина тривоги у пакеті `ALARM`. */
enum class HubAlarm { KNOCK, SUST, TILT }

/** Команди телефон → хаб. */
sealed interface HubCommand {
    val name: String
    val payload: String

    data object Ping : HubCommand {
        override val name = "PING"
        override val payload = ""
    }

    /** Відповідь на `CMD|PING` від хаба. */
    data object Pong : HubCommand {
        override val name = "PONG"
        override val payload = ""
    }

    data class State(val state: HubState) : HubCommand {
        override val name = "STATE"
        override val payload get() = state.name
    }

    data class Alarm(val cause: HubAlarm) : HubCommand {
        override val name = "ALARM"
        override val payload get() = cause.name
    }

    data class Siren(val on: Boolean) : HubCommand {
        override val name = "SIREN"
        override val payload get() = if (on) "ON" else "OFF"
    }

    /** Довільний текст із терміналу. */
    data class Custom(val text: String) : HubCommand {
        override val name = "CUSTOM"
        override val payload get() = text
    }
}

/** Пакет телефон → хаб з номером для зіставлення з підтвердженням. */
data class HubPacket(val seq: Int, val command: HubCommand)

/** Стан хаба з характеристики Hub Status. */
data class HubStatus(val armed: Boolean, val siren: Boolean, val battery: Int)

/** Назва і версія прошивки з характеристики Device Info. */
data class HubDeviceInfo(val name: String, val firmware: String)

/** Події хаб → телефон. */
sealed interface HubEvent {
    /** Хаб прийняв пакет з номером [seq]. */
    data class Ack(val seq: Int) : HubEvent

    /** Хаб не зміг розібрати пакет з номером [seq]. */
    data class Nak(val seq: Int) : HubEvent

    /** Віддалена команда: скинути тривогу й поставити на охорону. */
    data object RemoteArm : HubEvent

    /** Хаб перевіряє зв'язок, телефон відповідає `PONG`. */
    data object RemotePing : HubEvent

    /** Notify з Hub Status. */
    data class Status(val status: HubStatus) : HubEvent
}

object HubProtocol {
    const val SEQ_MODULO = 100
    private const val SEP = "|"

    /** Тіло пакета без номера: `ALARM|KNOCK` або `PING`. */
    fun body(command: HubCommand): String =
        if (command.payload.isEmpty()) command.name else command.name + SEP + command.payload

    /** Повний пакет `<seq>|<body>` з перевіркою ASCII і ліміту 20 байт. */
    fun frame(seq: Int, body: String): ProtocolResult<String> {
        if (seq !in 0 until SEQ_MODULO) return ProtocolResult.Error("seq поза межами 0–99: $seq")
        if (body.isEmpty()) return ProtocolResult.Error("порожній пакет")
        if (body.any { it.code !in 0x20..0x7E }) {
            return ProtocolResult.Error("дозволені лише ASCII-символи")
        }
        val raw = String.format(Locale.US, "%02d", seq) + SEP + body
        if (raw.length > HubProfile.MAX_PAYLOAD) {
            return ProtocolResult.Error("пакет ${raw.length} B > ${HubProfile.MAX_PAYLOAD} B")
        }
        return ProtocolResult.Ok(raw)
    }

    fun encode(seq: Int, command: HubCommand): ProtocolResult<String> = frame(seq, body(command))

    /** Розбір пакета телефон → хаб (використовує віртуальний хаб). */
    fun decodeCommand(raw: String): ProtocolResult<HubPacket> {
        val parts = raw.split(SEP, limit = 3)
        val seq = parseSeq(parts[0]) ?: return ProtocolResult.Error("немає номера пакета: '$raw'")
        if (parts.size < 2) return ProtocolResult.Error("немає команди: '$raw'")
        val payload = parts.getOrNull(2).orEmpty()
        val command: HubCommand = when (parts[1]) {
            "PING" -> HubCommand.Ping.takeIf { payload.isEmpty() }
            "PONG" -> HubCommand.Pong.takeIf { payload.isEmpty() }
            "STATE" -> enumOrNull<HubState>(payload)?.let { HubCommand.State(it) }
            "ALARM" -> enumOrNull<HubAlarm>(payload)?.let { HubCommand.Alarm(it) }
            "SIREN" -> when (payload) {
                "ON" -> HubCommand.Siren(true)
                "OFF" -> HubCommand.Siren(false)
                else -> null
            }
            "CUSTOM" -> HubCommand.Custom(payload)
            else -> null
        } ?: return ProtocolResult.Error("невідома команда: '$raw'")
        return ProtocolResult.Ok(HubPacket(seq, command))
    }

    /** Номер пакета з початку сирого рядка, навіть якщо решта некоректна (для NAK). */
    fun peekSeq(raw: String): Int? = parseSeq(raw.substringBefore(SEP))

    fun encodeEvent(event: HubEvent): String = when (event) {
        is HubEvent.Ack -> "ACK" + SEP + seqText(event.seq)
        is HubEvent.Nak -> "NAK" + SEP + seqText(event.seq)
        HubEvent.RemoteArm -> "CMD" + SEP + "ARM"
        HubEvent.RemotePing -> "CMD" + SEP + "PING"
        is HubEvent.Status -> encodeStatus(event.status)
    }

    /** Розбір Notify з Remote Event. */
    fun decodeEvent(raw: String): ProtocolResult<HubEvent> {
        val parts = raw.split(SEP)
        if (parts.size != 2) return ProtocolResult.Error("некоректна подія: '$raw'")
        val (type, payload) = parts
        val event = when (type) {
            "ACK" -> parseSeq(payload)?.let { HubEvent.Ack(it) }
            "NAK" -> parseSeq(payload)?.let { HubEvent.Nak(it) }
            "CMD" -> when (payload) {
                "ARM" -> HubEvent.RemoteArm
                "PING" -> HubEvent.RemotePing
                else -> null
            }
            else -> null
        } ?: return ProtocolResult.Error("невідома подія: '$raw'")
        return ProtocolResult.Ok(event)
    }

    fun encodeStatus(status: HubStatus): String =
        "ARM=${status.armed.bit()};SIR=${status.siren.bit()};BAT=${status.battery}"

    fun decodeStatus(raw: String): ProtocolResult<HubStatus> {
        val fields = raw.split(";").mapNotNull {
            val kv = it.split("=")
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val armed = fields["ARM"]?.toBitOrNull()
        val siren = fields["SIR"]?.toBitOrNull()
        val battery = fields["BAT"]?.toIntOrNull()?.takeIf { it in 0..100 }
        if (armed == null || siren == null || battery == null) {
            return ProtocolResult.Error("некоректний статус: '$raw'")
        }
        return ProtocolResult.Ok(HubStatus(armed, siren, battery))
    }

    fun encodeDeviceInfo(info: HubDeviceInfo): String = "${info.name};${info.firmware}"

    fun decodeDeviceInfo(raw: String): ProtocolResult<HubDeviceInfo> {
        val parts = raw.split(";")
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            return ProtocolResult.Error("некоректний Device Info: '$raw'")
        }
        return ProtocolResult.Ok(HubDeviceInfo(parts[0], parts[1]))
    }

    private fun seqText(seq: Int) = String.format(Locale.US, "%02d", seq)

    private fun parseSeq(text: String): Int? =
        if (text.length == 2 && text.all { it.isDigit() }) text.toInt() else null

    private fun Boolean.bit() = if (this) 1 else 0

    private fun String.toBitOrNull(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}

/** Стан детектора ЛР1 → payload пакета `STATE`. */
fun SecurityState.toHubState(): HubState = when (this) {
    SecurityState.CALIBRATING -> HubState.CAL
    SecurityState.QUIET -> HubState.QUIET
    SecurityState.VIBRATION -> HubState.VIB
    SecurityState.INTRUSION -> HubState.INTR
}

/** Причина тривоги ЛР1 → payload пакета `ALARM`. */
fun AlarmCause.toHubAlarm(): HubAlarm? = when (this) {
    AlarmCause.NONE -> null
    AlarmCause.KNOCKS -> HubAlarm.KNOCK
    AlarmCause.SUSTAINED_VIBRATION -> HubAlarm.SUST
    AlarmCause.TILT -> HubAlarm.TILT
}
