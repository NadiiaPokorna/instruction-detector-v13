package com.example.intrusiondetector.actuator

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.BuildConfig
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.cloud.ControlState
import com.example.intrusiondetector.cloud.FirebaseApi
import com.example.intrusiondetector.cloud.createFirebaseApi
import com.example.intrusiondetector.cloud.deviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ControlUiState(
    val autoMode: Boolean = true,
    /** Віртуальна лампа, керується з Firebase. */
    val lampOn: Boolean = false,
    val cloudStatus: String = "",
    val deviceId: String = "",
    /** Журнал «HH:mm:ss джерело дія», найновіші зверху. */
    val log: List<String> = emptyList(),
)

private const val TAG = "Control"
private const val POLL_INTERVAL_MS = 1_000L
private const val LOG_SIZE = 20

/** ЛР4: автоматичне, ручне і хмарне (опитування) керування актуаторами. */
class ControlViewModel(
    app: Application,
    sensor: StateFlow<IntrusionUiState>,
) : ViewModel() {
    private val actuator = SmartActuator(app)
    private val engine = DecisionEngine()
    private val api = createFirebaseApi(BuildConfig.FIREBASE_DB_URL)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(
        ControlUiState(
            deviceId = deviceId(app),
            cloudStatus = if (api == null) "хмара не налаштована" else "опитування",
        )
    )
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()
    val actuatorState: StateFlow<ActuatorState> = actuator.state

    init {
        // Автоматичне керування: дані сенсора → правила → актуатори
        viewModelScope.launch {
            sensor.collect { ui ->
                val actions = engine.decide(ui.snapshot, System.currentTimeMillis())
                for (action in actions) {
                    if (_uiState.value.autoMode) {
                        execute(action)
                        log("AUTO", action.name)
                    } else {
                        log("AUTO", "${action.name} (авто-режим вимкнено)")
                    }
                }
            }
        }
        api?.let { viewModelScope.launch { pollCloud(it) } }
    }

    fun toggleTorch(on: Boolean) {
        actuator.toggleFlashlight(on)
        log("MANUAL", if (on) "TORCH_ON" else "TORCH_OFF")
    }

    fun alarm() {
        actuator.triggerAlarm()
        log("MANUAL", "ALARM")
    }

    fun setAutoMode(on: Boolean) {
        _uiState.update { it.copy(autoMode = on) }
        log("MANUAL", "авто-режим ${if (on) "увімкнено" else "вимкнено"}")
    }

    private fun execute(action: Action) = when (action) {
        Action.ALARM -> actuator.triggerAlarm()
        Action.TORCH_ON -> actuator.toggleFlashlight(true)
        Action.TORCH_OFF -> actuator.toggleFlashlight(false)
        Action.WARN_VIBRATION -> actuator.warn()
    }

    /** Cloud-to-Device: кожну секунду читає devices/{id}/control. */
    private suspend fun pollCloud(api: FirebaseApi) {
        val id = _uiState.value.deviceId
        var created = false
        var last: Boolean? = null
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) { api.getControl(id) }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
                val control = response.body()
                if (control == null && !created) {
                    // Вузла ще немає — створюємо, щоб він з'явився в консолі Firebase
                    withContext(Dispatchers.IO) { api.putControl(id, ControlState(false)) }
                    created = true
                }
                val led = control?.led_status ?: false
                if (last != null && led != last) log("CLOUD", "led_status=$led")
                last = led
                _uiState.update { it.copy(lampOn = led, cloudStatus = "опитування") }
            } catch (e: IOException) {
                Log.w(TAG, "Помилка опитування: ${e.message}")
                _uiState.update { it.copy(cloudStatus = "помилка") }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun log(source: String, action: String) {
        val line = "${timeFormat.format(Date())} $source $action"
        Log.d(TAG, line)
        _uiState.update { it.copy(log = (listOf(line) + it.log).take(LOG_SIZE)) }
    }

    companion object {
        fun factory(app: Application, sensor: StateFlow<IntrusionUiState>) = viewModelFactory {
            initializer { ControlViewModel(app, sensor) }
        }
    }
}
