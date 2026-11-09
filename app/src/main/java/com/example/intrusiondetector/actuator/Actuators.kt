package com.example.intrusiondetector.actuator

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.example.intrusiondetector.sensor.SecuritySnapshot
import com.example.intrusiondetector.sensor.SecurityState
import com.example.intrusiondetector.sensor.Thresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "Actuator"

data class ActuatorState(
    val torchOn: Boolean = false,
    /** true — апаратного спалаху немає, ліхтарик існує лише в UI. */
    val torchVirtual: Boolean = false,
    /** Час останньої тривоги, мс. */
    val lastAlarmAt: Long? = null,
)

/** ЛР4: виконавчі пристрої телефону — вібромотор і спалах камери. */
class SmartActuator(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val hasFlash = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    /** Камера зі спалахом або null. */
    private val torchCameraId: String? = if (!hasFlash) null else try {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        Log.d(TAG, "Немає доступу до камери: ${e.message}")
        null
    }

    private val _state = MutableStateFlow(ActuatorState(torchVirtual = torchCameraId == null))
    val state: StateFlow<ActuatorState> = _state.asStateFlow()

    init {
        Log.d(TAG, "FEATURE_CAMERA_FLASH=$hasFlash, камера ліхтарика=$torchCameraId")
    }

    /** «Сирена»: довга серія вібрацій. */
    fun triggerAlarm() {
        _state.update { it.copy(lastAlarmAt = System.currentTimeMillis()) }
        if (!vibrator.hasVibrator()) {
            Log.d(TAG, "triggerAlarm: вібромотора немає")
            return
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1))
        Log.d(TAG, "triggerAlarm: вібрація запущена")
    }

    /** Коротке попередження про вібрацію поверхні. */
    fun warn() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.d(TAG, "warn: коротка вібрація")
    }

    /** Вмикає/вимикає спалах; без спалаху — «віртуальний ліхтарик» (стан лише в UI). */
    fun toggleFlashlight(status: Boolean) {
        var virtual = torchCameraId == null
        if (!virtual) {
            try {
                cameraManager.setTorchMode(torchCameraId!!, status)
            } catch (e: CameraAccessException) {
                Log.d(TAG, "setTorchMode помилка: ${e.message} — віртуальний режим")
                virtual = true
            }
        }
        _state.update { it.copy(torchOn = status, torchVirtual = virtual) }
        Log.d(TAG, "toggleFlashlight($status), віртуальний=$virtual")
    }
}

enum class Action { ALARM, TORCH_ON, TORCH_OFF, WARN_VIBRATION }

/**
 * Правила автоматичного керування (чистий Kotlin, без Android).
 * Зберігає попередній стан, щоб реагувати на переходи.
 */
class DecisionEngine {
    companion object {
        /** R3: IF rms ≥ 0.15 м/с² AND стан ≠ INTRUSION THEN коротка вібрація. */
        const val WARN_RMS = Thresholds.VIB_WARN_RMS

        /** R3: попередження не частіше 1 разу на 5 с. */
        const val WARN_INTERVAL_MS = 5_000L
    }

    private var prevState: SecurityState? = null
    private var torchByEngine = false
    private var lastWarnAt = Long.MIN_VALUE / 2

    fun decide(snapshot: SecuritySnapshot, now: Long): List<Action> {
        val state = snapshot.state
        val wasIntrusion = prevState == SecurityState.INTRUSION
        prevState = state
        val actions = mutableListOf<Action>()

        // R1: IF тривога спрацювала (перехід у INTRUSION) THEN сирена + прожектор.
        // Перевіряємо і сам перехід: StateFlow може пропустити разовий alarmTriggered.
        if (state == SecurityState.INTRUSION) {
            if (snapshot.alarmTriggered || !wasIntrusion) {
                actions += Action.ALARM
                actions += Action.TORCH_ON
                torchByEngine = true
            }
            return actions
        }

        // R2: IF стан вийшов з INTRUSION AND ліхтар увімкнено правилом R1 THEN вимкнути ліхтар.
        if (wasIntrusion && state != SecurityState.INTRUSION && torchByEngine) {
            actions += Action.TORCH_OFF
            torchByEngine = false
        }

        // R3: IF rms ≥ WARN_RMS AND стан ≠ INTRUSION THEN коротка вібрація (раз на 5 с).
        if (state != SecurityState.INTRUSION && snapshot.rms >= WARN_RMS &&
            now - lastWarnAt >= WARN_INTERVAL_MS
        ) {
            actions += Action.WARN_VIBRATION
            lastWarnAt = now
        }
        return actions
    }
}
