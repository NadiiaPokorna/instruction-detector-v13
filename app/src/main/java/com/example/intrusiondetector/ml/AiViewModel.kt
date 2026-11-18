package com.example.intrusiondetector.ml

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.BuildConfig
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.ml.ActivityClassifier.Companion.HOP
import com.example.intrusiondetector.ml.ActivityClassifier.Companion.RAW_WINDOW
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// ЛР6, шар ViewModel (MVVM): вікно семплів, планування inference, підтвердження аномалії, стан для UI.

private const val TAG = "AI"

/** R4: мінімальна впевненість у BREAK_IN, щоб прогноз зарахувався до підтвердження. */
const val ANOMALY_CONFIDENCE = 0.8f

/** R4: скільки прогнозів BREAK_IN поспіль потрібно (2 × 0,5 с ≈ 1 с). */
const val ANOMALY_CONSECUTIVE = 2

/** R4: після підтвердженої аномалії повторно не емітимо протягом цього часу, мс. */
const val ANOMALY_COOLDOWN_MS = 10_000L

/** Швидкий запис: мінімальна впевненість у класі ≠ CALM. */
const val ACTIVITY_CONFIDENCE = 0.6f

/** Швидкий запис вимикається після стількох мс без активності (лише CALM). */
const val ACTIVITY_HOLD_MS = 5_000L

/** Стан блоку «AI Insights». */
data class AiUiState(
    /** Модель завантажилась. */
    val available: Boolean = true,
    /** Причина, якщо модель не завантажилась. */
    val error: String? = null,
    /** Останній прогноз. */
    val prediction: Prediction? = null,
    /** Скільки з [RAW_WINDOW] семплів уже накопичено. */
    val bufferFill: Int = 0,
    /** Аномалію підтверджено (R4), діє протягом [ANOMALY_COOLDOWN_MS]. */
    val anomalyConfirmed: Boolean = false,
    /** Режим швидкого запису в БД (`SensorViewModel`). */
    val fastRecording: Boolean = false,
)

/**
 * ЛР6: on-device AI поверх потоку семплів детектора ЛР1.
 *
 * Головний потік: [onSample] кладе кожне значення `|a − g|` у [CircularBuffer] і кожні [HOP] семплів
 * знімає копію вікна. Фоновий потік: [ActivityClassifier.predict] і обробка результату.
 *
 * @param sensor стан детектора ЛР1 (`IntrusionViewModel.uiState`) — лише для `listening`.
 */
class AiViewModel(
    app: Application,
    sensor: StateFlow<IntrusionUiState>,
) : ViewModel() {

    /** Класифікатор; `null`, поки модель читається або якщо завантаження не вдалося. */
    @Volatile
    private var classifier: ActivityClassifier? = null

    /** Останні 2 с сирих значень. Чіпається лише з головного потоку. */
    private val buffer = CircularBuffer(RAW_WINDOW)

    /** Скільки семплів надійшло після останнього запуску прогнозу. */
    private var sinceLastHop = 0

    /**
     * Потік для inference. `Dispatchers.Default` — пул для обчислень, потоків стільки, скільки ядер CPU:
     * прогноз — це чиста арифметика, і більше потоків, ніж ядер, лише конкурували б між собою.
     * `Dispatchers.IO` — до 64 потоків, розрахований на очікування диска чи мережі, не на CPU-роботу.
     * `Main` — не можна: будь-яка важка робота там гальмує UI і сенсор.
     *
     * `limitedParallelism(1)`: ті самі потоки `Default`, але не більше одного завдання одночасно,
     * бо `Interpreter` не потокобезпечний (див. KDoc [ActivityClassifier]).
     *
     * Чесно: ця модель рахується за частки мілісекунди, тож на ній відставання UI не помітиш навіть у Main.
     * Але принцип правильний для будь-якої моделі, а реальний час видно в UI («Inference: … мс»).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Прогноз виконується. Якщо новий час настав, а попередній ще не завершився, новий пропускаємо,
     * а не ставимо в чергу: на повільному пристрої черга росла б, і прогнози відставали б від реальності.
     */
    private val busy = AtomicBoolean(false)

    // Стан підтвердження і швидкого запису. Змінюється лише в inferenceDispatcher (один потік за раз),
    // тож додаткова синхронізація не потрібна.
    private var breakInStreak = 0
    private var lastAnomalyAt = Long.MIN_VALUE / 2
    private var lastActiveAt = Long.MIN_VALUE / 2

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    // SharedFlow, а не StateFlow: аномалія — подія (як executedActions у ControlViewModel).
    // tryEmit + буфер: емісія з фонового потоку не призупиняється і не блокує inference.
    private val _anomalies = MutableSharedFlow<Prediction>(extraBufferCapacity = 4)

    /** Підтверджені аномалії BREAK_IN для правила R4 (`ControlViewModel`). */
    val anomalies: SharedFlow<Prediction> = _anomalies.asSharedFlow()

    private val _fastRecording = MutableStateFlow(false)

    /** `true` — AI бачить активність, `SensorViewModel` пише в БД частіше. */
    val fastRecording: StateFlow<Boolean> = _fastRecording.asStateFlow()

    init {
        // Завантаження моделі (читання файлу) — на IO, щоб не гальмувати старт UI.
        // Помилка не валить застосунок: блок AI просто показує причину.
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { ActivityClassifier(app) }
                classifier = c
                if (BuildConfig.DEBUG) withContext(inferenceDispatcher) { c.selfTest() }
            } catch (e: Exception) {
                Log.e(TAG, "Модель не завантажилась", e)
                _uiState.update { it.copy(available = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
        // Після onPause вікно скидається: вікно, що «перетинає» паузу, склеїло б два різні моменти часу.
        // viewModelScope працює на Main, тож buffer чіпається лише з головного потоку.
        viewModelScope.launch {
            sensor.map { it.listening }.distinctUntilChanged().collect { listening ->
                if (!listening) {
                    buffer.clear()
                    sinceLastHop = 0
                    _uiState.update { it.copy(bufferFill = 0) }
                }
            }
        }
    }

    /**
     * Новий семпл `|a − g|` (м/с²). Викликається з `MainActivity.onSensorChanged` одразу після
     * `IntrusionViewModel.onSample`, на головному потоці.
     *
     * Чому прямий виклик, а не `collect` з `IntrusionViewModel.uiState`: `StateFlow` об'єднує (conflate)
     * значення — колектор бачить лише останнє, і якщо він не встигає, проміжні семпли губляться.
     * Вікно стало б «дірявим», а модель навчена на неперервних 50 Гц. Синхронний виклик гарантує,
     * що в буфер потрапить кожен семпл.
     */
    fun onSample(dynamic: Float) {
        buffer.add(dynamic)
        if (!buffer.isFull) {
            // Оновлюємо прогрес лише під час накопичення: заповнений буфер не змінює UI 50 разів/с
            _uiState.update { it.copy(bufferFill = buffer.size) }
            return
        }
        if (++sinceLastHop < HOP) return
        sinceLastHop = 0
        if (_uiState.value.bufferFill != RAW_WINDOW) _uiState.update { it.copy(bufferFill = RAW_WINDOW) }

        val c = classifier ?: return
        if (!busy.compareAndSet(false, true)) return // попередній ще рахується → пропускаємо
        val window = buffer.toArray() // копія на головному потоці; буфер далі наповнюється
        viewModelScope.launch(inferenceDispatcher) {
            try {
                handle(c.predict(window))
            } catch (e: Exception) {
                Log.e(TAG, "Помилка inference", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Від прогнозу до дій. Виконується в [inferenceDispatcher]. */
    private fun handle(p: Prediction) {
        val now = p.timeMillis

        // Підтвердження аномалії: ціна хибної тривоги (сирена вночі) висока, тож вимагаємо стабільності —
        // BREAK_IN з впевненістю ≥ 0,8 у двох прогнозах поспіль (≈1 с), як гістерезис у ЛР1.
        // Хибнопозитивне спрацювання — тривога, коли злому немає (наприклад, вікно на краю одного удару).
        breakInStreak = if (p.cls == VibrationClass.BREAK_IN && p.confidence >= ANOMALY_CONFIDENCE) breakInStreak + 1 else 0
        if (breakInStreak >= ANOMALY_CONSECUTIVE && now - lastAnomalyAt >= ANOMALY_COOLDOWN_MS) {
            lastAnomalyAt = now
            breakInStreak = 0
            _anomalies.tryEmit(p)
            Log.i(TAG, "Аномалію підтверджено: ${p.cls} ${(p.confidence * 100).toInt()}%")
        }

        // Швидкий запис: вмикається одразу при активності, вимикається після ACTIVITY_HOLD_MS без неї.
        // Утримання (hold) потрібне, щоб інтервал запису не «блимав» між сусідніми прогнозами.
        if (p.cls != VibrationClass.CALM && p.confidence >= ACTIVITY_CONFIDENCE) lastActiveAt = now
        val fast = now - lastActiveAt < ACTIVITY_HOLD_MS
        _fastRecording.value = fast

        Log.d(TAG, "${p.cls} ${p.probabilities.contentToString()} ${"%.3f".format(p.inferenceMs)} мс")
        _uiState.update {
            it.copy(
                prediction = p,
                anomalyConfirmed = now - lastAnomalyAt < ANOMALY_COOLDOWN_MS,
                fastRecording = fast,
            )
        }
    }

    override fun onCleared() {
        // Native-пам'ять інтерпретатора GC не звільняє. Закриваємо в тому ж dispatcher, щоб не закрити
        // інтерпретатор посеред прогнозу; viewModelScope уже скасовано, тож запускаємо поза ним.
        val c = classifier ?: return
        classifier = null
        CoroutineScope(inferenceDispatcher).launch { c.close() }
    }

    companion object {
        /** Фабрика за тим самим шаблоном, що в інших ViewModel. */
        fun factory(app: Application, sensor: StateFlow<IntrusionUiState>) = viewModelFactory {
            initializer { AiViewModel(app, sensor) }
        }
    }
}
