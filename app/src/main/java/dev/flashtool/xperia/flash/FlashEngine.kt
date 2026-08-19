package dev.flashtool.xperia.flash

import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.core.humanSize
import dev.flashtool.xperia.s1.S1Session
import dev.flashtool.xperia.s1.S1Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs a [FlashPlan] against a phone in flash mode.
 *
 * The whole run is one coroutine: cancelling it aborts between packets rather than mid-packet, so
 * the phone is never left with a half-written frame in its buffer.
 */
class FlashEngine {

    private val _state = MutableStateFlow(FlashState())
    val state: StateFlow<FlashState> = _state.asStateFlow()

    /**
     * Executes the plan. Returns normally on success and throws on failure; [state] carries the
     * details either way so the UI does not have to catch anything.
     */
    suspend fun run(plan: FlashPlan, transport: S1Transport, dryRun: Boolean = false) {
        val ftf = plan.ftf
        _state.value = FlashState(
            phase = FlashPhase.CONNECTING,
            totalBytes = plan.totalBytes,
            startedAt = System.currentTimeMillis(),
            dryRun = dryRun,
        )

        FlashLog.i("=".repeat(60))
        FlashLog.i("Flashing ${ftf.name}${if (dryRun) " (DRY RUN — nothing is written to a phone)" else ""}")
        FlashLog.i("Target: ${transport.description}")
        FlashLog.i("${plan.images.size} images, ${plan.taFiles.size} TA files, ${humanSize(plan.totalBytes)} to send")

        var done = 0L
        try {
            S1Session(transport).use { session ->
                // ---- loader -------------------------------------------------
                plan.loader?.let { loader ->
                    currentCoroutineContext().ensureActive()
                    setPhase(FlashPhase.SENDING_LOADER, loader.displayName, loader.dataLength, done)
                    ftf.openImageData(loader).use { data ->
                        session.sendLoader(ftf.readHeader(loader), data, loader.dataLength) { sent ->
                            _state.value = _state.value.copy(currentBytes = sent)
                        }
                    }
                    done += loader.dataLength
                }

                // ---- TA units ----------------------------------------------
                if (plan.taFiles.isNotEmpty()) {
                    setPhase(FlashPhase.WRITING_TA, "TA units", 0, done)
                    for (taFile in plan.taFiles) {
                        currentCoroutineContext().ensureActive()
                        FlashLog.i("Writing ${taFile.displayName}: ${taFile.content.units.size} units into partition ${taFile.content.partition}")
                        session.openTa(taFile.content.partition)
                        try {
                            for (unit in taFile.content.units) {
                                currentCoroutineContext().ensureActive()
                                session.writeTaUnit(unit.unit, unit.value)
                                FlashLog.d("TA unit ${unit.label} <- ${unit.value.size} bytes")
                            }
                        } finally {
                            session.closeTa()
                        }
                        FlashLog.ok("${taFile.displayName} written")
                    }
                }

                // ---- images -------------------------------------------------
                for (image in plan.images) {
                    currentCoroutineContext().ensureActive()
                    setPhase(FlashPhase.FLASHING, image.displayName, image.dataLength, done)
                    FlashLog.i("Flashing ${image.displayName} -> ${image.partition} (${humanSize(image.dataLength)})")

                    ftf.openImageData(image).use { data ->
                        session.sendFile(image.displayName, ftf.readHeader(image), data, image.dataLength) { sent ->
                            _state.value = _state.value.copy(currentBytes = sent)
                        }
                    }
                    done += image.dataLength
                    FlashLog.ok("${image.displayName} done")
                }

                // ---- teardown -----------------------------------------------
                setPhase(FlashPhase.FINISHING, "Closing session", 0, done)
                session.endSession()
                if (plan.rebootWhenDone) {
                    FlashLog.i("Asking the phone to reboot")
                    session.reboot()
                }
            }

            _state.value = _state.value.copy(
                phase = FlashPhase.DONE,
                currentItem = "",
                currentBytes = 0,
                currentTotal = 0,
                doneBytes = done,
                finishedAt = System.currentTimeMillis(),
            )
            FlashLog.ok("Flashing finished in ${_state.value.elapsedMs / 1000}s")
            if (!plan.rebootWhenDone) {
                FlashLog.i("The phone is still in flash mode — unplug it and hold power to boot.")
            }
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(
                phase = FlashPhase.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                error = "Cancelled",
            )
            FlashLog.w("Flashing cancelled by the user — the phone is very likely in an unbootable state, reflash before unplugging")
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                phase = FlashPhase.FAILED,
                finishedAt = System.currentTimeMillis(),
                error = e.message ?: e.javaClass.simpleName,
            )
            FlashLog.e("Flashing failed", e)
            throw e
        }
    }

    fun reset() {
        _state.value = FlashState()
    }

    private fun setPhase(phase: FlashPhase, item: String, currentTotal: Long, done: Long) {
        _state.value = _state.value.copy(
            phase = phase,
            currentItem = item,
            currentBytes = 0,
            currentTotal = currentTotal,
            doneBytes = done,
        )
    }
}
