package com.example.intrusiondetector.ui

import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.intrusiondetector.HISTORY_SIZE
import com.example.intrusiondetector.IntrusionUiState
import com.example.intrusiondetector.description
import com.example.intrusiondetector.sensor.SecurityState
import com.example.intrusiondetector.sensor.Thresholds
import com.example.intrusiondetector.ui.theme.AxisXColor
import com.example.intrusiondetector.ui.theme.AxisYColor
import com.example.intrusiondetector.ui.theme.AxisZColor
import com.example.intrusiondetector.ui.theme.CalibratingColor
import com.example.intrusiondetector.ui.theme.IntrusionColor
import com.example.intrusiondetector.ui.theme.QuietColor
import com.example.intrusiondetector.ui.theme.VibrationColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Діапазон індикаторів X/Y/Z: ±2g. */
private const val AXIS_RANGE = 2 * Thresholds.GRAVITY

/** Верхня межа шкали вібрації, м/с². */
private const val VIBRATION_MAX = 2f

/** Верхня межа графіка сигналу, м/с². */
private const val CHART_MAX = 4f

private fun Float.fmt(digits: Int = 2) = String.format(Locale.US, "%.${digits}f", this)

private fun SecurityState.label() = when (this) {
    SecurityState.CALIBRATING -> "Калібрування"
    SecurityState.QUIET -> "Охорона: тихо"
    SecurityState.VIBRATION -> "Вібрація"
    SecurityState.INTRUSION -> "ВТОРГНЕННЯ"
}

private fun SecurityState.color() = when (this) {
    SecurityState.CALIBRATING -> CalibratingColor
    SecurityState.QUIET -> QuietColor
    SecurityState.VIBRATION -> VibrationColor
    SecurityState.INTRUSION -> IntrusionColor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntrusionScreen(state: IntrusionUiState, onRearm: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Intrusion Detector") },
                actions = { HubIndicator(state.hubConnected, state.hubName) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.sensorAvailable) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Акселерометр недоступний на цьому пристрої.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }
            StatusCard(state, onRearm)
            VibrationCard(state)
            AxesCard(state)
            TiltCard(state)
            SignalCard(state)
            EventLogCard(state)
            ThresholdsCard()
        }
    }
}

/** Маленький індикатор у верхній панелі: чи підключений хаб BLE. */
@Composable
private fun HubIndicator(connected: Boolean, name: String?) {
    val color = if (connected) QuietColor else CalibratingColor
    Row(
        Modifier
            .padding(end = 12.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (connected) name ?: "Хаб" else "Хаб не підключено",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Головний статус: стан системи, причина тривоги, кнопка повторної постановки на охорону. */
@Composable
private fun StatusCard(state: IntrusionUiState, onRearm: () -> Unit) {
    val s = state.snapshot
    val stateColor by animateColorAsState(s.state.color(), label = "stateColor")
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .background(stateColor, RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(s.state.label(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            when (s.state) {
                SecurityState.CALIBRATING -> {
                    Text("Не торкайтеся пристрою: запам'ятовується положення")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { s.calibrationProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CalibratingColor,
                    )
                }
                SecurityState.INTRUSION ->
                    Text("Причина: ${s.cause.description()}", color = IntrusionColor, fontWeight = FontWeight.SemiBold)
                else -> Text("Ударів за ${Thresholds.IMPULSE_WINDOW_NS / 1_000_000_000} с: ${s.impulsesInWindow} з ${Thresholds.IMPULSE_COUNT}")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.listening) "Сенсор активний (≈50 Гц)" else "Сенсор зупинено (onPause)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) {
                Text(
                    "Дані сенсора ненадійні",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onRearm) {
                Text(if (s.state == SecurityState.INTRUSION) "Скинути тривогу" else "Перекалібрувати")
            }
        }
    }
}

/** Шкала з зонами «тихо / вібрація / сильна вібрація» і маркером поточного RMS. */
@Composable
private fun VibrationCard(state: IntrusionUiState) {
    val rms = state.snapshot.rms
    val animated by animateFloatAsState(
        targetValue = (rms / VIBRATION_MAX).coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "vibration",
    )
    val markerColor = MaterialTheme.colorScheme.onSurface
    SectionCard("Рівень вібрації (RMS за 0,5 с): ${rms.fmt()} м/с²") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val warnX = size.width * Thresholds.VIB_WARN_RMS / VIBRATION_MAX
            val alarmX = size.width * Thresholds.VIB_ALARM_RMS / VIBRATION_MAX
            val r = CornerRadius(6.dp.toPx())
            drawRoundRect(IntrusionColor.copy(alpha = 0.35f), cornerRadius = r)
            drawRect(QuietColor.copy(alpha = 0.35f), size = Size(warnX, size.height))
            drawRect(
                VibrationColor.copy(alpha = 0.35f),
                topLeft = Offset(warnX, 0f),
                size = Size(alarmX - warnX, size.height),
            )
            val x = animated * size.width
            drawRect(
                markerColor,
                topLeft = Offset(x - 2.dp.toPx(), 0f),
                size = Size(4.dp.toPx(), size.height),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Тихо", color = QuietColor, style = MaterialTheme.typography.labelSmall)
            Text("${Thresholds.VIB_WARN_RMS} → Вібрація", color = VibrationColor, style = MaterialTheme.typography.labelSmall)
            Text("${Thresholds.VIB_ALARM_RMS} → Сильна", color = IntrusionColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AxesCard(state: IntrusionUiState) {
    val s = state.snapshot
    SectionCard("Координати акселерометра, м/с²") {
        AxisRow("X", s.x, AxisXColor)
        AxisRow("Y", s.y, AxisYColor)
        AxisRow("Z", s.z, AxisZColor)
        Text(
            "|a| = ${s.magnitude.fmt()}    |a − g| = ${s.dynamic.fmt()}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Значення з індикатором, що росте вліво (від'ємне) або вправо (додатне) від центру. */
@Composable
private fun AxisRow(name: String, value: Float, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val centerLine = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$name: ${value.fmt().padStart(6)}",
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(110.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp),
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val cx = size.width / 2
            val w = (value / AXIS_RANGE).coerceIn(-1f, 1f) * cx
            drawRect(
                color,
                topLeft = Offset(if (w < 0) cx + w else cx, 0f),
                size = Size(abs(w), size.height),
            )
            drawLine(centerLine, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
        }
    }
}

/**
 * Індикатор положення: кулька показує поточний нахил, кільце - запам'ятоване положення,
 * пунктирне коло навколо нього - зона, вихід за яку означає тривогу (≈ 10°).
 */
@Composable
private fun TiltCard(state: IntrusionUiState) {
    val s = state.snapshot
    val norm = sqrt(s.x * s.x + s.y * s.y + s.z * s.z).coerceAtLeast(0.1f)
    // Кулька котиться до нижчого краю пристрою, як у рівні.
    val tx by animateFloatAsState((-s.x / norm).coerceIn(-1f, 1f), tween(80), label = "tx")
    val ty by animateFloatAsState((s.y / norm).coerceIn(-1f, 1f), tween(80), label = "ty")
    val outline = MaterialTheme.colorScheme.outline
    val dotColor = s.state.color()
    val base = s.baseline
    SectionCard("Положення поверхні: відхилення ${s.tiltDeg.fmt(1)}° (поріг ${Thresholds.TILT_ALARM_DEG.toInt()}°)") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val radius = min(size.width, size.height) / 2
                val c = center
                // Шкала: два кола та перехрестя
                drawCircle(outline, radius, c, style = Stroke(2.dp.toPx()))
                drawCircle(outline, radius / 2, c, style = Stroke(1.dp.toPx()))
                drawLine(outline, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y))
                drawLine(outline, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius))
                val dotR = 14.dp.toPx()
                val reach = radius - dotR
                // Запам'ятоване положення і зона допустимого відхилення
                if (base != null) {
                    val bp = Offset(c.x - base.x * reach, c.y + base.y * reach)
                    val zone = sin(Math.toRadians(Thresholds.TILT_ALARM_DEG.toDouble())).toFloat() * reach
                    drawCircle(
                        IntrusionColor, zone + dotR, bp,
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                    drawCircle(outline, dotR + 3.dp.toPx(), bp, style = Stroke(2.dp.toPx()))
                }
                // Поточне положення
                drawCircle(dotColor, dotR, Offset(c.x + tx * reach, c.y + ty * reach))
            }
        }
        Text(
            "Кільце - запам'ятоване положення, червоне коло - межа тривоги. " +
                "Змінюйте нахил у Virtual Sensors емулятора.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Графік динамічного прискорення з пунктирними лініями порогів. */
@Composable
private fun SignalCard(state: IntrusionUiState) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val history = state.history
    SectionCard("Сигнал |a − g|, останні ~3 с") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            fun yOf(v: Float) = size.height - (v / CHART_MAX).coerceIn(0f, 1f) * size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            drawLine(grid, Offset(0f, yOf(0f)), Offset(size.width, yOf(0f)))
            drawLine(
                IntrusionColor, Offset(0f, yOf(Thresholds.IMPULSE_PEAK)),
                Offset(size.width, yOf(Thresholds.IMPULSE_PEAK)), strokeWidth = 2f, pathEffect = dash,
            )
            drawLine(
                VibrationColor, Offset(0f, yOf(Thresholds.VIB_ALARM_RMS)),
                Offset(size.width, yOf(Thresholds.VIB_ALARM_RMS)), strokeWidth = 2f, pathEffect = dash,
            )
            if (history.size >= 2) {
                val step = size.width / (HISTORY_SIZE - 1)
                val startX = size.width - step * (history.size - 1)
                val path = Path()
                history.forEachIndexed { i, v ->
                    val x = startX + i * step
                    if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
                }
                drawPath(path, line, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            "Червона лінія - поріг удару (${Thresholds.IMPULSE_PEAK} м/с²), " +
                "жовта - сильна вібрація (${Thresholds.VIB_ALARM_RMS} м/с²)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventLogCard(state: IntrusionUiState) {
    val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    SectionCard("Журнал подій") {
        if (state.events.isEmpty()) {
            Text("Подій ще немає", style = MaterialTheme.typography.bodyMedium)
        }
        state.events.take(8).forEach { e ->
            Row {
                Text(
                    format.format(Date(e.timeMillis)),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.critical) IntrusionColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (e.critical) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ThresholdsCard() {
    SectionCard("Порогові значення (Threshold)") {
        val rows = listOf(
            "Тихо → Вібрація" to "RMS ≥ ${Thresholds.VIB_WARN_RMS} м/с²",
            "Сильна вібрація" to "RMS ≥ ${Thresholds.VIB_ALARM_RMS} м/с²",
            "Тривала вібрація → тривога" to "≥ ${Thresholds.SUSTAIN_NS / 1_000_000} мс",
            "Удар (імпульс)" to "|a − g| > ${Thresholds.IMPULSE_PEAK} м/с²",
            "Серія ударів → тривога" to "${Thresholds.IMPULSE_COUNT} за ${Thresholds.IMPULSE_WINDOW_NS / 1_000_000_000} с",
            "Зміна нахилу → тривога" to "≥ ${Thresholds.TILT_ALARM_DEG.toInt()}°",
            "Калібрування" to "${Thresholds.CALIBRATION_NS / 1_000_000_000} с",
        )
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = MaterialTheme.typography.bodyMedium)
                Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
