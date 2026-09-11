package de.nikonautofocus.app

import de.nikonautofocus.app.analysis.LaplacianVariance
import de.nikonautofocus.app.analysis.MovingAverage
import de.nikonautofocus.app.focus.FocusAction
import de.nikonautofocus.app.focus.FocusSettings
import de.nikonautofocus.app.focus.FocusState
import de.nikonautofocus.app.focus.FocusStateMachine
import de.nikonautofocus.app.focus.MeasuringField
import de.nikonautofocus.app.liveview.JpegExtractor
import de.nikonautofocus.app.liveview.LiveViewHeaderParser
import de.nikonautofocus.app.usb.MovieDiagnostics
import de.nikonautofocus.app.usb.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts that decide whether the camera is told to focus. These are the pieces
 * where a subtle mistake produces exactly the focus pumping the app exists to prevent.
 */
class FocusPipelineTest {

    private var now = 0L
    private val clock = { now }

    private fun machine(
        threshold: Double = 500.0,
        requiredBlurryFrames: Int = 5,
        movingAverageSize: Int = 5,
        cooldownMs: Long = 3_000
    ): FocusStateMachine {
        val machine = FocusStateMachine(
            FocusSettings(
                threshold = threshold,
                requiredBlurryFrames = requiredBlurryFrames,
                movingAverageSize = movingAverageSize,
                cooldownMs = cooldownMs
            ),
            clock
        )
        machine.onConnecting()
        machine.onLiveViewReady()
        machine.startMonitoring()
        return machine
    }

    // ------------------------------------------------------------------ state machine

    @Test
    fun `no trigger while the averaging window is still filling`() {
        val machine = machine(movingAverageSize = 5, requiredBlurryFrames = 1)
        // Four blurry frames: the window needs five samples before a decision is allowed.
        repeat(4) {
            assertEquals(FocusAction.NONE, machine.onFrameScore(10.0))
        }
        assertEquals(0, machine.snapshot().blurryFrames)
    }

    @Test
    fun `trigger only after the required number of consecutive blurry frames`() {
        val machine = machine(requiredBlurryFrames = 5, movingAverageSize = 3)

        // Warm the window up with blurry values.
        repeat(2) { assertEquals(FocusAction.NONE, machine.onFrameScore(100.0)) }

        // From here the window is warm; count up to but not including the trigger.
        repeat(4) { assertEquals(FocusAction.NONE, machine.onFrameScore(100.0)) }
        assertEquals(4, machine.snapshot().blurryFrames)

        assertEquals(FocusAction.TRIGGER_AUTOFOCUS, machine.onFrameScore(100.0))
        assertEquals(FocusState.BLUR_DETECTED, machine.snapshot().state)
    }

    @Test
    fun `a single sharp frame resets the blur counter`() {
        val machine = machine(requiredBlurryFrames = 5, movingAverageSize = 1)

        repeat(4) { machine.onFrameScore(100.0) }
        assertEquals(4, machine.snapshot().blurryFrames)

        machine.onFrameScore(9_000.0)
        assertEquals(0, machine.snapshot().blurryFrames)

        // The counter really starts over: four more blurry frames must not trigger.
        repeat(4) { assertEquals(FocusAction.NONE, machine.onFrameScore(100.0)) }
    }

    @Test
    fun `exactly one autofocus per blur episode - frames during AF and cooldown are inert`() {
        val machine = machine(requiredBlurryFrames = 2, movingAverageSize = 1, cooldownMs = 3_000)

        machine.onFrameScore(100.0)
        assertEquals(FocusAction.TRIGGER_AUTOFOCUS, machine.onFrameScore(100.0))

        machine.onAutofocusStarted()
        assertEquals(FocusState.AUTOFOCUS_TRIGGERED, machine.snapshot().state)

        // Frames arriving while the AF drive runs must never trigger again.
        repeat(20) { assertEquals(FocusAction.NONE, machine.onFrameScore(1.0)) }

        machine.onAutofocusFinished("Fokus gefunden")
        assertEquals(FocusState.COOLDOWN, machine.snapshot().state)

        // Nor during the cooldown, no matter how blurry the image is.
        repeat(50) { assertEquals(FocusAction.NONE, machine.onFrameScore(1.0)) }
        assertEquals(1, machine.snapshot().autofocusCount)
    }

    @Test
    fun `cooldown expires back into analysing and only then can trigger again`() {
        val machine = machine(requiredBlurryFrames = 2, movingAverageSize = 1, cooldownMs = 3_000)

        machine.onFrameScore(100.0)
        machine.onFrameScore(100.0)
        machine.onAutofocusStarted()
        machine.onAutofocusFinished("Fokus gefunden")

        now += 2_999
        machine.tick()
        assertEquals(FocusState.COOLDOWN, machine.snapshot().state)

        now += 2
        machine.tick()
        assertEquals(FocusState.ANALYZING, machine.snapshot().state)

        machine.onFrameScore(100.0)
        assertEquals(FocusAction.TRIGGER_AUTOFOCUS, machine.onFrameScore(100.0))
        machine.onAutofocusStarted()
        machine.onAutofocusFinished("Fokus gefunden")
        assertEquals(2, machine.snapshot().autofocusCount)
    }

    @Test
    fun `an unsupported autofocus command permanently stops monitoring`() {
        val machine = machine(requiredBlurryFrames = 1, movingAverageSize = 1)

        assertEquals(FocusAction.TRIGGER_AUTOFOCUS, machine.onFrameScore(10.0))
        machine.onAutofocusStarted()
        machine.onAutofocusUnsupported("nicht unterstuetzt")

        val snapshot = machine.snapshot()
        assertFalse(snapshot.monitoring)
        assertNotNull(snapshot.autofocusDisabledReason)

        // It must not be possible to arm it again, so the same command is never resent.
        assertFalse(machine.startMonitoring())
        repeat(20) { assertEquals(FocusAction.NONE, machine.onFrameScore(1.0)) }
    }

    @Test
    fun `a shutter release does not make the watchdog fire on the frames it interrupts`() {
        val machine = machine(requiredBlurryFrames = 2, movingAverageSize = 1, cooldownMs = 3_000)

        // Watchdog is armed and the image is sharp.
        machine.onFrameScore(2_000.0)
        machine.onFrameScore(2_000.0)

        // The user presses the shutter. LiveView goes black for a moment.
        machine.onCameraInterruption()
        assertEquals(FocusState.COOLDOWN, machine.snapshot().state)

        // Those black frames measure as extremely blurry but must not trigger anything.
        repeat(30) { assertEquals(FocusAction.NONE, machine.onFrameScore(0.5)) }
        assertEquals(0, machine.snapshot().autofocusCount)

        // Once the cooldown is over the watchdog works normally again.
        now += 3_001
        machine.tick()
        assertEquals(FocusState.ANALYZING, machine.snapshot().state)
        machine.onFrameScore(100.0)
        assertEquals(FocusAction.TRIGGER_AUTOFOCUS, machine.onFrameScore(100.0))
    }

    @Test
    fun `an interruption throws away the measurement window`() {
        val machine = machine(requiredBlurryFrames = 1, movingAverageSize = 5, cooldownMs = 500)
        repeat(5) { machine.onFrameScore(2_000.0) }
        assertTrue(machine.snapshot().windowWarm)

        machine.onCameraInterruption()
        assertFalse(machine.snapshot().windowWarm)
        assertEquals(0, machine.snapshot().samplesInWindow)
    }

    @Test
    fun `sharp images never trigger`() {
        val machine = machine(threshold = 500.0, requiredBlurryFrames = 3, movingAverageSize = 3)
        repeat(100) { assertEquals(FocusAction.NONE, machine.onFrameScore(1_500.0)) }
        assertEquals(0, machine.snapshot().autofocusCount)
    }

    @Test
    fun `smoothing absorbs a single outlier below the threshold`() {
        val machine = machine(threshold = 500.0, requiredBlurryFrames = 1, movingAverageSize = 5)
        // Four sharp frames plus one very blurry outlier: the average stays above 500.
        repeat(4) { machine.onFrameScore(900.0) }
        assertEquals(FocusAction.NONE, machine.onFrameScore(100.0))
        assertEquals(0, machine.snapshot().blurryFrames)
    }

    // ------------------------------------------------------------------ moving average

    @Test
    fun `moving average reports warm only once the window is full`() {
        val average = MovingAverage(3)
        average.add(1.0)
        average.add(2.0)
        assertFalse(average.isWarm)
        average.add(3.0)
        assertTrue(average.isWarm)
        assertEquals(2.0, average.average, 1e-9)
        average.add(6.0)
        assertEquals(11.0 / 3.0, average.average, 1e-9)
    }

    // ------------------------------------------------------------------ sharpness

    @Test
    fun `laplacian variance is zero on a flat image and large on a hard edge`() {
        val width = 32
        val height = 32

        val flat = IntArray(width * height) { 128 }
        assertEquals(0.0, LaplacianVariance.compute(flat, width, height), 1e-9)

        val edges = IntArray(width * height) { index ->
            if ((index % width) % 2 == 0) 0 else 255
        }
        assertTrue(LaplacianVariance.compute(edges, width, height) > 10_000.0)
    }

    @Test
    fun `a blurred gradient scores far lower than a sharp pattern`() {
        val width = 64
        val height = 64
        val sharp = IntArray(width * height) { index ->
            if (((index % width) / 4) % 2 == 0) 20 else 235
        }
        val blurred = IntArray(width * height) { index ->
            // Smooth ramp: same overall contrast, but no crisp edges.
            ((index % width) * 255) / width
        }
        val sharpScore = LaplacianVariance.compute(sharp, width, height)
        val blurredScore = LaplacianVariance.compute(blurred, width, height)
        assertTrue("sharp=$sharpScore blurred=$blurredScore", sharpScore > blurredScore * 10)
    }

    // ------------------------------------------------------------------ jpeg extraction

    @Test
    fun `jpeg is found behind a nikon liveview header`() {
        val headerSize = 384
        val jpeg = ByteArray(600).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
            it[598] = 0xFF.toByte()
            it[599] = 0xD9.toByte()
        }
        val payload = ByteArray(headerSize + jpeg.size)
        // Plausible header noise, deliberately without a 0xFFD8 pair.
        for (i in 0 until headerSize) payload[i] = (i % 200).toByte()
        System.arraycopy(jpeg, 0, payload, headerSize, jpeg.size)

        val range = JpegExtractor.find(payload, payload.size)
        assertNotNull(range)
        assertEquals(headerSize, range!!.start)
        assertEquals(payload.size, range.end)
    }

    @Test
    fun `leading offset field is honoured when it points at the SOI marker`() {
        val offset = 128
        val payload = ByteArray(offset + 400)
        payload[0] = offset.toByte()
        payload[1] = 0
        payload[2] = 0
        payload[3] = 0
        // Decoy marker inside the header area, before the real image.
        payload[40] = 0xFF.toByte()
        payload[41] = 0xD8.toByte()
        payload[offset] = 0xFF.toByte()
        payload[offset + 1] = 0xD8.toByte()
        payload[payload.size - 2] = 0xFF.toByte()
        payload[payload.size - 1] = 0xD9.toByte()

        val range = JpegExtractor.find(payload, payload.size)
        assertNotNull(range)
        assertEquals(offset, range!!.start)
    }

    // ------------------------------------------------------------------ measuring field

    @Test
    fun `the measuring field only exists while it is switched on`() {
        val off = FocusSettings(manualFieldEnabled = false)
        assertNull(off.measuringField)

        val on = FocusSettings(manualFieldEnabled = true, manualFieldX = 0.25f)
        val field = on.measuringField
        assertNotNull(field)
        assertEquals(0.25f, field!!.centerX, 1e-6f)
    }

    @Test
    fun `the field stays square in pixels on a non square frame`() {
        val field = MeasuringField(centerX = 0.5f, centerY = 0.5f, size = 0.2f)
        // 640x426 frame: 20% of the width is 128 px, so the height fraction has to be
        // larger than 0.2 for the field to still be 128 px tall.
        val heightFraction = field.heightFraction(640, 426)
        assertEquals(128f, field.size * 640, 0.5f)
        assertEquals(128f, heightFraction * 426, 0.5f)
    }

    @Test
    fun `field position and size are clamped to the frame`() {
        val settings = FocusSettings(
            manualFieldEnabled = true,
            manualFieldX = 4f,
            manualFieldY = -2f,
            manualFieldSize = 9f
        ).sanitized()
        assertEquals(1f, settings.manualFieldX, 1e-6f)
        assertEquals(0f, settings.manualFieldY, 1e-6f)
        assertEquals(FocusSettings.FIELD_SIZE_MAX, settings.manualFieldSize, 1e-6f)
    }

    // ------------------------------------------------------------------ liveview header

    /** Builds a 384 byte Nikon LiveView header with big endian fields. */
    private fun buildHeader(
        liveViewWidth: Int = 640,
        liveViewHeight: Int = 426,
        imageWidth: Int = 1000,
        imageHeight: Int = 666,
        focusFrameWidth: Int = 100,
        focusFrameHeight: Int = 100,
        focusX: Int = 500,
        focusY: Int = 333,
        focusByte: Int = 0,
        rotationByte: Int = 0,
        recordingByte: Int = 0
    ): ByteArray {
        val header = ByteArray(384)
        fun put(offset: Int, value: Int) {
            header[offset] = ((value shr 8) and 0xFF).toByte()
            header[offset + 1] = (value and 0xFF).toByte()
        }
        put(0, liveViewWidth)
        put(2, liveViewHeight)
        put(4, imageWidth)
        put(6, imageHeight)
        put(16, focusFrameWidth)
        put(18, focusFrameHeight)
        put(20, focusX)
        put(22, focusY)
        header[29] = rotationByte.toByte()
        header[40] = focusByte.toByte()
        header[60] = recordingByte.toByte()
        return header
    }

    @Test
    fun `af frame is read from the liveview header and converted to fractions`() {
        val header = buildHeader()
        val parsed = LiveViewHeaderParser.parse(header, 384, 640, 426)

        assertNotNull(parsed)
        parsed!!
        assertEquals(1000, parsed.imageWidth)
        assertEquals(666, parsed.imageHeight)
        assertEquals(0.5f, parsed.focusCenterXFraction, 1e-4f)
        assertEquals(0.5f, parsed.focusCenterYFraction, 1e-3f)
        assertEquals(0.1f, parsed.focusWidthFraction, 1e-4f)
        assertTrue(parsed.focused)
    }

    @Test
    fun `focus byte of 1 means not focused`() {
        val parsed = LiveViewHeaderParser.parse(buildHeader(focusByte = 1), 384, 640, 426)
        assertNotNull(parsed)
        assertFalse(parsed!!.focused)
    }

    @Test
    fun `a header that does not describe the decoded jpeg is rejected`() {
        // This is the guard that stops a wrong header layout from drawing a bogus AF box:
        // the width/height in the header must match the JPEG that follows it.
        val header = buildHeader(liveViewWidth = 640, liveViewHeight = 426)
        assertNull(LiveViewHeaderParser.parse(header, 384, 1024, 680))
    }

    @Test
    fun `short headers and implausible frames are rejected`() {
        assertNull(LiveViewHeaderParser.parse(buildHeader(), 8, 640, 426))

        // AF frame larger than the image it should sit in.
        assertNull(
            LiveViewHeaderParser.parse(
                buildHeader(focusFrameWidth = 5000), 384, 640, 426
            )
        )
        // AF centre outside the image.
        assertNull(
            LiveViewHeaderParser.parse(buildHeader(focusX = 4000), 384, 640, 426)
        )
        // Zero sized AF frame.
        assertNull(
            LiveViewHeaderParser.parse(buildHeader(focusFrameHeight = 0), 384, 640, 426)
        )
    }

    @Test
    fun `payload without any jpeg is rejected`() {
        val payload = ByteArray(2048) { 0x41 }
        assertNull(JpegExtractor.find(payload, payload.size))
    }

    // ------------------------------------------------------------------ movie start diagnostics

    @Test
    fun `movie prohibit bit 13 is photo live view not an enabled selector`() {
        val text = PtpConstants.describeMovieProhibitCondition(1L shl 13)
        assertTrue(text.contains("Foto statt Video"))
        assertEquals("Foto-LiveView (0)", PtpConstants.liveViewSelectorName(0))
        assertEquals("Video-LiveView (1)", PtpConstants.liveViewSelectorName(1))
    }

    @Test
    fun `movie diagnostics name a still liveview selector when nothing else is set`() {
        val diagnostics = movieDiagnostics(
            liveViewStatus = 1,
            recordingMedia = PtpConstants.RECORDING_MEDIA_CARD,
            liveViewSelector = PtpConstants.LIVE_VIEW_SELECTOR_STILL
        )
        val cause = diagnostics.likelyCause()
        assertNotNull(cause)
        assertTrue(cause!!.contains("Foto"))
        assertTrue(diagnostics.details().contains("Foto-LiveView"))
    }

    @Test
    fun `movie diagnostics name sdram as a reason 0x920A would refuse`() {
        val diagnostics = movieDiagnostics(
            recordingMedia = PtpConstants.RECORDING_MEDIA_SDRAM,
            liveViewSelector = PtpConstants.LIVE_VIEW_SELECTOR_MOVIE
        )
        val cause = diagnostics.likelyCause()
        assertNotNull(cause)
        assertTrue(cause!!.contains("SDRAM"))
    }

    @Test
    fun `movie diagnostics prefer the prohibit bitmask over a guess`() {
        val diagnostics = movieDiagnostics(
            movieProhibit = 1L shl 0,
            liveViewSelector = PtpConstants.LIVE_VIEW_SELECTOR_STILL,
            exposureProgram = 0x8010
        )
        val cause = diagnostics.likelyCause()
        assertNotNull(cause)
        assertTrue(cause!!.contains("keine Speicherkarte"))
    }

    private fun movieDiagnostics(
        liveViewStatus: Long? = 1,
        movieProhibit: Long? = null,
        liveViewProhibit: Long? = null,
        exposureProgram: Long? = 0x0001,
        recordingMedia: Long? = PtpConstants.RECORDING_MEDIA_CARD,
        liveViewSelector: Long? = PtpConstants.LIVE_VIEW_SELECTOR_MOVIE,
        lastResponseCode: Int = PtpConstants.RC_NIKON_INVALID_STATUS
    ) = MovieDiagnostics(
        liveViewStatus = liveViewStatus,
        movieProhibit = movieProhibit,
        liveViewProhibit = liveViewProhibit,
        exposureProgram = exposureProgram,
        recordingMedia = recordingMedia,
        liveViewSelector = liveViewSelector,
        liveViewMode = null,
        movieCaptureMode = null,
        applicationMode = null,
        hasStartMovie = true,
        hasEndMovie = true,
        hasOpenCapture = false,
        lastResponseCode = lastResponseCode
    )
}
