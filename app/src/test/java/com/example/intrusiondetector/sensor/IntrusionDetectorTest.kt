package com.example.intrusiondetector.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class IntrusionDetectorTest {

    private val sampleRateHz = 50
    private val dtNs = 1_000_000_000L / sampleRateHz
    private val random = Random(42)

    /** Поточний час симуляції, нс. */
    private var nowNs = 0L

    /**
     * Подає [seconds] секунд даних із частотою 50 Гц.
     * [signal] повертає (x, y, z) для часу t (с) від початку цього відрізка; до нього додається шум.
     */
    private fun feed(
        detector: IntrusionDetector,
        seconds: Float,
        noise: Float = 0.03f,
        signal: (Float) -> Triple<Float, Float, Float> = { Triple(0f, 0f, Thresholds.GRAVITY) },
    ): List<SecuritySnapshot> {
        val n = (seconds * sampleRateHz).toInt()
        return List(n) { i ->
            val t = i.toFloat() / sampleRateHz
            val (x, y, z) = signal(t)
            val r = { (random.nextFloat() * 2 - 1) * noise }
            detector.process(x + r(), y + r(), z + r(), nowNs).also { nowNs += dtNs }
        }
    }

    /** Нерухомий пристрій, що лежить на столі, - калібрування. */
    private fun calibrated(): IntrusionDetector = IntrusionDetector().also { feed(it, 2.5f) }

    @Test
    fun calibration_thenQuiet() {
        val detector = IntrusionDetector()
        val first = feed(detector, 1f)
        assertTrue(first.all { it.state == SecurityState.CALIBRATING })
        val last = feed(detector, 2f).last()
        assertEquals(SecurityState.QUIET, last.state)
        val base = last.baseline!!
        assertTrue("baseline=$base", base.z > 0.99f)
    }

    @Test
    fun stillSurface_noAlarm() {
        val detector = calibrated()
        val result = feed(detector, 10f)
        assertTrue(result.none { it.alarmTriggered })
        assertEquals(SecurityState.QUIET, result.last().state)
        assertEquals(0, result.last().totalImpulses)
    }

    @Test
    fun lightVibration_isWarningOnly() {
        val detector = calibrated()
        // 15 Гц, амплітуда 0,4 м/с²: кроки поруч, легкий дотик.
        val result = feed(detector, 5f) { t ->
            Triple(0f, 0f, Thresholds.GRAVITY + 0.4f * sin(2 * PI * 15 * t).toFloat())
        }
        assertEquals(SecurityState.VIBRATION, result.last().state)
        assertTrue(result.none { it.alarmTriggered })
        assertEquals(0, result.last().totalImpulses)
    }

    /** Короткий удар: один відлік з піком [peak] м/с² по осі Z кожні [periodS] с. */
    private fun knocks(peak: Float, periodS: Float): (Float) -> Triple<Float, Float, Float> = { t ->
        val i = Math.round(t * sampleRateHz)
        val period = Math.round(periodS * sampleRateHz)
        val z = if (i % period == 0) Thresholds.GRAVITY + peak else Thresholds.GRAVITY
        Triple(0f, 0f, z)
    }

    @Test
    fun twoKnocks_noAlarm() {
        val detector = calibrated()
        val result = feed(detector, 1.6f, signal = knocks(peak = 5f, periodS = 0.8f))
        assertEquals(2, result.last().totalImpulses)
        assertTrue(result.none { it.alarmTriggered })
    }

    @Test
    fun threeKnocks_triggerKnocksAlarm() {
        val detector = calibrated()
        val result = feed(detector, 2.4f, signal = knocks(peak = 5f, periodS = 0.8f))
        val last = result.last()
        assertEquals(SecurityState.INTRUSION, last.state)
        assertEquals(AlarmCause.KNOCKS, last.cause)
        assertEquals(1, result.count { it.alarmTriggered })
    }

    @Test
    fun sustainedStrongVibration_triggersAlarm() {
        val detector = calibrated()
        // 20 Гц, амплітуда 1,5 м/с² (RMS ≈ 1,06): відкриття дверей, злам.
        val vib: (Float) -> Triple<Float, Float, Float> = { t ->
            Triple(0f, 0f, Thresholds.GRAVITY + 1.5f * sin(2 * PI * 20 * t).toFloat())
        }
        val short = feed(detector, 1.0f, signal = vib)
        assertTrue("коротка вібрація не повинна давати тривогу", short.none { it.alarmTriggered })
        feed(detector, 1.0f) // пауза
        val long = feed(detector, 3f, signal = vib)
        assertEquals(SecurityState.INTRUSION, long.last().state)
        assertEquals(AlarmCause.SUSTAINED_VIBRATION, long.last().cause)
    }

    /** Плавний поворот навколо осі X на [deg] градусів за 2 с. */
    private fun tilt(deg: Float): (Float) -> Triple<Float, Float, Float> = { t ->
        val angle = Math.toRadians((deg * (t / 2f).coerceAtMost(1f)).toDouble())
        Triple(0f, (Thresholds.GRAVITY * sin(angle)).toFloat(), (Thresholds.GRAVITY * cos(angle)).toFloat())
    }

    @Test
    fun smallTilt_noAlarm() {
        val detector = calibrated()
        val result = feed(detector, 4f, signal = tilt(5f))
        assertTrue(result.none { it.alarmTriggered })
        assertTrue("tilt=${result.last().tiltDeg}", result.last().tiltDeg in 4f..6f)
    }

    @Test
    fun largeTilt_triggersTiltAlarm() {
        val detector = calibrated()
        val result = feed(detector, 4f, signal = tilt(20f))
        assertEquals(AlarmCause.TILT, result.last().cause)
        assertEquals(SecurityState.INTRUSION, result.last().state)
    }

    @Test
    fun alarm_isLatched_untilArm() {
        val detector = calibrated()
        feed(detector, 2.4f, signal = knocks(peak = 5f, periodS = 0.8f))
        val afterQuiet = feed(detector, 5f).last()
        assertEquals(SecurityState.INTRUSION, afterQuiet.state)

        detector.arm()
        assertEquals(SecurityState.CALIBRATING, feed(detector, 1f).last().state)
        val rearmed = feed(detector, 2f).last()
        assertEquals(SecurityState.QUIET, rearmed.state)
        assertEquals(AlarmCause.NONE, rearmed.cause)
    }

    @Test
    fun resume_recalibrates_butKeepsAlarm() {
        val quiet = calibrated()
        quiet.resume()
        assertEquals(SecurityState.CALIBRATING, feed(quiet, 0.5f).last().state)

        val alarmed = calibrated()
        feed(alarmed, 2.4f, signal = knocks(peak = 5f, periodS = 0.8f))
        alarmed.resume()
        assertEquals(SecurityState.INTRUSION, feed(alarmed, 1f).last().state)
    }

    @Test
    fun classify_usesThresholdBoundaries() {
        assertEquals(SecurityState.QUIET, IntrusionDetector.classify(false, Thresholds.VIB_WARN_RMS - 0.01f))
        assertEquals(SecurityState.VIBRATION, IntrusionDetector.classify(false, Thresholds.VIB_WARN_RMS))
        assertEquals(SecurityState.INTRUSION, IntrusionDetector.classify(true, 0f))
    }
}
