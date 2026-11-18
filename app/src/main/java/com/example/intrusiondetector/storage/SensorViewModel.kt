package com.example.intrusiondetector.storage

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.actuator.Action
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ЛР5, шар ViewModel (MVVM): запис даних сенсора в Room і стан для екрана «Аналітика».

/** Стан екрана «Аналітика». */
data class AnalyticsUiState(
    /** Останні [MAX_CHART_POINTS] записів, хронологічно (для графіка). */
    val chart: List<SensorRecord> = emptyList(),
    /** Останні [MAX_TRIGGERED] спрацювань актуаторів, нові зверху. */
    val triggered: List<SensorRecord> = emptyList(),
    /** Кількість записів у БД. */
    val totalCount: Int = 0,
    /** Сенсор активний (між `onResume` і `onPause`), тобто запис у БД іде. */
    val recording: Boolean = false,
    /** ЛР6: поточний інтервал запису, мс ([RECORD_INTERVAL_MS] або [FAST_RECORD_INTERVAL_MS]). */
    val intervalMs: Long = RECORD_INTERVAL_MS,
)

/** Період запису значення сенсора в БД, мс. */
const val RECORD_INTERVAL_MS = 1_000L

/** ЛР6: період запису, коли AI бачить активність (клас ≠ CALM), мс. */
const val FAST_RECORD_INTERVAL_MS = 200L

/** Скільки останніх записів показує графік. */
const val MAX_CHART_POINTS = 50

/** Скільки останніх спрацювань показує список. */
const val MAX_TRIGGERED = 20

/** Скільки зберігаємо історію: записи, старіші за 24 год, видаляються при старті. */
const val RETENTION_MS = 24 * 60 * 60 * 1_000L

/**
 * ЛР5: пише значення сенсора й спрацювання актуаторів у Room і віддає UI історію з БД.
 *
 * ViewModel не знає про Vico: віддає звичайні [SensorRecord], а перетворення на модель графіка
 * робить View. Так ViewModel не залежить від UI-бібліотеки і її можна тестувати на JVM.
 *
 * Корутини запускаються у `viewModelScope` і автоматично скасовуються в `onCleared()`,
 * тож після знищення ViewModel нічого не продовжує писати в БД (немає витоку).
 *
 * @param sensor стан детектора ЛР1 (`IntrusionViewModel.uiState`).
 * @param actions виконані автодії ЛР4 (`ControlViewModel.executedActions`).
 * @param fast ЛР6: AI бачить активність (`AiViewModel.fastRecording`) → запис кожні [FAST_RECORD_INTERVAL_MS].
 */
class SensorViewModel(
    app: Application,
    private val sensor: StateFlow<IntrusionUiState>,
    actions: SharedFlow<List<Action>>,
    private val fast: StateFlow<Boolean>,
) : ViewModel() {
    // ViewModel звертається лише до репозиторію, не до DAO напряму
    private val repo = SensorRepository(AppDatabase.getInstance(app).sensorDao())

    init {
        // 1. Ретеншн: один раз при старті прибираємо старі записи, щоб БД не росла безкінечно
        viewModelScope.launch {
            repo.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }

        // 2. Запис за таймером (даунсемплінг). Сенсор дає ≈50 подій/с: писати кожну означало б
        // ~180 000 рядків на годину і постійне навантаження на флеш-пам'ять. Тому раз на секунду
        // беремо sensor.value — останній знімок, — а не collect усіх подій.
        // delay дає невеликий дрейф інтервалу (час insert додається до періоду); для графіка
        // некритично. Точніше — рахувати наступний момент як startTime + n * interval.
        viewModelScope.launch {
            // isActive: при скасуванні viewModelScope цикл коректно завершується
            while (isActive) {
                // ЛР6: змінний інтервал — під час активності детальніша історія, у спокої економимо флеш
                delay(if (fast.value) FAST_RECORD_INTERVAL_MS else RECORD_INTERVAL_MS)
                val ui = sensor.value
                // Пишемо лише коли сенсор слухається: після onPause він знятий, і записи були б
                // «застиглим» останнім значенням
                if (ui.listening && ui.sensorAvailable) {
                    repo.insert(
                        SensorRecord(
                            timestamp = System.currentTimeMillis(),
                            sensorValue = ui.snapshot.rms,
                            actionTriggered = false,
                        )
                    )
                }
            }
        }

        // 3. Запис подій: кожне рішення DecisionEngine, яке виконано, — один рядок з actionTriggered = true
        viewModelScope.launch {
            actions.collect {
                repo.insert(
                    SensorRecord(
                        timestamp = System.currentTimeMillis(),
                        sensorValue = sensor.value.snapshot.rms,
                        actionTriggered = true,
                    )
                )
            }
        }
    }

    /**
     * Стан для екрана. `combine` об'єднує кілька `Flow`: коли будь-який видає нове значення
     * (новий запис у БД або зміна listening), перераховується весь [AnalyticsUiState].
     *
     * `stateIn` перетворює холодні Room-`Flow` на гарячий `StateFlow`. `WhileSubscribed(5_000)`:
     * підписка на БД тримається, поки екран видно, і ще 5 с після — цього вистачає, щоб пережити
     * поворот екрана без перезапуску запитів, і водночас не тримати запит, коли екран закрито.
     */
    val uiState: StateFlow<AnalyticsUiState> = combine(
        repo.lastRecords(MAX_CHART_POINTS),
        repo.triggered(MAX_TRIGGERED),
        repo.count(),
        sensor.map { it.listening && it.sensorAvailable },
        fast,
    ) { chart, triggered, count, recording, isFast ->
        AnalyticsUiState(
            chart = chart,
            triggered = triggered,
            totalCount = count,
            recording = recording,
            intervalMs = if (isFast) FAST_RECORD_INTERVAL_MS else RECORD_INTERVAL_MS,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    /** Видаляє всю історію, щоб почати графік з чистого аркуша (наприклад, перед скриншотом). */
    fun clearHistory() {
        viewModelScope.launch { repo.deleteAll() }
    }

    companion object {
        /** Фабрика за тим самим шаблоном, що в `ControlViewModel`: передає залежності в конструктор. */
        fun factory(
            app: Application,
            sensor: StateFlow<IntrusionUiState>,
            actions: SharedFlow<List<Action>>,
            fast: StateFlow<Boolean>,
        ) = viewModelFactory {
            initializer { SensorViewModel(app, sensor, actions, fast) }
        }
    }
}
