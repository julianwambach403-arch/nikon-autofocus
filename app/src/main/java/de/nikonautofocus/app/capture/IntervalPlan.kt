package de.nikonautofocus.app.capture

/**
 * User-facing interval settings. Times are stored as three fields so the UI can edit
 * hours / minutes / seconds independently; [intervalMs] is what the scheduler uses.
 */
data class IntervalSettings(
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 5,
    val shotCount: Int = 10,
    val delaySeconds: Int = 0,
    val bracketing: BracketingSettings = BracketingSettings()
) {
    fun sanitized(): IntervalSettings {
        val h = hours.coerceIn(0, 23)
        val m = minutes.coerceIn(0, 59)
        val s = seconds.coerceIn(0, 59)
        val intervalSec = (h * 3600 + m * 60 + s).coerceAtLeast(MIN_INTERVAL_SEC)
        return copy(
            hours = intervalSec / 3600,
            minutes = (intervalSec % 3600) / 60,
            seconds = intervalSec % 60,
            shotCount = shotCount.coerceIn(MIN_COUNT, MAX_COUNT),
            delaySeconds = delaySeconds.coerceIn(0, MAX_DELAY_SEC),
            bracketing = bracketing.sanitized()
        )
    }

    val intervalMs: Long
        get() = (hours * 3600L + minutes * 60L + seconds) * 1000L

    val delayMs: Long get() = delaySeconds * 1000L

    val shotsPerSlot: Int get() = bracketing.shotsPerSeries

    val totalPhotos: Int get() = shotCount * shotsPerSlot

    companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MIN_COUNT = 1
        const val MAX_COUNT = 9_999
        const val MAX_DELAY_SEC = 3_600
        const val DEFAULT_SAVE_BUFFER_MS = 1_500L
    }
}

object IntervalTiming {

    fun slotDueAt(t0Ms: Long, delayMs: Long, intervalMs: Long, index: Int): Long =
        t0Ms + delayMs + index * intervalMs

    fun isMissed(nowMs: Long, dueMs: Long, graceMs: Long = 250L): Boolean =
        nowMs > dueMs + graceMs

    /**
     * After a pause, remaining slots start at [nowMs]: slot [resumeIndex] fires immediately,
     * later slots keep the original interval.
     */
    fun rebaseT0(nowMs: Long, delayMs: Long, intervalMs: Long, resumeIndex: Int): Long =
        nowMs - delayMs - resumeIndex * intervalMs

    fun remainingEtaMs(
        nowMs: Long,
        nextDueMs: Long,
        remainingSlotsIncludingNext: Int,
        intervalMs: Long
    ): Long {
        if (remainingSlotsIncludingNext <= 0) return 0L
        val untilNext = (nextDueMs - nowMs).coerceAtLeast(0L)
        return untilNext + (remainingSlotsIncludingNext - 1).coerceAtLeast(0) * intervalMs
    }

    fun shutterMs(shutterPtp: Long): Long {
        if (BracketingPlan.isBulb(shutterPtp)) return 0L
        return (shutterPtp * 1_000L) / 10_000L
    }

    /**
     * Soft warnings (do not block start). [bracket] is the prepared series when bracketing
     * is on; otherwise duration is one shot of [shutterPtp] plus the save buffer.
     */
    fun durationWarnings(
        intervalMs: Long,
        shutterPtp: Long,
        saveBufferMs: Long,
        bracket: PreparedBracket?
    ): List<String> {
        val warnings = mutableListOf<String>()
        val shotMs = shutterMs(shutterPtp) + saveBufferMs
        if (bracket == null || !bracket.shots.any { it.thirds != 0 }) {
            if (intervalMs < shotMs) {
                warnings += "Das Intervall (" +
                    IntervalSession.formatClock(intervalMs) +
                    ") ist kuerzer als Belichtungszeit plus Speicherpuffer (" +
                    IntervalSession.formatClock(shotMs) +
                    "). Slots werden dann als verpasst gezaehlt."
            }
        } else {
            val seriesMs = BracketingPlan.estimatedDurationMs(bracket, saveBufferMs, shutterMs(shutterPtp))
            if (seriesMs > intervalMs) {
                warnings += "Eine Belichtungsreihe braucht etwa " +
                    IntervalSession.formatClock(seriesMs) +
                    ", das Intervall ist nur " +
                    IntervalSession.formatClock(intervalMs) +
                    ". Die Reihe laeuft in den naechsten Slot."
            }
        }
        return warnings
    }
}
