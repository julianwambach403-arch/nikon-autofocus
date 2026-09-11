package de.nikonautofocus.app.focus

/**
 * Everything the user can tune. Defaults match the values from the specification:
 * threshold 500, five consecutive blurry frames, three second cooldown.
 */
data class FocusSettings(
    /** score < threshold counts as blurry. */
    val threshold: Double = 500.0,

    /** How many consecutive blurry frames must be seen before focusing. */
    val requiredBlurryFrames: Int = 5,

    /** Window size N of the moving average applied to the raw score. */
    val movingAverageSize: Int = 5,

    /** No further autofocus is allowed for this long after a trigger. */
    val cooldownMs: Long = 3_000,

    /** Upper bound for the analysis rate. The USB stream is usually the real limit. */
    val analysisFps: Int = 10,

    /** Width the frame is scaled down to before the Laplacian runs. Rescales the score. */
    val analysisWidth: Int = 320,

    /** How long to wait for the camera to report "ready" again after an AF command. */
    val autofocusTimeoutMs: Long = 5_000,

    /** Destination of a shutter release: true = memory card, false = internal SDRAM. */
    val captureToCard: Boolean = true,

    /**
     * Whether the app sends Nikon_ChangeCameraMode(1) when LiveView starts.
     *
     * Taking PC control is what most Nikon bodies expect for tethered operation, but it is
     * also what locks the physical shutter and movie buttons on the camera. Switch this off
     * to leave the body controls usable - some bodies then refuse LiveView, which is why it
     * defaults to on.
     */
    val takeCameraControl: Boolean = true,

    /**
     * Pause the focus watchdog while an internal movie recording runs. The D3400 rejects
     * autofocus commands during recording, so leaving this on avoids pointless retries.
     */
    val pauseMonitoringWhileRecording: Boolean = true,

    /**
     * A user placed measuring field.
     *
     * When off, sharpness is measured over the whole frame - a busy background then counts
     * as much as the subject. When on, only the field is measured, so the watchdog reacts
     * to the subject rather than to the scene. Tapping the preview moves it, and the same
     * position is sent to the camera as its AF area when the body supports 0x9205.
     */
    val manualFieldEnabled: Boolean = false,

    /** Field centre as a fraction of the frame, 0..1. */
    val manualFieldX: Float = 0.5f,
    val manualFieldY: Float = 0.5f,

    /** Edge length as a fraction of the frame width. The field is square in pixels. */
    val showGrid: Boolean = true,
    val showHistogram: Boolean = true,
    val showExposureControls: Boolean = true
) {
    fun sanitized(): FocusSettings = copy(
        threshold = threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
        requiredBlurryFrames = requiredBlurryFrames.coerceIn(FRAMES_MIN, FRAMES_MAX),
        movingAverageSize = movingAverageSize.coerceIn(AVERAGE_MIN, AVERAGE_MAX),
        cooldownMs = cooldownMs.coerceIn(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS),
        analysisFps = analysisFps.coerceIn(FPS_MIN, FPS_MAX),
        analysisWidth = analysisWidth.coerceIn(WIDTH_MIN, WIDTH_MAX),
        autofocusTimeoutMs = autofocusTimeoutMs.coerceIn(AF_TIMEOUT_MIN_MS, AF_TIMEOUT_MAX_MS),
        manualFieldX = manualFieldX.coerceIn(0f, 1f),
        manualFieldY = manualFieldY.coerceIn(0f, 1f),
        manualFieldSize = manualFieldSize.coerceIn(FIELD_SIZE_MIN, FIELD_SIZE_MAX)
    )

    /** The measuring field, or null when the whole frame is measured. */
    val measuringField: MeasuringField?
        get() = if (manualFieldEnabled) {
            MeasuringField(manualFieldX, manualFieldY, manualFieldSize)
        } else {
            null
        }

    /** Interval between two analysed frames, derived from [analysisFps]. */
    val frameIntervalMs: Long get() = (1000L / analysisFps.coerceAtLeast(1))

    companion object {
        const val THRESHOLD_MIN = 10.0
        const val THRESHOLD_MAX = 5_000.0
        const val FRAMES_MIN = 1
        const val FRAMES_MAX = 30
        const val AVERAGE_MIN = 1
        const val AVERAGE_MAX = 30
        const val COOLDOWN_MIN_MS = 500L
        const val COOLDOWN_MAX_MS = 30_000L
        const val FPS_MIN = 1
        const val FPS_MAX = 30
        const val WIDTH_MIN = 160
        const val WIDTH_MAX = 640
        const val AF_TIMEOUT_MIN_MS = 1_000L
        const val AF_TIMEOUT_MAX_MS = 15_000L
        const val FIELD_SIZE_MIN = 0.08f
        const val FIELD_SIZE_MAX = 1.0f
    }
}

/**
 * A square region of the frame, expressed in fractions so it survives any change of
 * LiveView resolution.
 *
 * [size] is the edge length relative to the frame **width**; the height fraction is derived
 * from the frame aspect so the field stays square in pixels rather than stretching with the
 * image.
 */
data class MeasuringField(
    val centerX: Float,
    val centerY: Float,
    val size: Float
) {
    fun heightFraction(frameWidth: Int, frameHeight: Int): Float =
        if (frameHeight <= 0) size else size * frameWidth / frameHeight
}
