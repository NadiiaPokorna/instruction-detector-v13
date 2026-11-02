package com.example.intrusiondetector.ble.mock

import com.example.intrusiondetector.ble.HubCommand
import com.example.intrusiondetector.ble.HubDeviceInfo
import com.example.intrusiondetector.ble.HubEvent
import com.example.intrusiondetector.ble.HubProfile
import com.example.intrusiondetector.ble.HubProtocol
import com.example.intrusiondetector.ble.HubState
import com.example.intrusiondetector.ble.HubStatus
import com.example.intrusiondetector.ble.ProtocolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Відповідь хаба: Notify у характеристику [characteristic]. */
data class HubReply(val characteristic: UUID, val text: String)

/**
 * «Прошивка» віртуального хаба: розбирає команди, змінює свій стан і формує відповіді.
 * Таймінги і втрати пакетів — справа [MockGattLink], тут лише логіка.
 */
class VirtualHub(initial: HubStatus = HubStatus(armed = true, siren = false, battery = 87)) {
    private val _status = MutableStateFlow(initial)
    val status: StateFlow<HubStatus> = _status.asStateFlow()

    /** Скільки пакетів хаб реально обробив (без дублікатів і NAK) — для тестів. */
    @Volatile var processedCount = 0
        private set

    /** Останні номери пакетів: повтор з тим самим `seq` підтверджується, але не виконується вдруге. */
    private val recentSeqs = ArrayDeque<Int>()

    val deviceInfo: String = HubProtocol.encodeDeviceInfo(HubDeviceInfo(HubProfile.HUB_NAME, HubProfile.FIRMWARE))

    @Synchronized
    fun onWrite(raw: String): List<HubReply> {
        val packet = when (val decoded = HubProtocol.decodeCommand(raw)) {
            is ProtocolResult.Error -> {
                // Без номера пакета навіть NAK надіслати нікому.
                val seq = HubProtocol.peekSeq(raw) ?: return emptyList()
                return listOf(event(HubEvent.Nak(seq)))
            }
            is ProtocolResult.Ok -> decoded.value
        }
        val ack = event(HubEvent.Ack(packet.seq))
        if (packet.seq in recentSeqs) return listOf(ack)
        recentSeqs.addLast(packet.seq)
        if (recentSeqs.size > DEDUP_WINDOW) recentSeqs.removeFirst()
        processedCount++

        val before = _status.value
        val after = when (val command = packet.command) {
            is HubCommand.Siren -> before.copy(siren = command.on)
            is HubCommand.Alarm -> before.copy(armed = true, siren = true)
            // Телефон заново ставить на охорону — сирену можна вимкнути.
            is HubCommand.State -> if (command.state == HubState.CAL) before.copy(armed = true, siren = false) else before
            else -> before
        }
        _status.value = after
        return if (after != before) listOf(ack, statusReply()) else listOf(ack)
    }

    /** Кнопка на корпусі хаба: вимкнути сирену і попросити телефон стати на охорону. */
    @Synchronized
    fun pressArmButton(): List<HubReply> {
        _status.value = _status.value.copy(armed = true, siren = false)
        return listOf(statusReply(), event(HubEvent.RemoteArm))
    }

    @Synchronized
    fun drainBattery(): HubReply? {
        val current = _status.value
        if (current.battery <= 1) return null
        _status.value = current.copy(battery = current.battery - 1)
        return statusReply()
    }

    fun ping(): HubReply = event(HubEvent.RemotePing)

    private fun statusReply() = HubReply(HubProfile.HUB_STATUS, HubProtocol.encodeStatus(_status.value))

    private fun event(event: HubEvent) = HubReply(HubProfile.REMOTE_EVENT, HubProtocol.encodeEvent(event))

    private companion object {
        const val DEDUP_WINDOW = 16
    }
}
