package dev.flashtool.xperia.flash

import dev.flashtool.xperia.core.humanSize

enum class FlashPhase(val title: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    SENDING_LOADER("Sending loader"),
    WRITING_TA("Writing TA units"),
    FLASHING("Flashing"),
    FINISHING("Finishing"),
    DONE("Finished"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    ;

    val isRunning: Boolean
        get() = this != IDLE && this != DONE && this != FAILED && this != CANCELLED
}

data class FlashState(
    val phase: FlashPhase = FlashPhase.IDLE,
    val currentItem: String = "",
    val currentBytes: Long = 0,
    val currentTotal: Long = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val error: String? = null,
    val dryRun: Boolean = false,
) {
    val overallFraction: Float
        get() = if (totalBytes <= 0) 0f else ((doneBytes + currentBytes).toFloat() / totalBytes).coerceIn(0f, 1f)

    val currentFraction: Float
        get() = if (currentTotal <= 0) 0f else (currentBytes.toFloat() / currentTotal).coerceIn(0f, 1f)

    val elapsedMs: Long
        get() = when {
            startedAt == 0L -> 0
            finishedAt != 0L -> finishedAt - startedAt
            else -> System.currentTimeMillis() - startedAt
        }

    /** Bytes per second so far, or null before there is enough data to be meaningful. */
    val throughput: Long?
        get() {
            val elapsed = elapsedMs
            val transferred = doneBytes + currentBytes
            return if (elapsed < 1000 || transferred <= 0) null else transferred * 1000 / elapsed
        }

    val throughputText: String get() = throughput?.let { "${humanSize(it)}/s" } ?: "—"

    val etaText: String
        get() {
            val rate = throughput ?: return "—"
            val remaining = totalBytes - doneBytes - currentBytes
            if (remaining <= 0) return "—"
            val seconds = remaining / rate
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
}
