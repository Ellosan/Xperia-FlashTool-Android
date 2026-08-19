package dev.flashtool.xperia.flash

import dev.flashtool.xperia.s1.S1Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the running flash job.
 *
 * A flash must not be cancelled just because the Activity went away — a half-written phone does
 * not boot. The job therefore lives here, outside any ViewModel, and a foreground service keeps
 * the process alive while it runs.
 */
object Flasher {

    val engine = FlashEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(plan: FlashPlan, transport: S1Transport, dryRun: Boolean, onFinish: () -> Unit = {}) {
        check(!isRunning) { "A flash is already running" }
        job = scope.launch {
            try {
                engine.run(plan, transport, dryRun)
            } catch (_: Throwable) {
                // The engine has already recorded the failure in its state and in the log.
            } finally {
                onFinish()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
