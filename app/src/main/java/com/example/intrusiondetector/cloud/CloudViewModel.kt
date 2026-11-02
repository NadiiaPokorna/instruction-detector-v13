package com.example.intrusiondetector.cloud

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.intrusiondetector.BuildConfig
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.sensor.SecurityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

enum class CloudStatus { NOT_CONFIGURED, IDLE, SUCCESS, ERROR, PENDING }

data class CloudUiState(
    val status: CloudStatus = CloudStatus.IDLE,
    /** Час останньої успішної відправки, мс. */
    val lastSuccessAt: Long? = null,
    val sentCount: Int = 0,
    val lastError: String? = null,
)

private const val TAG = "CloudSync"
private const val SEND_INTERVAL_MS = 5_000L

/**
 * ЛР3: кожні 5 с (поки сенсор активний) і одразу при тривозі
 * надсилає SensorData у Firebase Realtime Database.
 */
class CloudViewModel(
    private val app: Application,
    private val sensor: StateFlow<IntrusionUiState>,
) : ViewModel() {
    private val api = createFirebaseApi(BuildConfig.FIREBASE_DB_URL)
    private val deviceId = deviceId(app)
    private val sendLock = Mutex() // тік і тривога не відправляють одночасно
    private var wasOffline = false

    private val _uiState = MutableStateFlow(
        CloudUiState(status = if (api == null) CloudStatus.NOT_CONFIGURED else CloudStatus.IDLE)
    )
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        if (api == null) {
            Log.w(TAG, "FIREBASE_DB_URL не задано в local.properties — хмара вимкнена")
        } else {
            // Періодична відправка
            viewModelScope.launch {
                while (true) {
                    delay(SEND_INTERVAL_MS)
                    if (sensor.value.listening) send(api)
                }
            }
            // Позачергова відправка при переході в INTRUSION
            viewModelScope.launch {
                sensor.map { it.snapshot.state }
                    .distinctUntilChanged()
                    .filter { it == SecurityState.INTRUSION }
                    .collect { send(api) }
            }
        }
    }

    private suspend fun send(api: FirebaseApi) = sendLock.withLock {
        if (!isOnline()) {
            _uiState.update { it.copy(status = CloudStatus.PENDING) }
            if (!wasOffline) {
                wasOffline = true
                Log.w(TAG, "Немає інтернету, відправку пропущено")
                Toast.makeText(app, "Немає інтернету — дані не відправлено (Pending)", Toast.LENGTH_SHORT).show()
            }
            return@withLock
        }
        wasOffline = false

        val data = SensorData.from(sensor.value.snapshot, deviceId, System.currentTimeMillis())
        try {
            val response = withContext(Dispatchers.IO) { api.post(deviceId, data) }
            if (response.isSuccessful) {
                Log.d(TAG, "Надіслано: ${response.body()}")
                _uiState.update {
                    it.copy(
                        status = CloudStatus.SUCCESS,
                        lastSuccessAt = data.timestamp,
                        sentCount = it.sentCount + 1,
                        lastError = null,
                    )
                }
            } else {
                fail("HTTP ${response.code()}: ${response.errorBody()?.string().orEmpty()}")
            }
        } catch (e: IOException) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, "Помилка відправки: $message")
        _uiState.update { it.copy(status = CloudStatus.ERROR, lastError = message) }
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        fun factory(app: Application, sensor: StateFlow<IntrusionUiState>) = viewModelFactory {
            initializer { CloudViewModel(app, sensor) }
        }
    }
}
