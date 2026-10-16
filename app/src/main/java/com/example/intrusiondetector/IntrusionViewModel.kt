package com.example.intrusiondetector

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.ble.HubCommand
import com.example.intrusiondetector.ble.HubEvent
import com.example.intrusiondetector.ble.HubProtocol
import com.example.intrusiondetector.ble.IBleConnector
import com.example.intrusiondetector.ble.device
import com.example.intrusiondetector.ble.isLinkUp
import com.example.intrusiondetector.ble.send
import com.example.intrusiondetector.ble.toHubAlarm
import com.example.intrusiondetector.ble.toHubState
import com.example.intrusiondetector.sensor.AlarmCause
import com.example.intrusiondetector.sensor.IntrusionDetector
import com.example.intrusiondetector.sensor.SecuritySnapshot
import com.example.intrusiondetector.sensor.SecurityState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Запис у журналі подій. */
data class SecurityEvent(val timeMillis: Long, val text: String, val critical: Boolean)

data class IntrusionUiState(
    val sensorAvailable: Boolean = true,
    val listening: Boolean = false,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    val snapshot: SecuritySnapshot = SecuritySnapshot(),
    /** Останні [HISTORY_SIZE] значень динамічного прискорення для графіка, м/с². */
    val history: List<Float> = emptyList(),
    /** Журнал подій, найновіші зверху. */
    val events: List<SecurityEvent> = emptyList(),
    /** Хаб BLE підключений і може приймати події. */
    val hubConnected: Boolean = false,
    val hubName: String? = null,
)

const val HISTORY_SIZE = 150 // ~3 с при SENSOR_DELAY_GAME (~50 Гц)
const val MAX_EVENTS = 20

/** Скільки подій для хаба можна накопичити, поки триває відправка попередніх. */
private const val HUB_OUTBOX_SIZE = 16

/**
 * Зберігає детектор і стан інтерфейсу при зміні конфігурації (поворот екрана).
 * Activity передає сирі дані сенсора в [onSample].
 *
 * Якщо підключений хаб [hub], зміни стану й тривоги пересилаються йому.
 */
class IntrusionViewModel(private val hub: IBleConnector? = null) : ViewModel() {
    private val detector = IntrusionDetector()

    private val _uiState = MutableStateFlow(IntrusionUiState())
    val uiState: StateFlow<IntrusionUiState> = _uiState.asStateFlow()

    /**
     * Черга подій для хаба. Обробка сенсора лише кладе подію сюди (trySend не блокує),
     * а окрема корутина відправляє їх по одній і в тому ж порядку.
     */
    private val hubOutbox = Channel<HubCommand>(HUB_OUTBOX_SIZE, BufferOverflow.DROP_OLDEST)

    /** Останній стан, повідомлений хабу; `null` — після підключення треба надіслати заново. */
    private var lastSentState: SecurityState? = null

    init {
        hub?.let { connector ->
            viewModelScope.launch {
                for (command in hubOutbox) deliverToHub(connector, command)
            }
            viewModelScope.launch {
                connector.connectionState.collect { state ->
                    val connected = state.isLinkUp
                    if (connected && !_uiState.value.hubConnected) lastSentState = null
                    _uiState.update { it.copy(hubConnected = connected, hubName = state.device?.name) }
                }
            }
            viewModelScope.launch {
                connector.incoming.collect { event ->
                    if (event == HubEvent.RemoteArm) {
                        log("Хаб: віддалена команда ARM")
                        rearm()
                    }
                }
            }
        }
    }

    fun setSensorAvailable(available: Boolean) {
        _uiState.update { it.copy(sensorAvailable = available) }
    }

    fun onListeningChanged(listening: Boolean) {
        if (listening) {
            // Повернення з onPause: перезапуск фільтрів і повторне калібрування.
            detector.resume()
            log("Моніторинг запущено, калібрування положення")
        } else {
            log("Моніторинг призупинено (onPause)")
        }
        _uiState.update { it.copy(listening = listening) }
    }

    fun onAccuracyChanged(accuracy: Int) {
        _uiState.update { it.copy(accuracy = accuracy) }
    }

    fun onSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        val snapshot = detector.process(x, y, z, timestampNs)
        if (snapshot.impulseDetected) {
            log("Удар / стук: ${"%.1f".format(snapshot.dynamic)} м/с²")
        }
        if (snapshot.alarmTriggered) {
            log("ТРИВОГА: ${snapshot.cause.description()}", critical = true)
        }
        forwardToHub(snapshot)
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                history = (it.history + snapshot.dynamic).takeLast(HISTORY_SIZE),
            )
        }
    }

    /** Скинути тривогу і поставити на охорону заново. */
    fun rearm() {
        detector.arm()
        log("Поставлено на охорону, калібрування положення")
        _uiState.update { it.copy(snapshot = SecuritySnapshot(), history = emptyList()) }
    }

    private fun forwardToHub(snapshot: SecuritySnapshot) {
        if (hub?.connectionState?.value?.isLinkUp != true) return
        if (snapshot.state != lastSentState) {
            lastSentState = snapshot.state
            hubOutbox.trySend(HubCommand.State(snapshot.state.toHubState()))
        }
        if (snapshot.alarmTriggered) {
            snapshot.cause.toHubAlarm()?.let { hubOutbox.trySend(HubCommand.Alarm(it)) }
        }
    }

    private suspend fun deliverToHub(connector: IBleConnector, command: HubCommand) {
        val packet = HubProtocol.body(command)
        val result = connector.send(command)
        val isAlarm = command is HubCommand.Alarm
        when {
            // Зміни стану бувають часто (тихо ⇄ вібрація), тому в журнал іде лише тривога та невдачі.
            result.isSuccess && isAlarm -> log("Надіслано на хаб: $packet")
            result.isFailure -> log("Не доставлено на хаб: $packet (${result.exceptionOrNull()?.message})", critical = isAlarm)
        }
    }

    private fun log(text: String, critical: Boolean = false) {
        val event = SecurityEvent(System.currentTimeMillis(), text, critical)
        _uiState.update { it.copy(events = (listOf(event) + it.events).take(MAX_EVENTS)) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as IntrusionApp
                IntrusionViewModel(app.container.bleConnector)
            }
        }
    }
}

fun AlarmCause.description(): String = when (this) {
    AlarmCause.NONE -> "-"
    AlarmCause.KNOCKS -> "серія ударів / стуків"
    AlarmCause.SUSTAINED_VIBRATION -> "тривала сильна вібрація"
    AlarmCause.TILT -> "зміна нахилу поверхні"
}
