package de.nikonautofocus.app.focus

import de.nikonautofocus.app.analysis.MovingAverage

/** The eight states of the focus pipeline. */
enum class FocusState {
    DISCONNECTED,
    CONNECTING,
    LIVEVIEW,
    ANALYZING,
    BLUR_DETECTED,
    AUTOFOCUS_TRIGGERED,
    COOLDOWN,
    ERROR
}

/** What the caller has to do after feeding an event into the machine. */
enum class FocusAction {
    NONE,

    /** Send exactly one autofocus command now. */
    TRIGGER_AUTOFOCUS
}

/** Immutable view of the machine for the UI. */
data class FocusStatus(
    val state: FocusState,
    val monitoring: Boolean,
    val rawScore: Double,
    val smoothedScore: Double,
    val threshold: Double,
    val blurryFrames: Int,
    val requiredBlurryFrames: Int,
    val samplesInWindow: Int,
    val windowSize: Int,
    val windowWarm: Boolean,
    val cooldownRemainingMs: Long,
    val cooldownTotalMs: Long,
    val autofocusCount: Int,
    val lastAutofocusResult: String?,
    val autofocusDisabledReason: String?,
    val errorMessage: String?
) {
    /** Sharp / blurred verdict for the big on screen badge. */
    val isBlurry: Boolean
        get() = if (windowWarm) smoothedScore < threshold else rawScore < threshold

    val isSharp: Boolean get() = !isBlurry

    val blurProgress: Float
        get() = if (requiredBlurryFrames <= 0) 0f
        else (blurryFrames.toFloat() / requiredBlurryFrames).coerceIn(0f, 1f)

    val cooldownProgress: Float
        get() = if (cooldownTotalMs <= 0) 0f
        else (cooldownRemainingMs.toFloat() / cooldownTotalMs).coerceIn(0f, 1f)
}

/**
 * The anti focus-pumping logic, as an explicit state machine.
 *
 *   DISCONNECTED -> CONNECTING -> LIVEVIEW -> ANALYZING
 *                                                |
 *                        N consecutive blurry frames (on the smoothed score)
 *                                                v
 *                                        BLUR_DETECTED
 *                                                |
 *                                                v
 *                                     AUTOFOCUS_TRIGGERED  (exactly one command)
 *                                                |
 *                                                v
 *                                           COOLDOWN
 *                                                |
 *                                        cooldown elapsed
 *                                                v
 *                                           ANALYZING
 *
 * Three separate mechanisms keep a fluctuating measurement from producing an endless
 * focus loop:
 *
 *  1. the decision runs on the smoothed score, never on a single frame;
 *  2. the blur condition has to hold for N consecutive frames, and a single sharp frame
 *     resets that counter to zero;
 *  3. once fired, the machine leaves the analysing state entirely. Frames that arrive
 *     while the camera is focusing are discarded (LiveView freezes and shows garbage
 *     during the AF drive), and the cooldown period cannot be shortened by any
 *     measurement.
 *
 * Pure Kotlin, no Android imports: this is the part of the app that is worth unit testing.
 */
class FocusStateMachine(
    settings: FocusSettings = FocusSettings(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    var settings: FocusSettings = settings.sanitized()
        private set

    private val average = MovingAverage(this.settings.movingAverageSize)

    private var state: FocusState = FocusState.DISCONNECTED
    private var monitoring = false
    private var rawScore = 0.0
    private var blurryFrames = 0
    private var cooldownStartedAt = 0L
    private var autofocusCount = 0
    private var lastAutofocusResult: String? = null
    private var autofocusDisabledReason: String? = null
    private var errorMessage: String? = null

    // ------------------------------------------------------------------ configuration

    fun updateSettings(newSettings: FocusSettings) {
        val sanitized = newSettings.sanitized()
        val windowChanged = sanitized.movingAverageSize != settings.movingAverageSize
        settings = sanitized
        if (windowChanged) {
            average.resize(sanitized.movingAverageSize)
            blurryFrames = 0
        }
        // A raised threshold must not immediately fire on a counter that was accumulated
        // under the old one.
        if (blurryFrames > sanitized.requiredBlurryFrames) {
            blurryFrames = sanitized.requiredBlurryFrames
        }
    }

    // ------------------------------------------------------------------ connection events

    fun onConnecting() {
        state = FocusState.CONNECTING
        errorMessage = null
    }

    fun onLiveViewReady() {
        state = FocusState.LIVEVIEW
        errorMessage = null
        resetMeasurement()
    }

    fun onDisconnected() {
        state = FocusState.DISCONNECTED
        monitoring = false
        resetMeasurement()
    }

    fun onError(message: String) {
        state = FocusState.ERROR
        monitoring = false
        errorMessage = message
    }

    fun clearError() {
        if (state == FocusState.ERROR) {
            state = if (monitoring) FocusState.ANALYZING else FocusState.LIVEVIEW
        }
        errorMessage = null
    }

    // ------------------------------------------------------------------ monitoring control

    fun startMonitoring(): Boolean {
        if (autofocusDisabledReason != null) return false
        if (state != FocusState.LIVEVIEW && state != FocusState.ANALYZING &&
            state != FocusState.COOLDOWN && state != FocusState.ERROR
        ) {
            return false
        }
        monitoring = true
        errorMessage = null
        resetMeasurement()
        state = FocusState.ANALYZING
        return true
    }

    fun stopMonitoring() {
        monitoring = false
        blurryFrames = 0
        if (state != FocusState.DISCONNECTED && state != FocusState.ERROR) {
            state = FocusState.LIVEVIEW
        }
    }

    // ------------------------------------------------------------------ frame processing

    /**
     * Feeds one measured frame into the machine.
     * @return [FocusAction.TRIGGER_AUTOFOCUS] exactly once per detected blur episode.
     */
    fun onFrameScore(score: Double): FocusAction {
        rawScore = score

        // Cooldown may have expired between two frames.
        advanceCooldown()

        when (state) {
            FocusState.AUTOFOCUS_TRIGGERED -> {
                // LiveView is frozen or garbage while the AF drive runs. Ignore completely.
                return FocusAction.NONE
            }

            FocusState.COOLDOWN -> {
                // Keep the smoothed value alive and let the window warm up again, but never
                // count blur or trigger during the cooldown.
                average.add(score)
                blurryFrames = 0
                return FocusAction.NONE
            }

            FocusState.LIVEVIEW -> {
                // Preview only, no monitoring: still show a live smoothed value.
                average.add(score)
                return FocusAction.NONE
            }

            FocusState.ANALYZING -> Unit

            else -> return FocusAction.NONE
        }

        val smoothed = average.add(score)

        if (!average.isWarm) {
            // Not enough samples yet for a trustworthy decision.
            blurryFrames = 0
            return FocusAction.NONE
        }

        if (smoothed < settings.threshold) {
            blurryFrames++
        } else {
            blurryFrames = 0
        }

        if (blurryFrames >= settings.requiredBlurryFrames && monitoring) {
            state = FocusState.BLUR_DETECTED
            return FocusAction.TRIGGER_AUTOFOCUS
        }
        return FocusAction.NONE
    }

    // ------------------------------------------------------------------ autofocus events

    /** Called by the caller right before the PTP command goes out. */
    fun onAutofocusStarted() {
        state = FocusState.AUTOFOCUS_TRIGGERED
        blurryFrames = 0
        // Frames measured before the AF run say nothing about the state afterwards.
        average.reset()
    }

    /**
     * Called once the camera has settled, whatever the outcome. Always enters the cooldown -
     * even a failed autofocus must not be retried immediately, otherwise a body that cannot
     * lock focus (low light, low contrast subject) would be hammered forever.
     */
    fun onAutofocusFinished(resultDescription: String, counted: Boolean = true) {
        if (counted) autofocusCount++
        lastAutofocusResult = resultDescription
        blurryFrames = 0
        cooldownStartedAt = clock()
        state = FocusState.COOLDOWN
    }

    /**
     * The camera answered OperationNotSupported. Monitoring is switched off and will not be
     * restarted, so the same unsupported command is never sent twice.
     */
    fun onAutofocusUnsupported(reason: String) {
        autofocusDisabledReason = reason
        monitoring = false
        blurryFrames = 0
        state = FocusState.LIVEVIEW
    }

    /**
     * Something other than the focus watchdog just drove the camera - a shutter release or
     * a movie start/stop. Those interrupt the LiveView stream exactly like an AF run does,
     * so the measurement window is thrown away and a cooldown is started. Without this the
     * black or frozen frames right after a capture would be measured as "blurry" and would
     * fire an autofocus the user never asked for.
     */
    fun onCameraInterruption() {
        average.reset()
        blurryFrames = 0
        cooldownStartedAt = clock()
        if (state == FocusState.ANALYZING ||
            state == FocusState.LIVEVIEW ||
            state == FocusState.BLUR_DETECTED ||
            state == FocusState.COOLDOWN
        ) {
            state = FocusState.COOLDOWN
        }
    }

    /** Advances COOLDOWN -> ANALYZING. Safe to call as often as you like. */
    fun tick(): FocusAction {
        advanceCooldown()
        return FocusAction.NONE
    }

    private fun advanceCooldown() {
        if (state != FocusState.COOLDOWN) return
        if (clock() - cooldownStartedAt >= settings.cooldownMs) {
            state = if (monitoring) FocusState.ANALYZING else FocusState.LIVEVIEW
            blurryFrames = 0
        }
    }

    private fun resetMeasurement() {
        average.reset()
        blurryFrames = 0
        rawScore = 0.0
        cooldownStartedAt = 0
    }

    fun resetCounters() {
        autofocusCount = 0
        lastAutofocusResult = null
    }

    // ------------------------------------------------------------------ snapshot

    fun snapshot(): FocusStatus {
        val remaining = if (state == FocusState.COOLDOWN) {
            (settings.cooldownMs - (clock() - cooldownStartedAt)).coerceAtLeast(0)
        } else {
            0
        }
        return FocusStatus(
            state = state,
            monitoring = monitoring,
            rawScore = rawScore,
            smoothedScore = average.average,
            threshold = settings.threshold,
            blurryFrames = blurryFrames,
            requiredBlurryFrames = settings.requiredBlurryFrames,
            samplesInWindow = average.sampleCount,
            windowSize = settings.movingAverageSize,
            windowWarm = average.isWarm,
            cooldownRemainingMs = remaining,
            cooldownTotalMs = settings.cooldownMs,
            autofocusCount = autofocusCount,
            lastAutofocusResult = lastAutofocusResult,
            autofocusDisabledReason = autofocusDisabledReason,
            errorMessage = errorMessage
        )
    }
}
