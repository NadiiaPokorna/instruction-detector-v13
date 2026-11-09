package com.example.intrusiondetector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.intrusiondetector.actuator.ActuatorState
import com.example.intrusiondetector.actuator.ControlUiState

/** ЛР4: ручне керування актуаторами, авто-режим і журнал. */
@Composable
fun ControlScreen(
    state: ControlUiState,
    actuator: ActuatorState,
    onToggleTorch: (Boolean) -> Unit,
    onAlarm: () -> Unit,
    onAutoMode: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Manual Control", style = MaterialTheme.typography.titleLarge)

        // Віртуальна лампа
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier.size(72.dp).clip(CircleShape)
                    .background(if (state.lampOn) Color(0xFFFFD54F) else Color.Gray)
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text("Віртуальна лампа: ${if (state.lampOn) "увімкнено" else "вимкнено"}")
                Text(
                    "керується з Firebase: devices/${state.deviceId}/control/led_status",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LabeledSwitch("Ліхтарик", actuator.torchOn, onToggleTorch)
        Text(
            if (actuator.torchVirtual) "віртуальний (спалаху немає)" else "апаратний",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onAlarm) { Text("Тривога (вібрація)") }

        HorizontalDivider()
        LabeledSwitch("Автоматичний режим (DecisionEngine)", state.autoMode, onAutoMode)
        Text(
            "R1: тривога → вібрація + ліхтар\n" +
                "R2: вихід з INTRUSION → ліхтар вимкнено\n" +
                "R3: RMS ≥ 0.15 м/с² → коротка вібрація (≤ 1 раз / 5 с)",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Хмара: ${state.cloudStatus}")
        Text("deviceId: ${state.deviceId}", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Журнал", style = MaterialTheme.typography.titleMedium)
        if (state.log.isEmpty()) Text("(порожньо)", style = MaterialTheme.typography.bodySmall)
        state.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
