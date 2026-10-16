package com.example.intrusiondetector.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Напрямок запису в журналі терміналу. */
enum class LogDir { TX, RX, SYS, ERR }

/** Рядок журналу терміналу. [warning] — повтор або втрата пакета. */
data class LogLine(val timeMillis: Long, val dir: LogDir, val text: String, val warning: Boolean = false)

/** Приймач для Logcat: пріоритет `Log.DEBUG/WARN/ERROR` і текст. */
typealias LogSink = (priority: Int, message: String) -> Unit

/** Logcat із тегом `IntrusionBLE`. У JVM-тестах підміняється. */
val LogcatSink: LogSink = { priority, message -> Log.println(priority, BleLogger.TAG, message) }

/**
 * Спільний журнал BLE-операцій: пише в Logcat і тримає останні [MAX_LINES] рядків для терміналу.
 */
class BleLogger(
    private val sink: LogSink = LogcatSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    /** Запис у характеристику: `TX → [A103] 07|ALARM|KNOCK (14 B)`. */
    fun tx(characteristic: UUID, raw: String, note: String = "") =
        add(LogDir.TX, "→ [${HubProfile.shortId(characteristic)}] $raw (${raw.length} B)$note", Log.DEBUG)

    /** Notify або читання: `RX ← [A104] ACK|07`. */
    fun rx(characteristic: UUID, raw: String) =
        add(LogDir.RX, "← [${HubProfile.shortId(characteristic)}] $raw", Log.DEBUG)

    fun sys(text: String) = add(LogDir.SYS, text, Log.DEBUG)

    /** Повтори, втрати пакетів, розриви. */
    fun warn(text: String) = add(LogDir.SYS, text, Log.WARN, warning = true)

    fun error(text: String) = add(LogDir.ERR, text, Log.ERROR)

    fun clear() = _lines.update { emptyList() }

    private fun add(dir: LogDir, text: String, priority: Int, warning: Boolean = false) {
        sink(priority, "${dir.name} $text")
        val line = LogLine(clock(), dir, text, warning)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    companion object {
        const val TAG = "IntrusionBLE"
        const val MAX_LINES = 200
    }
}
