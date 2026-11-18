package com.example.intrusiondetector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.intrusiondetector.storage.AnalyticsUiState
import com.example.intrusiondetector.storage.MAX_CHART_POINTS
import com.example.intrusiondetector.storage.RECORD_INTERVAL_MS
import com.example.intrusiondetector.storage.SensorRecord
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

// ЛР5, шар View (MVVM): екран «Аналітика» — статус запису, графік RMS і список спрацювань.

/** Мінімальна відстань між підписами осі X, с (≈ кожні 10 с при записі раз на секунду). */
private const val MIN_LABEL_SPACING_S = 10

/** Бажана кількість підписів на осі X. */
private const val TARGET_LABELS = 5

/**
 * Ключ додаткових даних моделі Vico: крок підписів осі X (у секундах), обчислений з даних.
 * Записується в тій самій транзакції, що й точки, тож підписи завжди відповідають даним.
 */
private val labelSpacingKey = ExtraStore.Key<Int>()

/**
 * ЛР5: екран «Аналітика».
 *
 * @param state стан з `SensorViewModel` (дані з Room).
 * @param onClear очищає історію в БД.
 */
@Composable
fun AnalyticsScreen(state: AnalyticsUiState, onClear: () -> Unit) {
    // Формат часу з явним Locale, як у ControlViewModel: без Locale результат залежав би від
    // мовних налаштувань пристрою (і lint попереджає про це). remember — один об'єкт на весь екран.
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Аналітика", style = MaterialTheme.typography.titleLarge)
        StatusCard(state, onClear)
        ChartCard(state.chart, timeFormat)
        TriggeredCard(state.triggered, timeFormat)
    }
}

/** Картка статусу запису в БД. */
@Composable
private fun StatusCard(state: AnalyticsUiState, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Запис у БД: " + if (state.recording) "активний" else "на паузі (сенсор зупинено)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Записів у БД: ${state.totalCount}")
            Text("Інтервал: ${RECORD_INTERVAL_MS / 1000} с")
            state.chart.lastOrNull()?.let {
                Text("Останнє значення RMS: ${"%.2f".format(Locale.US, it.sensorValue)} м/с²")
            }
            OutlinedButton(onClick = onClear) { Text("Очистити історію") }
        }
    }
}

/** Картка з лінійним графіком Vico. */
@Composable
private fun ChartCard(records: List<SensorRecord>, timeFormat: SimpleDateFormat) {
    // remember: producer переживає рекомпозицію. Без remember на кожній рекомпозиції створювався б
    // новий producer, графік втрачав би дані й анімацію. ModelProducer потрібен, щоб Vico сам
    // анімував перехід між старою й новою моделлю; runTransaction атомарно замінює всі дані.
    val producer = remember { CartesianChartModelProducer() }

    // LaunchedEffect(key): корутина перезапускається при зміні ключа — тобто коли Room віддав новий
    // список — і скасовується, коли composable зникає з екрана.
    LaunchedEffect(records) {
        if (records.size >= 2) {
            // X — абсолютні секунди epoch: форматер осі перетворює X назад на HH:mm:ss без
            // додаткового стану, і немає гонки між даними та форматером
            val xs = records.map { it.timestamp / 1000.0 }
            val ys = records.map { it.sensorValue }
            // Крок підписів: ≈ TARGET_LABELS підписів на весь діапазон, але не частіше ніж раз на 10 с.
            // Фіксований крок 10 с дав би сотні підписів, якщо в історії є розрив (застосунок був згорнутий)
            val range = xs.last() - xs.first()
            val spacing = maxOf(MIN_LABEL_SPACING_S, ceil(range / TARGET_LABELS).toInt())
            producer.runTransaction {
                lineSeries { series(xs, ys) }
                extras { it[labelSpacingKey] = spacing }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "RMS вібрації, м/с² — останні $MAX_CHART_POINTS записів",
                style = MaterialTheme.typography.titleMedium,
            )
            if (records.size < 2) {
                Text(
                    "Недостатньо даних: покрутіть пристрій (Extended Controls → Virtual Sensors)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(),
                        // Вісь Y: два знаки після коми
                        startAxis = VerticalAxis.rememberStart(
                            valueFormatter = remember { CartesianValueFormatter.decimal(DecimalFormat("0.00")) },
                        ),
                        // Вісь X: секунди epoch → HH:mm:ss; підписи з кроком labelSpacingKey
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = remember {
                                CartesianValueFormatter { _, value, _ -> timeFormat.format(Date((value * 1000).toLong())) }
                            },
                            itemPlacer = remember {
                                HorizontalAxis.ItemPlacer.aligned(
                                    spacing = { it.getOrNull(labelSpacingKey) ?: MIN_LABEL_SPACING_S },
                                )
                            },
                        ),
                        // Фіксований крок X = 1 с. За замовчуванням Vico бере НСД різниць між X;
                        // записи подій мають мілісекундні зсуви, тож НСД вийшов би крихітним,
                        // а графік — гігантським за шириною
                        getXStep = { 1.0 },
                    ),
                    modelProducer = producer,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    // Скрол і зум вимкнено, весь вміст вписаний у ширину картки
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
                )
            }
        }
    }
}

/** Картка зі списком спрацювань актуаторів (`actionTriggered = true`), нові зверху. */
@Composable
private fun TriggeredCard(triggered: List<SensorRecord>, timeFormat: SimpleDateFormat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Спрацювання актуаторів", style = MaterialTheme.typography.titleMedium)
            if (triggered.isEmpty()) {
                Text("Спрацювань ще не було", style = MaterialTheme.typography.bodySmall)
            }
            triggered.forEach {
                Text(
                    "${timeFormat.format(Date(it.timestamp))} · RMS ${"%.2f".format(Locale.US, it.sensorValue)} м/с²",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
