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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.intrusiondetector.ui.AppRoot
import com.example.intrusiondetector.ui.theme.IntrusionDetectorTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ViewModel зберігає стан (тривогу, журнал) при повороті екрана,
    // фабрика передає йому конектор хаба з AppContainer
    private val viewModel: IntrusionViewModel by viewModels { IntrusionViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Отримуємо системний сервіс і акселерометр
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        viewModel.setSensorAvailable(accelerometer != null)

        setContent {
            IntrusionDetectorTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle()
                AppRoot(sensorState = state.value, onRearm = viewModel::rearm)
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
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Показуємо попередження, якщо дані ненадійні
        viewModel.onAccuracyChanged(accuracy)
    }
}
