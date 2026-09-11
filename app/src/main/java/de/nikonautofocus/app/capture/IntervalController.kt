package de.nikonautofocus.app.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

data class IntervalSlotResult(
    val photos: Int,
    val warning: String? = null
)

/**
 * Start-to-start interval scheduler. Capture itself is injected so tests can run without USB.
 *
 * Pause freezes the next slot; resume fires that slot immediately and keeps the original
 * interval for the rest. A slot that starts late (busy body, long exposure) is still taken
 * and counted as missed.
 */
class IntervalController(
    private val clockMs: () -> Long,
    private val delayMs: suspend (Long) -> Unit
) {

    constructor() : this(
        clockMs = { System.currentTimeMillis() },
        delayMs = { kotlinx.coroutines.delay(it) }
    )

    private val _session = MutableStateFlow(IntervalSession.idle())
    val session: StateFlow<IntervalSession> = _session.asStateFlow()

    @Volatile
    var paused: Boolean = false
        private set

    @Volatile
    private var cancelRequested: Boolean = false

    val cancelling: Boolean get() = cancelRequested

    fun pause() {
        if (!_session.value.active) return
        paused = true
        emit(_session.value.copy(phase = IntervalPhase.PAUSED, lastMessage = "pausiert"))
    }

    fun resume() {
        if (_session.value.phase != IntervalPhase.PAUSED) return
        paused = false
        emit(_session.value.copy(phase = IntervalPhase.RUNNING, lastMessage = "fortgesetzt"))
    }

    fun requestCancel() {
        cancelRequested = true
    }

    fun reset() {
        paused = false
        cancelRequested = false
        _session.value = IntervalSession.idle()
    }

    suspend fun run(
        settings: IntervalSettings,
        shootSlot: suspend (index: Int, missed: Boolean) -> IntervalSlotResult
    ) {
        val plan = settings.sanitized()
        cancelRequested = false
        paused = false
        val log = ArrayList<String>()
        var photos = 0
        var missed = 0
        var t0 = clockMs()
        var rebaseAfterPause = false

        emit(
            IntervalSession(
                phase = if (plan.delayMs > 0) IntervalPhase.DELAY else IntervalPhase.RUNNING,
                slotIndex = 0,
                slotCount = plan.shotCount,
                photosTaken = 0,
                photosExpected = plan.totalPhotos,
                missedSlots = 0,
                countdownMs = plan.delayMs,
                etaMs = plan.delayMs + (plan.shotCount - 1).coerceAtLeast(0) * plan.intervalMs,
                shotsPerSlot = plan.shotsPerSlot
            )
        )

        var index = 0
        try {
            while (index < plan.shotCount) {
                if (!coroutineContext.isActive || cancelRequested) break

                if (paused) {
                    emit(
                        _session.value.copy(
                            phase = IntervalPhase.PAUSED,
                            slotIndex = index,
                            countdownMs = 0
                        )
                    )
                    while (paused && !cancelRequested && coroutineContext.isActive) {
                        delayMs(TICK_MS)
                    }
                    rebaseAfterPause = true
                    if (cancelRequested || !coroutineContext.isActive) break
                }

                if (rebaseAfterPause) {
                    t0 = IntervalTiming.rebaseT0(
                        nowMs = clockMs(),
                        delayMs = 0L,
                        intervalMs = plan.intervalMs,
                        resumeIndex = index
                    )
                    rebaseAfterPause = false
                }

                val due = IntervalTiming.slotDueAt(t0, plan.delayMs, plan.intervalMs, index)
                val waitResult = waitForSlot(due, plan, index, photos, missed)
                if (waitResult == WaitResult.CANCELLED) break
                if (waitResult == WaitResult.PAUSED) {
                    rebaseAfterPause = true
                    continue
                }

                val now = clockMs()
                val slotMissed = IntervalTiming.isMissed(now, due)
                if (slotMissed) {
                    missed++
                    log += "Reihe ${index + 1}: verspaetet (${now - due} ms hinter dem Slot)"
                }

                emit(
                    _session.value.copy(
                        phase = IntervalPhase.RUNNING,
                        slotIndex = index,
                        photosTaken = photos,
                        missedSlots = missed,
                        countdownMs = 0,
                        lastMessage = if (slotMissed) "Slot verpasst, loese trotzdem aus" else "Aufnahme"
                    )
                )

                val result = try {
                    shootSlot(index, slotMissed)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    missed++
                    log += "Reihe ${index + 1}: ${t.message ?: t.javaClass.simpleName}"
                    IntervalSlotResult(0, t.message)
                }

                photos += result.photos
                if (result.warning != null) {
                    log += "Reihe ${index + 1}: ${result.warning}"
                }
                index++
                emit(
                    _session.value.copy(
                        slotIndex = index,
                        photosTaken = photos,
                        missedSlots = missed,
                        log = log.takeLast(LOG_CAP),
                        lastMessage = result.warning,
                        etaMs = IntervalTiming.remainingEtaMs(
                            nowMs = clockMs(),
                            nextDueMs = IntervalTiming.slotDueAt(
                                t0, plan.delayMs, plan.intervalMs, index
                            ),
                            remainingSlotsIncludingNext = plan.shotCount - index,
                            intervalMs = plan.intervalMs
                        )
                    )
                )
            }
        } catch (c: CancellationException) {
            emit(finished(IntervalPhase.CANCELLED, photos, plan, missed, log, "abgebrochen"))
            throw c
        }

        val phase = when {
            cancelRequested -> IntervalPhase.CANCELLED
            index < plan.shotCount -> IntervalPhase.CANCELLED
            else -> IntervalPhase.FINISHED
        }
        emit(
            finished(
                phase,
                photos,
                plan,
                missed,
                log,
                if (phase == IntervalPhase.FINISHED) "Serie beendet" else "abgebrochen"
            )
        )
    }

    private suspend fun waitForSlot(
        due: Long,
        plan: IntervalSettings,
        index: Int,
        photos: Int,
        missed: Int
    ): WaitResult {
        while (true) {
            if (cancelRequested || !coroutineContext.isActive) return WaitResult.CANCELLED
            if (paused) return WaitResult.PAUSED
            val now = clockMs()
            val left = due - now
            val phase = if (index == 0 && plan.delayMs > 0L && left > 0L) {
                IntervalPhase.DELAY
            } else {
                IntervalPhase.RUNNING
            }
            emit(
                _session.value.copy(
                    phase = phase,
                    slotIndex = index,
                    photosTaken = photos,
                    missedSlots = missed,
                    countdownMs = left.coerceAtLeast(0L),
                    etaMs = IntervalTiming.remainingEtaMs(
                        nowMs = now,
                        nextDueMs = due,
                        remainingSlotsIncludingNext = plan.shotCount - index,
                        intervalMs = plan.intervalMs
                    )
                )
            )
            if (left <= 0L) return WaitResult.DUE
            delayMs(left.coerceAtMost(TICK_MS))
        }
    }

    private fun finished(
        phase: IntervalPhase,
        photos: Int,
        plan: IntervalSettings,
        missed: Int,
        log: List<String>,
        message: String
    ): IntervalSession = IntervalSession(
        phase = phase,
        slotIndex = plan.shotCount,
        slotCount = plan.shotCount,
        photosTaken = photos,
        photosExpected = plan.totalPhotos,
        missedSlots = missed,
        countdownMs = 0,
        etaMs = 0,
        shotsPerSlot = plan.shotsPerSlot,
        log = log.takeLast(LOG_CAP),
        lastMessage = message
    )

    private fun emit(next: IntervalSession) {
        _session.value = next
    }

    private enum class WaitResult { DUE, PAUSED, CANCELLED }

    companion object {
        private const val TICK_MS = 200L
        private const val LOG_CAP = 40
    }
}
