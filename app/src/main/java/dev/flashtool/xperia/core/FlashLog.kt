package dev.flashtool.xperia.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR, SUCCESS }

data class LogLine(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
) {
    fun format(): String = "${TIME_FORMAT.format(Date(timestamp))} - ${level.name} - $message"

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Process-wide log sink. Everything the flasher does ends up here so the user can read back
 * exactly what was sent to the phone when something goes wrong — the log is usually the only
 * evidence available after a failed flash.
 */
object FlashLog {

    private const val MAX_LINES = 8000

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    /** Verbose packet-level tracing. Off by default because it is very noisy. */
    @Volatile
    var debugEnabled: Boolean = false

    fun d(message: String) {
        if (debugEnabled) append(LogLevel.DEBUG, message)
    }

    fun i(message: String) = append(LogLevel.INFO, message)
    fun w(message: String) = append(LogLevel.WARN, message)
    fun e(message: String) = append(LogLevel.ERROR, message)
    fun ok(message: String) = append(LogLevel.SUCCESS, message)

    fun e(message: String, t: Throwable) {
        append(LogLevel.ERROR, "$message: ${t.javaClass.simpleName}: ${t.message}")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = _lines.value.joinToString("\n") { it.format() }

    private fun append(level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), level, message)
        _lines.value = (_lines.value + line).let { if (it.size > MAX_LINES) it.takeLast(MAX_LINES) else it }
    }
}
