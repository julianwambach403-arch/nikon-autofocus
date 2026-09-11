package de.nikonautofocus.app.capture

enum class IntervalPhase {
    IDLE,
    DELAY,
    RUNNING,
    PAUSED,
    FINISHED,
    CANCELLED,
    FAILED
}

data class IntervalSession(
    val phase: IntervalPhase = IntervalPhase.IDLE,
    val slotIndex: Int = 0,
    val slotCount: Int = 0,
    val photosTaken: Int = 0,
    val photosExpected: Int = 0,
    val missedSlots: Int = 0,
    val countdownMs: Long = 0,
    val etaMs: Long = 0,
    val shotsPerSlot: Int = 1,
    val log: List<String> = emptyList(),
    val lastMessage: String? = null
) {
    val active: Boolean
        get() = phase == IntervalPhase.DELAY ||
            phase == IntervalPhase.RUNNING ||
            phase == IntervalPhase.PAUSED

    val progressLabel: String
        get() = when (phase) {
            IntervalPhase.IDLE -> "keine Serie"
            IntervalPhase.FINISHED -> "fertig ($photosTaken/$photosExpected Fotos)"
            IntervalPhase.CANCELLED -> "abgebrochen ($photosTaken/$photosExpected)"
            IntervalPhase.FAILED -> lastMessage ?: "fehlgeschlagen"
            IntervalPhase.PAUSED -> "pausiert — Reihe ${slotIndex + 1}/$slotCount"
            IntervalPhase.DELAY -> "Start in ${formatClock(countdownMs)}"
            IntervalPhase.RUNNING ->
                "Reihe ${slotIndex.coerceAtMost(slotCount)}/$slotCount  ·  " +
                    "$photosTaken/$photosExpected Fotos"
        }

    fun notificationText(): String {
        val next = if (phase == IntervalPhase.PAUSED) {
            "pausiert"
        } else if (countdownMs > 0) {
            "naechste in ${formatClock(countdownMs)}"
        } else {
            "Aufnahme"
        }
        return "Reihe ${slotIndex.coerceAtLeast(0)}/$slotCount · $next" +
            if (missedSlots > 0) " · $missedSlots verpasst" else ""
    }

    companion object {
        fun idle(): IntervalSession = IntervalSession()

        fun formatClock(ms: Long): String {
            val total = (ms / 1000L).coerceAtLeast(0L)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                "%d:%02d:%02d".format(h, m, s)
            } else {
                "%d:%02d".format(m, s)
            }
        }
    }
}
