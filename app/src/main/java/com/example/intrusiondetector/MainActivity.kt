package com.example.intrusiondetector

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.intrusiondetector.actuator.ControlViewModel
import com.example.intrusiondetector.ble.HubViewModel
import com.example.intrusiondetector.cloud.CloudViewModel
import com.example.intrusiondetector.ml.AiViewModel
import com.example.intrusiondetector.storage.SensorViewModel
import com.example.intrusiondetector.ui.AppRoot
import com.example.intrusiondetector.ui.theme.IntrusionDetectorTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ViewModel зберігає стан (тривогу, журнал) при повороті екрана
    private val viewModel: IntrusionViewModel by viewModels()

    // ЛР2: стан BLE-хаба; отримує стан детектора, щоб надсилати тривогу
    private val hubViewModel: HubViewModel by viewModels { HubViewModel.factory(viewModel.uiState) }

    // ЛР3: відправка даних сенсора у Firebase Realtime Database
    private val cloudViewModel: CloudViewModel by viewModels {
        CloudViewModel.factory(application, viewModel.uiState)
    }

    // ЛР6: on-device AI (TensorFlow Lite). Отримує кожен семпл з onSensorChanged
    private val aiViewModel: AiViewModel by viewModels { AiViewModel.factory(application, viewModel.uiState) }

    // ЛР4: актуатори (вібрація, ліхтарик) і команди з хмари; ЛР6: AI-аномалії для правила R4
    private val controlViewModel: ControlViewModel by viewModels {
        ControlViewModel.factory(application, viewModel.uiState, aiViewModel.anomalies)
    }

    // ЛР5: Room + графік. Отримує стан детектора і потік автодій актуаторів;
    // ЛР6: прискорений запис, коли AI бачить активність
    private val sensorViewModel: SensorViewModel by viewModels {
        SensorViewModel.factory(
            application, viewModel.uiState, controlViewModel.executedActions, aiViewModel.fastRecording,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Отримуємо системний сервіс і акселерометр
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        viewModel.setSensorAvailable(accelerometer != null)

        setContent {
            IntrusionDetectorTheme {
                AppRoot(
                    intrusionViewModel = viewModel,
                    hubViewModel = hubViewModel,
                    cloudViewModel = cloudViewModel,
                    controlViewModel = controlViewModel,
                    // ЛР5: by viewModels лінивий — ViewModel створюється при першому зверненні.
                    // Звертаємося тут, у onCreate, щоб запис у БД стартував одразу, а не тоді,
                    // коли користувач відкриє вкладку «Аналітика»
                    sensorViewModel = sensorViewModel,
                    aiViewModel = aiViewModel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            // SENSOR_DELAY_GAME ≈ 50 Гц: достатньо, щоб помітити короткий удар,
            // і водночас енергоефективно
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            viewModel.onListeningChanged(true)
        }
    }

    override fun onPause() {
        // Зупиняємо зчитування, коли застосунок не видно, - економія батареї
        sensorManager.unregisterListener(this)
        viewModel.onListeningChanged(false)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // values[0..2] - прискорення по осях X, Y, Z (м/с²), timestamp - нс
        viewModel.onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
        // ЛР6: той самий семпл |a − g| — у вікно AI. Прямий виклик, а не collect зі StateFlow,
        // щоб не губити семпли (пояснення — AiViewModel.onSample)
        aiViewModel.onSample(viewModel.uiState.value.snapshot.dynamic)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Показуємо попередження, якщо дані ненадійні
        viewModel.onAccuracyChanged(accuracy)
    }
}
