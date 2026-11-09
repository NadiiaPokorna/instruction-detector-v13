package com.example.intrusiondetector.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.example.intrusiondetector.BuildConfig
import com.example.intrusiondetector.sensor.SecuritySnapshot
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** Один запис у базі: devices/{deviceId}/readings/{pushId}. */
data class SensorData(
    val deviceId: String,
    val type: String = "ACCELEROMETER",
    /** Час відправки, мс (System.currentTimeMillis). */
    val timestamp: Long,
    /** RMS вібрації, м/с². */
    val value: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val state: String,
    val cause: String,
) {
    companion object {
        fun from(snapshot: SecuritySnapshot, deviceId: String, timestamp: Long) = SensorData(
            deviceId = deviceId,
            timestamp = timestamp,
            value = snapshot.rms,
            x = snapshot.x,
            y = snapshot.y,
            z = snapshot.z,
            state = snapshot.state.name,
            cause = snapshot.cause.name,
        )
    }
}

/** ЛР4: команда з хмари, вузол devices/{deviceId}/control. */
@Suppress("PropertyName") // ім'я поля збігається з ключем у Firebase
data class ControlState(val led_status: Boolean = false)

/** REST API Firebase Realtime Database: POST створює запис з унікальним pushId. */
interface FirebaseApi {
    @POST("devices/{deviceId}/readings.json")
    suspend fun post(@Path("deviceId") deviceId: String, @Body data: SensorData): Response<Any>

    /** ЛР4: null у тілі, якщо вузла control ще немає. */
    @GET("devices/{deviceId}/control.json")
    suspend fun getControl(@Path("deviceId") deviceId: String): Response<ControlState?>

    @PUT("devices/{deviceId}/control.json")
    suspend fun putControl(@Path("deviceId") deviceId: String, @Body state: ControlState): Response<ControlState>
}

/** Створює клієнт Retrofit; null, якщо адресу бази не задано. */
fun createFirebaseApi(baseUrl: String): FirebaseApi? {
    if (baseUrl.isBlank()) return null
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .apply {
            // Повний лог запитів/відповідей лише в debug-збірці
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            }
        }
        .build()
    return Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FirebaseApi::class.java)
}

/** Ідентифікатор пристрою (ANDROID_ID). */
@SuppressLint("HardwareIds")
fun deviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
