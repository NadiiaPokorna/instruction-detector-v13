package com.example.intrusiondetector.cloud

import com.example.intrusiondetector.sensor.AlarmCause
import com.example.intrusiondetector.sensor.SecurityState
import com.example.intrusiondetector.sensor.SecuritySnapshot
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTest {

    private val snapshot = SecuritySnapshot(
        x = 0.1f, y = -0.2f, z = 9.8f, rms = 0.95f,
        state = SecurityState.INTRUSION, cause = AlarmCause.KNOCKS,
    )

    @Test
    fun `from переносить поля зі SecuritySnapshot`() {
        val data = SensorData.from(snapshot, deviceId = "dev1", timestamp = 1234L)
        assertEquals(
            SensorData(
                deviceId = "dev1", type = "ACCELEROMETER", timestamp = 1234L,
                value = 0.95f, x = 0.1f, y = -0.2f, z = 9.8f,
                state = "INTRUSION", cause = "KNOCKS",
            ),
            data,
        )
    }

    @Test
    fun `Gson серіалізує SensorData з очікуваними ключами`() {
        val json = Gson().toJson(SensorData.from(snapshot, "dev1", 1234L))
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(
            setOf("deviceId", "type", "timestamp", "value", "x", "y", "z", "state", "cause"),
            obj.keySet(),
        )
        assertEquals("INTRUSION", obj["state"].asString)
        assertEquals(1234L, obj["timestamp"].asLong)
    }
}
