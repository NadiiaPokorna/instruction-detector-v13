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
import com.example.intrusiondetector.ml.Prediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/**
 * ЛР4: автоматичне, ручне і хмарне (опитування) керування актуаторами.
 *
 * @param ml ЛР6: підтверджені AI-аномалії (`AiViewModel.anomalies`) для правила R4.
 */
class ControlViewModel(
    app: Application,
    sensor: StateFlow<IntrusionUiState>,
    ml: SharedFlow<Prediction>,
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

    // ЛР5: події «автодії фактично виконано» для запису в Room (SensorViewModel).
    // SharedFlow, а не StateFlow: дія — подія, а не стан. StateFlow зберігає лише останнє
    // значення і пропускає однакові підряд (distinctUntilChanged), тож дві однакові тривоги
    // поспіль злилися б в одну. extraBufferCapacity + tryEmit: tryEmit не призупиняє корутину
    // і не блокує збір сенсора, а буфер на 16 подій покриває короткий сплеск.
    private val _executedActions = MutableSharedFlow<List<Action>>(extraBufferCapacity = 16)

    /**
     * ЛР5: списки автодій, які `DecisionEngine` вирішив і які реально виконано (авто-режим увімкнено).
     * `List<Action>`, а не по одній дії: R1 дає `ALARM + TORCH_ON` одночасно — одне рішення
     * означає один запис у БД, інакше на осі X з'явилися б дві точки з однаковим часом.
     * Ручні й хмарні дії сюди не потрапляють.
     */
    val executedActions: SharedFlow<List<Action>> = _executedActions.asSharedFlow()

    init {
        // Автоматичне керування: дані сенсора → правила → актуатори
        viewModelScope.launch {
            sensor.collect { ui ->
                runAuto(engine.decide(ui.snapshot, System.currentTimeMillis()))
            }
        }
        // ЛР6: R4 — підтверджена AI-аномалія → той самий шлях автодій, що й R1–R3 (авто-режим, журнал,
        // запис у БД через executedActions з actionTriggered = true)
        viewModelScope.launch {
            ml.collect { p ->
                val actions = engine.decideMl(p, sensor.value.snapshot.state, System.currentTimeMillis())
                runAuto(actions, prefix = "R4 ", suffix = " (ML ${(p.confidence * 100).toInt()}%)")
            }
        }
        api?.let { viewModelScope.launch { pollCloud(it) } }
    }

    /**
     * Виконує автодії, якщо авто-режим увімкнено, і журналює їх. Спільний шлях для R1–R3 і R4 (ЛР6).
     * [prefix]/[suffix] доповнюють рядок журналу, напр. `AUTO R4 ALARM (ML 92%)`.
     */
    private fun runAuto(actions: List<Action>, prefix: String = "", suffix: String = "") {
        for (action in actions) {
            if (_uiState.value.autoMode) {
                execute(action)
                log("AUTO", "$prefix${action.name}$suffix")
            } else {
                log("AUTO", "$prefix${action.name}$suffix (авто-режим вимкнено)")
            }
        }
        // ЛР5: повідомляємо про виконані автодії (лише залоговані не емітимо)
        if (_uiState.value.autoMode && actions.isNotEmpty()) _executedActions.tryEmit(actions)
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
        fun factory(app: Application, sensor: StateFlow<IntrusionUiState>, ml: SharedFlow<Prediction>) =
            viewModelFactory {
                initializer { ControlViewModel(app, sensor, ml) }
            }
    }
}
