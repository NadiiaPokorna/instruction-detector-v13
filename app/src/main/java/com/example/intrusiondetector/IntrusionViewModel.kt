package com.example.intrusiondetector

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import com.example.intrusiondetector.sensor.AlarmCause
import com.example.intrusiondetector.sensor.IntrusionDetector
import com.example.intrusiondetector.sensor.SecuritySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
)

const val HISTORY_SIZE = 150 // ~3 с при SENSOR_DELAY_GAME (~50 Гц)
const val MAX_EVENTS = 20

/**
 * Зберігає детектор і стан інтерфейсу при зміні конфігурації (поворот екрана).
 * Activity передає сирі дані сенсора в [onSample].
 */
class IntrusionViewModel : ViewModel() {
    private val detector = IntrusionDetector()

    private val _uiState = MutableStateFlow(IntrusionUiState())
    val uiState: StateFlow<IntrusionUiState> = _uiState.asStateFlow()

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

    private fun log(text: String, critical: Boolean = false) {
        val event = SecurityEvent(System.currentTimeMillis(), text, critical)
        _uiState.update { it.copy(events = (listOf(event) + it.events).take(MAX_EVENTS)) }
    }
}

fun AlarmCause.description(): String = when (this) {
    AlarmCause.NONE -> "-"
    AlarmCause.KNOCKS -> "серія ударів / стуків"
    AlarmCause.SUSTAINED_VIBRATION -> "тривала сильна вібрація"
    AlarmCause.TILT -> "зміна нахилу поверхні"
}
