package com.example.intrusiondetector.sensor

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Стани охоронної системи.
 *
 * CALIBRATING → QUIET ⇄ VIBRATION → INTRUSION (тривога утримується до скидання).
 */
enum class SecurityState {
    /** Перші [Thresholds.CALIBRATION_NS] після постановки на охорону: запам'ятовується положення. */
    CALIBRATING,

    /** Поверхня нерухома: RMS вібрації < [Thresholds.VIB_WARN_RMS]. */
    QUIET,

    /** Підозріла вібрація: RMS ≥ [Thresholds.VIB_WARN_RMS], але умови тривоги ще не виконані. */
    VIBRATION,

    /** Вторгнення: спрацювала одна з умов тривоги. Стан утримується до скидання. */
    INTRUSION,
}

/** Причина тривоги. */
enum class AlarmCause {
    NONE,

    /** Серія ударів / стуків: ≥ [Thresholds.IMPULSE_COUNT] імпульсів за [Thresholds.IMPULSE_WINDOW_NS]. */
    KNOCKS,

    /** Сильна вібрація, що триває ≥ [Thresholds.SUSTAIN_NS] (відкриття дверей, злам). */
    SUSTAINED_VIBRATION,

    /** Поверхня (або пристрій) змінила нахил більше ніж на [Thresholds.TILT_ALARM_DEG]. */
    TILT,
}

/**
 * Порогові значення (м/с², градуси, нс), при яких система змінює стан.
 *
 * Пристрій лежить на поверхні або закріплений на дверях:
 * - у спокої динамічне прискорення - це шум сенсора (≈ 0,01–0,08 м/с²);
 * - легкий дотик, кроки поруч, зачинення сусідніх дверей дають 0,15–0,8 м/с²;
 * - стук у двері, удар по поверхні - короткі імпульси 2–10 м/с²;
 * - відкриття дверей або спроба зламу - тривала вібрація понад 0,8 м/с².
 */
object Thresholds {
    /** Прискорення вільного падіння, м/с². */
    const val GRAVITY = 9.80665f

    /** Тривалість калібрування після постановки на охорону, нс. */
    const val CALIBRATION_NS = 2_000_000_000L

    /** Ковзне вікно для RMS вібрації, нс. */
    const val RMS_WINDOW_NS = 500_000_000L

    /** QUIET → VIBRATION: RMS динамічного прискорення, м/с². */
    const val VIB_WARN_RMS = 0.15f

    /** Рівень сильної вібрації, м/с². */
    const val VIB_ALARM_RMS = 0.8f

    /** Скільки часу сильна вібрація має тривати без перерви для тривоги, нс. */
    const val SUSTAIN_NS = 1_500_000_000L

    /** Пік динамічного прискорення, що вважається ударом / стуком, м/с². */
    const val IMPULSE_PEAK = 2.0f

    /** Гістерезис: наступний імпульс можливий після спаду сигналу нижче цього значення, м/с². */
    const val IMPULSE_RESET = 0.5f

    /** Мінімальний інтервал між імпульсами (відсікає «дзвін» одного удару), нс. */
    const val MIN_IMPULSE_INTERVAL_NS = 150_000_000L

    /** Скільки імпульсів у вікні означає тривогу. */
    const val IMPULSE_COUNT = 3

    /** Вікно підрахунку імпульсів, нс. */
    const val IMPULSE_WINDOW_NS = 3_000_000_000L

    /** Відхилення вектора гравітації від запам'ятованого положення для тривоги, градуси. */
    const val TILT_ALARM_DEG = 10f

    /** Стала часу фільтра нижніх частот, що виділяє гравітацію по кожній осі, с. */
    const val GRAVITY_TAU_S = 0.5f
}

/** Одиничний вектор (напрямок гравітації). */
data class Vec3(val x: Float, val y: Float, val z: Float)

/** Усе, що відомо детектору після обробки одного вимірювання. */
data class SecuritySnapshot(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** |a| разом із гравітацією, м/с². */
    val magnitude: Float = 0f,
    /** Модуль динамічного прискорення |a − g|, м/с². */
    val dynamic: Float = 0f,
    /** RMS динамічного прискорення у вікні [Thresholds.RMS_WINDOW_NS], м/с². */
    val rms: Float = 0f,
    /** Відхилення від запам'ятованого положення, градуси. */
    val tiltDeg: Float = 0f,
    /** Запам'ятований напрямок гравітації або null, поки триває калібрування. */
    val baseline: Vec3? = null,
    /** Імпульсів у поточному вікні. */
    val impulsesInWindow: Int = 0,
    /** Усього імпульсів з моменту постановки на охорону. */
    val totalImpulses: Int = 0,
    /** Прогрес калібрування 0..1. */
    val calibrationProgress: Float = 0f,
    val state: SecurityState = SecurityState.CALIBRATING,
    val cause: AlarmCause = AlarmCause.NONE,
    /** true лише для вимірювання, на якому зафіксовано новий імпульс. */
    val impulseDetected: Boolean = false,
    /** true лише для вимірювання, на якому спрацювала тривога. */
    val alarmTriggered: Boolean = false,
)

/**
 * Детектор вторгнення на чистому Kotlin (без залежностей від Android),
 * тому його можна перевіряти звичайними JUnit-тестами.
 *
 * Алгоритм:
 * 1. Фільтр нижніх частот по кожній осі виділяє вектор гравітації g.
 * 2. Динамічне прискорення = |a − g| - вібрація в будь-якому напрямку.
 * 3. Інтенсивність вібрації = RMS динамічного прискорення за 0,5 с.
 * 4. Імпульс (удар) = динамічне прискорення перетинає [Thresholds.IMPULSE_PEAK] з гістерезисом.
 * 5. Нахил = кут між поточним g і напрямком, запам'ятованим під час калібрування.
 * 6. Тривога: серія імпульсів, тривала сильна вібрація або нахил понад поріг.
 */
class IntrusionDetector {
    private var lastTimestampNs = -1L
    private var gx = 0f
    private var gy = 0f
    private var gz = Thresholds.GRAVITY

    // Калібрування
    private var calibrationStartNs = -1L
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var calibrationSamples = 0
    private var baseline: Vec3? = null

    // Вібрація
    private val rmsWindow = ArrayDeque<Pair<Long, Float>>() // час to dynamic²
    private var rmsSumSq = 0.0
    private var strongSinceNs = -1L

    // Імпульси
    private var impulseArmed = true
    private var lastImpulseNs = Long.MIN_VALUE / 2
    private val impulseTimes = ArrayDeque<Long>()
    private var totalImpulses = 0

    // Тривога
    private var alarmed = false
    private var cause = AlarmCause.NONE

    /** Поставити на охорону заново: скидає тривогу і запускає калібрування. */
    fun arm() {
        alarmed = false
        cause = AlarmCause.NONE
        totalImpulses = 0
        baseline = null
        restartFilters()
    }

    /**
     * Повернення з фону: моніторинг був призупинений і пристрій міг бути переміщений,
     * тому фільтри перезапускаються, а положення калібрується заново.
     * Тривога, якщо вона вже спрацювала, зберігається.
     */
    fun resume() {
        restartFilters()
        if (!alarmed) baseline = null
    }

    private fun restartFilters() {
        lastTimestampNs = -1L
        calibrationStartNs = -1L
        sumX = 0.0
        sumY = 0.0
        sumZ = 0.0
        calibrationSamples = 0
        rmsWindow.clear()
        rmsSumSq = 0.0
        strongSinceNs = -1L
        impulseArmed = true
        lastImpulseNs = Long.MIN_VALUE / 2
        impulseTimes.clear()
    }
    // Розрахунок середньої вібрації
    fun process(x: Float, y: Float, z: Float, timestampNs: Long): SecuritySnapshot {
        val magnitude = sqrt(x * x + y * y + z * z)

        // 1. Виділення гравітації фільтром нижніх частот по кожній осі.
        if (lastTimestampNs < 0) {
            gx = x
            gy = y
            gz = z
        } else {
            val dt = ((timestampNs - lastTimestampNs) / 1e9f).coerceIn(0f, 0.5f)
            val a = alpha(dt, Thresholds.GRAVITY_TAU_S)
            gx += a * (x - gx)
            gy += a * (y - gy)
            gz += a * (z - gz)
        }
        lastTimestampNs = timestampNs

        // 2. Динамічне прискорення - вібрація без гравітації.
        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        val dynamic = sqrt(dx * dx + dy * dy + dz * dz)

        // 3. RMS у ковзному вікні.
        val sq = dynamic * dynamic
        rmsWindow.addLast(timestampNs to sq)
        rmsSumSq += sq
        while (rmsWindow.isNotEmpty() && timestampNs - rmsWindow.first().first > Thresholds.RMS_WINDOW_NS) {
            rmsSumSq -= rmsWindow.removeFirst().second
        }
        val rms = if (rmsWindow.isEmpty()) 0f else sqrt((rmsSumSq / rmsWindow.size).coerceAtLeast(0.0)).toFloat()

        // Калібрування: усереднюємо положення, умови тривоги не перевіряємо.
        val base = baseline
        if (base == null) {
            if (calibrationStartNs < 0) calibrationStartNs = timestampNs
            sumX += x
            sumY += y
            sumZ += z
            calibrationSamples++
            val elapsed = timestampNs - calibrationStartNs
            if (elapsed >= Thresholds.CALIBRATION_NS) {
                baseline = normalize(
                    (sumX / calibrationSamples).toFloat(),
                    (sumY / calibrationSamples).toFloat(),
                    (sumZ / calibrationSamples).toFloat(),
                )
            }
            return SecuritySnapshot(
                x = x,
                y = y,
                z = z,
                magnitude = magnitude,
                dynamic = dynamic,
                rms = rms,
                baseline = baseline,
                totalImpulses = totalImpulses,
                calibrationProgress = (elapsed.toFloat() / Thresholds.CALIBRATION_NS).coerceIn(0f, 1f),
                state = if (alarmed) SecurityState.INTRUSION else SecurityState.CALIBRATING,
                cause = cause,
            )
        }

        // 4. Імпульси (удари / стуки) з гістерезисом і мінімальним інтервалом.
        var impulseDetected = false
        if (dynamic < Thresholds.IMPULSE_RESET) impulseArmed = true
        if (impulseArmed &&
            dynamic > Thresholds.IMPULSE_PEAK &&
            timestampNs - lastImpulseNs >= Thresholds.MIN_IMPULSE_INTERVAL_NS
        ) {
            impulseArmed = false
            lastImpulseNs = timestampNs
            impulseTimes.addLast(timestampNs)
            totalImpulses++
            impulseDetected = true
        }
        while (impulseTimes.isNotEmpty() && timestampNs - impulseTimes.first() > Thresholds.IMPULSE_WINDOW_NS) {
            impulseTimes.removeFirst()
        }

        // Тривала сильна вібрація: запам'ятовуємо, з якого моменту RMS вище порогу.
        if (rms >= Thresholds.VIB_ALARM_RMS) {
            if (strongSinceNs < 0) strongSinceNs = timestampNs
        } else {
            strongSinceNs = -1L
        }

        // 5. Відхилення від запам'ятованого положення.
        val tiltDeg = angleDeg(base, normalize(gx, gy, gz))

        // 6. Умови тривоги (перевіряються, доки тривога ще не спрацювала).
        var alarmTriggered = false
        if (!alarmed) {
            val newCause = when {
                tiltDeg >= Thresholds.TILT_ALARM_DEG -> AlarmCause.TILT
                impulseTimes.size >= Thresholds.IMPULSE_COUNT -> AlarmCause.KNOCKS
                strongSinceNs >= 0 && timestampNs - strongSinceNs >= Thresholds.SUSTAIN_NS ->
                    AlarmCause.SUSTAINED_VIBRATION
                else -> AlarmCause.NONE
            }
            if (newCause != AlarmCause.NONE) {
                alarmed = true
                cause = newCause
                alarmTriggered = true
            }
        }

        return SecuritySnapshot(
            x = x,
            y = y,
            z = z,
            magnitude = magnitude,
            dynamic = dynamic,
            rms = rms,
            tiltDeg = tiltDeg,
            baseline = base,
            impulsesInWindow = impulseTimes.size,
            totalImpulses = totalImpulses,
            calibrationProgress = 1f,
            state = classify(alarmed, rms),
            cause = cause,
            impulseDetected = impulseDetected,
            alarmTriggered = alarmTriggered,
        )
    }

    companion object {
        /** Коефіцієнт експоненційного згладжування для фільтра 1-го порядку. */
        fun alpha(dt: Float, tau: Float): Float = dt / (tau + dt)

        /** Стан після калібрування: тривога має пріоритет, далі - рівень вібрації. */
        fun classify(alarmed: Boolean, rms: Float): SecurityState = when {
            alarmed -> SecurityState.INTRUSION
            rms >= Thresholds.VIB_WARN_RMS -> SecurityState.VIBRATION
            else -> SecurityState.QUIET
        }

        fun normalize(x: Float, y: Float, z: Float): Vec3 {
            val n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            return Vec3(x / n, y / n, z / n)
        }

        /** Кут між двома одиничними векторами, градуси. */
        fun angleDeg(a: Vec3, b: Vec3): Float {
            val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(dot).toDouble()).toFloat()
        }
    }
}
