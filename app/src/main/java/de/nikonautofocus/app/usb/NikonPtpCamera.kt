package de.nikonautofocus.app.usb

import android.util.Log

/**
 * How the app obtains preview frames from the body.
 *
 * [LIVE_VIEW] is the real, continuous stream (Nikon_StartLiveView + Nikon_GetLiveViewImg).
 * [PREVIEW_IMAGE] is the fallback for bodies that do not expose LiveView over PTP: a single
 * preview JPEG is pulled repeatedly. It is slower and lower frame rate, but the sharpness
 * pipeline works unchanged.
 */
enum class FrameSource { LIVE_VIEW, PREVIEW_IMAGE, NONE }

/** Where a still capture is stored. */
enum class CaptureTarget { CARD, SDRAM }

/** What the connected body actually implements, derived from DeviceInfo.OperationsSupported. */
data class CameraCapabilities(
    val startLiveView: Boolean,
    val getLiveViewImage: Boolean,
    val endLiveView: Boolean,
    val getPreviewImage: Boolean,
    val afDrive: Boolean,
    val afDriveCancel: Boolean,
    val deviceReady: Boolean,
    val changeCameraMode: Boolean,
    val changeAfArea: Boolean,
    val mfDrive: Boolean,
    val captureRecInMedia: Boolean,
    val captureRecInSdram: Boolean,
    val afCaptureSdram: Boolean,
    val standardCapture: Boolean,
    val startMovie: Boolean,
    val endMovie: Boolean,
    val openCapture: Boolean,
    val changeApplicationMode: Boolean,
    val getEvent: Boolean,
    val hasLiveViewStatusProp: Boolean,
    val hasProhibitConditionProp: Boolean,
    val hasMovieProhibitProp: Boolean,
    val hasRecordingMediaProp: Boolean,
    val hasApplicationModeProp: Boolean,
    val hasAfAreaModeProp: Boolean,
    val hasAfServoModeProp: Boolean
) {
    val frameSource: FrameSource
        get() = when {
            startLiveView && getLiveViewImage -> FrameSource.LIVE_VIEW
            getPreviewImage -> FrameSource.PREVIEW_IMAGE
            else -> FrameSource.NONE
        }

    /** True when at least one way of releasing the shutter over PTP exists. */
    val canCaptureStill: Boolean
        get() = captureRecInMedia || captureRecInSdram || afCaptureSdram || standardCapture

    /** Internal movie recording needs both the start and the stop opcode. */
    val canRecordMovie: Boolean get() = (startMovie && endMovie) || openCapture

    companion object {
        fun from(info: PtpDeviceInfo): CameraCapabilities = CameraCapabilities(
            startLiveView = info.supports(PtpConstants.OC_NIKON_START_LIVE_VIEW),
            getLiveViewImage = info.supports(PtpConstants.OC_NIKON_GET_LIVE_VIEW_IMG),
            endLiveView = info.supports(PtpConstants.OC_NIKON_END_LIVE_VIEW),
            getPreviewImage = info.supports(PtpConstants.OC_NIKON_GET_PREVIEW_IMG),
            afDrive = info.supports(PtpConstants.OC_NIKON_AF_DRIVE),
            afDriveCancel = info.supports(PtpConstants.OC_NIKON_AF_DRIVE_CANCEL),
            deviceReady = info.supports(PtpConstants.OC_NIKON_DEVICE_READY),
            changeCameraMode = info.supports(PtpConstants.OC_NIKON_CHANGE_CAMERA_MODE),
            changeAfArea = info.supports(PtpConstants.OC_NIKON_CHANGE_AF_AREA),
            mfDrive = info.supports(PtpConstants.OC_NIKON_MF_DRIVE),
            captureRecInMedia =
            info.supports(PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA),
            captureRecInSdram =
            info.supports(PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM),
            afCaptureSdram = info.supports(PtpConstants.OC_NIKON_AF_CAPTURE_SDRAM),
            standardCapture = info.supports(PtpConstants.OC_INITIATE_CAPTURE),
            startMovie = info.supports(PtpConstants.OC_NIKON_START_MOVIE_REC_IN_CARD),
            endMovie = info.supports(PtpConstants.OC_NIKON_END_MOVIE_REC),
            openCapture = info.supports(PtpConstants.OC_INITIATE_OPEN_CAPTURE) &&
                info.supports(PtpConstants.OC_TERMINATE_OPEN_CAPTURE),
            changeApplicationMode =
            info.supports(PtpConstants.OC_NIKON_CHANGE_APPLICATION_MODE),
            getEvent = info.supports(PtpConstants.OC_NIKON_GET_EVENT),
            hasLiveViewStatusProp = info.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS),
            hasProhibitConditionProp =
            info.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_PROHIBIT_CONDITION),
            hasMovieProhibitProp =
            info.hasProperty(PtpConstants.DPC_NIKON_MOV_REC_PROHIBIT_CONDITION),
            hasRecordingMediaProp = info.hasProperty(PtpConstants.DPC_NIKON_RECORDING_MEDIA),
            hasApplicationModeProp = info.hasProperty(PtpConstants.DPC_NIKON_APPLICATION_MODE),
            hasAfAreaModeProp = info.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA),
            hasAfServoModeProp = info.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS)
        )
    }
}

/** Outcome of one autofocus attempt. */
sealed class AutofocusResult {
    /** Camera reported focus lock. */
    object Focused : AutofocusResult()

    /** The AF drive ran but could not lock (Nikon_OutOfFocus). Still counts as "we tried". */
    object OutOfFocus : AutofocusResult()

    /** Camera was busy - typically an internal movie recording or a card write. */
    class Busy(val detail: String) : AutofocusResult()

    /** The body does not implement Nikon_AfDrive. The app must stop trying. */
    object Unsupported : AutofocusResult()

    /** Any other protocol answer. */
    class Failed(val error: CameraError) : AutofocusResult()
}

/** Outcome of a still capture attempt. */
sealed class CaptureResult {
    /** The shutter was released. [operation] says which opcode did it. */
    class Released(val operation: Int, val target: CaptureTarget) : CaptureResult()

    /** Camera was busy - movie recording, buffer flush, mirror still moving. */
    class Busy(val detail: String) : CaptureResult()

    /** No usable capture opcode, or the body rejected all of them as unsupported. */
    object Unsupported : CaptureResult()

    class Failed(val error: CameraError) : CaptureResult()
}

/**
 * Everything the camera can tell us about why a movie recording will or will not start.
 *
 * Nikon answers a refused movie start with the single catch-all code InvalidStatus, which
 * on its own says nothing actionable. So when that happens the app reads every related
 * property the body exposes and reports the actual values instead of guessing.
 *
 * A null field means the camera does not expose that property at all - which is itself
 * information, so it is reported as "n/v" rather than silently omitted.
 */
data class MovieDiagnostics(
    val liveViewStatus: Long?,
    val movieProhibit: Long?,
    val liveViewProhibit: Long?,
    val exposureProgram: Long?,
    val recordingMedia: Long?,
    val liveViewSelector: Long?,
    val liveViewMode: Long?,
    val movieCaptureMode: Long?,
    val applicationMode: Long?,
    val hasStartMovie: Boolean,
    val hasEndMovie: Boolean,
    val hasOpenCapture: Boolean,
    val lastResponseCode: Int
) {
    /** The single most likely cause, as one sentence, or null when nothing stands out. */
    fun likelyCause(): String? = when {
        movieProhibit != null && movieProhibit != 0L ->
            PtpConstants.describeMovieProhibitCondition(movieProhibit)

        liveViewProhibit != null && liveViewProhibit != 0L ->
            PtpConstants.describeProhibitCondition(liveViewProhibit)

        liveViewStatus != null && liveViewStatus == 0L ->
            "LiveView ist laut Kamera aus (0xD1A2 = 0)"

        recordingMedia != null && recordingMedia != PtpConstants.RECORDING_MEDIA_CARD ->
            "RecordingMedia steht auf " + PtpConstants.recordingMediaName(recordingMedia) +
                ". StartMovieRecInCard (0x920A) speichert wie der rote Knopf auf die Karte."

        liveViewSelector != null && liveViewSelector == PtpConstants.LIVE_VIEW_SELECTOR_STILL ->
            "LiveView steht auf Foto (0xD1A6 = 0). Nikon startet Video nur aus dem Video-LiveView."

        exposureProgram != null && !PtpConstants.isPsamMode(exposureProgram) ->
            "Moduswahlrad steht auf " + PtpConstants.exposureProgramName(exposureProgram) +
                ". Nikon erlaubt Fernsteuerung in der Regel nur in P, S, A oder M."

        else -> null
    }

    fun details(): String = buildString {
        appendLine("Antwort auf Videostart: " + PtpConstants.responseName(lastResponseCode))
        appendLine("StartMovieRecInCard 0x920A: " + yesNo(hasStartMovie))
        appendLine("EndMovieRec         0x920B: " + yesNo(hasEndMovie))
        appendLine("InitiateOpenCapture 0x101C: " + yesNo(hasOpenCapture))
        appendLine(
            "LiveViewStatus       0xD1A2: " +
                (liveViewStatus?.let { if (it == 0L) "aus (0)" else "an ($it)" } ?: "n/v")
        )
        appendLine(
            "MovRecProhibit       0xD0A4: " +
                (movieProhibit?.let { PtpConstants.describeMovieProhibitCondition(it) } ?: "n/v")
        )
        appendLine(
            "LiveViewProhibit     0xD1A4: " +
                (liveViewProhibit?.let { PtpConstants.describeProhibitCondition(it) } ?: "n/v")
        )
        appendLine(
            "ExposureProgram      0x500E: " +
                (exposureProgram?.let { PtpConstants.exposureProgramName(it) } ?: "n/v")
        )
        appendLine(
            "RecordingMedia       0xD10B: " +
                (recordingMedia?.let { PtpConstants.recordingMediaName(it) } ?: "n/v")
        )
        appendLine(
            "LiveViewSelector     0xD1A6: " +
                (liveViewSelector?.let { PtpConstants.liveViewSelectorName(it) } ?: "n/v")
        )
        appendLine("LiveViewMode         0xD1A0: " + rawOrNa(liveViewMode))
        appendLine("MovieCaptureMode     0xD304: " + rawOrNa(movieCaptureMode))
        appendLine("ApplicationMode      0xD1F0: " + rawOrNa(applicationMode))
    }

    private fun rawOrNa(value: Long?): String =
        value?.let { "$it (" + PtpConstants.hex32(it) + ")" } ?: "n/v"

    private fun yesNo(value: Boolean) = if (value) "ja" else "NEIN"
}

/** Outcome of a movie recording start/stop attempt. */
sealed class MovieResult {
    object Started : MovieResult()
    object Stopped : MovieResult()
    class Prohibited(val condition: Long) : MovieResult()
    class Busy(val detail: String) : MovieResult()
    object Unsupported : MovieResult()

    /** The body listed the opcode but refused the start. Carries the collected evidence. */
    class Rejected(val diagnostics: MovieDiagnostics) : MovieResult()

    class Failed(val error: CameraError) : MovieResult()
}

/** One selectable value of an autofocus mode property. */
data class AfModeOption(val value: Long, val label: String)

/** Current state of one autofocus mode property, as reported by the camera. */
data class AfModeState(
    val propertyCode: Int,
    val current: Long,
    val options: List<AfModeOption>,
    val writable: Boolean
) {
    val currentLabel: String
        get() = options.firstOrNull { it.value == current }?.label ?: "unbekannt ($current)"
}

/** One raw frame payload as delivered by the camera, before JPEG extraction. */
class RawFrame(val data: ByteArray, val length: Int)

/**
 * Nikon specific camera object. Owns a [PtpSession] and exposes exactly the operations
 * this app needs. All calls must be made from the single PTP thread.
 */
class NikonPtpCamera(
    private val session: PtpSession,
    val deviceInfo: PtpDeviceInfo
) {

    val capabilities: CameraCapabilities = CameraCapabilities.from(deviceInfo)

    /** Latched once the body answers OperationNotSupported for the AF drive. */
    var autofocusUnsupported: Boolean = false
        private set

    /** Latched once every known capture opcode has been rejected. */
    var captureUnsupported: Boolean = false
        private set

    var liveViewRunning: Boolean = false
        private set

    /** True between a successful movie start and the matching stop. */
    var movieRecording: Boolean = false
        private set

    /** Which opcode started the running recording, so the right one stops it. */
    private var movieUsesOpenCapture = false
    private var openCaptureTransactionId = 0

    /**
     * Whether the app has taken PC control of the body (Nikon_ChangeCameraMode 1).
     *
     * While this is set - and in tethered LiveView generally - Nikon locks the physical
     * controls of the camera, which is why the shutter and movie buttons on the body do
     * nothing. [releaseToCamera] hands control back.
     */
    var controlTaken = false
        private set

    /** Set false to never send ChangeCameraMode, leaving the body controls alone. */
    var takeControlOnLiveView: Boolean = true

    /**
     * Some bodies only accept a single parameter for InitiateCaptureRecInMedia. Latched
     * after the first ParameterNotSupported so the two parameter form is not retried.
     */
    private var captureNeedsSingleParameter = false

    /** Reused across frames so a 10-20 fps stream does not churn the heap. */
    private var frameBuffer = ByteArray(512 * 1024)

    // ---------------------------------------------------------------- readiness

    /**
     * One shot readiness probe. Returns the raw response code.
     * Bodies without Nikon_DeviceReady are optimistically treated as ready.
     */
    fun probeReady(timeoutMs: Int = 1500): Int {
        if (!capabilities.deviceReady) return PtpConstants.RC_OK
        return try {
            session.transact(
                PtpConstants.OC_NIKON_DEVICE_READY,
                timeoutMs = timeoutMs
            ).responseCode
        } catch (e: PtpTransportException) {
            throw CameraException(CameraError.NotResponding)
        }
    }

    fun isBusy(): Boolean = isBusyCode(probeReady())

    /**
     * Polls Nikon_DeviceReady until the camera stops reporting a busy state.
     * Mirrors libgphoto2 nikon_wait_busy(). Returns the final response code.
     */
    fun waitUntilReady(intervalMs: Long = 20, timeoutMs: Long = 5000): Int {
        if (!capabilities.deviceReady) return PtpConstants.RC_OK
        val deadline = System.currentTimeMillis() + timeoutMs
        var code: Int
        while (true) {
            code = probeReady()
            if (code == PtpConstants.RC_NIKON_SILENT_RELEASE_BUSY) return PtpConstants.RC_OK
            if (!isBusyCode(code)) return code
            if (System.currentTimeMillis() >= deadline) return code
            Thread.sleep(intervalMs)
        }
    }

    private fun isBusyCode(code: Int): Boolean =
        code == PtpConstants.RC_DEVICE_BUSY ||
            code == PtpConstants.RC_NIKON_BULB_RELEASE_BUSY

    // ---------------------------------------------------------------- live view

    fun readLiveViewStatus(): Int? {
        if (!capabilities.hasLiveViewStatusProp) return null
        return runCatching {
            session.getDevicePropValue(
                PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS,
                PtpConstants.DTC_UINT8
            ).toInt()
        }.getOrNull()
    }

    fun readProhibitCondition(): Long? {
        if (!capabilities.hasProhibitConditionProp) return null
        return runCatching {
            session.getDevicePropValue(
                PtpConstants.DPC_NIKON_LIVE_VIEW_PROHIBIT_CONDITION,
                PtpConstants.DTC_UINT32
            )
        }.getOrNull()
    }

    /**
     * Brings the camera into a state where preview frames can be pulled.
     * Follows the sequence libgphoto2 uses for Nikon bodies.
     */
    fun startLiveView() {
        when (capabilities.frameSource) {
            FrameSource.NONE -> throw CameraException(
                CameraError.LiveViewUnsupported(
                    "Die Kamera meldet weder Nikon_StartLiveView (0x9201) noch " +
                        "Nikon_GetPreviewImg (0x9200) in ihrer Operationsliste."
                )
            )

            FrameSource.PREVIEW_IMAGE -> {
                Log.w(TAG, "Body has no LiveView; falling back to GetPreviewImg polling")
                liveViewRunning = true
                return
            }

            FrameSource.LIVE_VIEW -> Unit
        }

        takeControlIfPossible()

        val alreadyOn = readLiveViewStatus()
        if (alreadyOn != null && alreadyOn != 0) {
            liveViewRunning = true
            return
        }

        // Nikon wants the recording media set to SDRAM before LiveView on many bodies.
        if (capabilities.hasRecordingMediaProp) {
            runCatching {
                session.setDevicePropValue(
                    PtpConstants.DPC_NIKON_RECORDING_MEDIA, 1, PtpConstants.DTC_UINT8
                )
            }
        }

        readProhibitCondition()?.let { condition ->
            if (condition != 0L) throw CameraException(CameraError.LiveViewProhibited(condition))
        }

        val response = session.transact(
            PtpConstants.OC_NIKON_START_LIVE_VIEW,
            timeoutMs = START_LIVE_VIEW_TIMEOUT
        )
        if (!response.isOk) {
            if (response.responseCode == PtpConstants.RC_OPERATION_NOT_SUPPORTED) {
                throw CameraException(
                    CameraError.LiveViewUnsupported(
                        "Nikon_StartLiveView (0x9201) wurde mit OperationNotSupported " +
                            "beantwortet."
                    )
                )
            }
            readProhibitCondition()?.let { condition ->
                if (condition != 0L) {
                    throw CameraException(CameraError.LiveViewProhibited(condition))
                }
            }
            throw CameraException(
                CameraError.Protocol(PtpConstants.OC_NIKON_START_LIVE_VIEW, response.responseCode)
            )
        }

        waitUntilReady(intervalMs = 20, timeoutMs = 2000)
        liveViewRunning = true
    }

    fun endLiveView() {
        if (!liveViewRunning) return
        liveViewRunning = false
        if (capabilities.endLiveView) {
            runCatching { session.transact(PtpConstants.OC_NIKON_END_LIVE_VIEW) }
        }
        releaseControlIfTaken()
    }

    /**
     * Pulls the next preview frame. The returned [RawFrame] wraps a pooled buffer that is
     * overwritten by the following call, so consumers must decode before asking for more.
     *
     * Returns null when the camera has nothing to deliver right now (busy, momentary
     * AccessDenied while it is focusing). That is a normal condition, not an error.
     */
    fun fetchFrame(timeoutMs: Int = FRAME_TIMEOUT): RawFrame? {
        val operation = when (capabilities.frameSource) {
            FrameSource.LIVE_VIEW -> PtpConstants.OC_NIKON_GET_LIVE_VIEW_IMG
            FrameSource.PREVIEW_IMAGE -> PtpConstants.OC_NIKON_GET_PREVIEW_IMG
            FrameSource.NONE -> return null
        }

        val response = try {
            session.transact(operation, timeoutMs = timeoutMs, reuseDataBuffer = frameBuffer)
        } catch (e: PtpTransportException) {
            throw CameraException(CameraError.NotResponding)
        }

        when (response.responseCode) {
            PtpConstants.RC_OK -> Unit
            // All of these mean "not now, try again": the body is mid-AF, mid-mirror or
            // the LiveView buffer is not filled yet. Never treat them as fatal.
            PtpConstants.RC_DEVICE_BUSY,
            PtpConstants.RC_ACCESS_DENIED,
            PtpConstants.RC_NIKON_INVALID_STATUS -> return null

            PtpConstants.RC_NIKON_NOT_LIVE_VIEW -> {
                liveViewRunning = false
                throw CameraException(
                    CameraError.LiveViewUnsupported(
                        "Die Kamera hat LiveView beendet (Nikon_NotLiveView). " +
                            "Haeufige Ursache: Sucher-/Displayschalter oder Standby."
                    )
                )
            }

            else -> throw CameraException(
                CameraError.Protocol(operation, response.responseCode)
            )
        }

        val data = response.data ?: return null
        // The session may have allocated a bigger buffer than our pool; adopt it.
        if (data !== frameBuffer && data.size > frameBuffer.size) frameBuffer = data
        if (response.dataLength <= 0) return null
        return RawFrame(data, response.dataLength)
    }

    // ---------------------------------------------------------------- autofocus

    /**
     * Sends exactly one autofocus drive command and waits for the camera to settle.
     *
     * The call is intentionally synchronous: it occupies the single PTP thread for its whole
     * duration, which guarantees that no LiveView poll and no second AF command can overlap
     * with it. That is the core of the anti focus-pumping design.
     */
    fun triggerAutofocus(timeoutMs: Long = 5000): AutofocusResult {
        if (autofocusUnsupported) return AutofocusResult.Unsupported
        if (!capabilities.afDrive) {
            autofocusUnsupported = true
            return AutofocusResult.Unsupported
        }

        // Never fire into a busy body: during an internal SD movie recording the D3400
        // answers DeviceBusy and would otherwise be hammered with retries.
        val readyBefore = try {
            probeReady()
        } catch (e: CameraException) {
            return AutofocusResult.Failed(e.error)
        }
        if (isBusyCode(readyBefore)) {
            return AutofocusResult.Busy(
                "DeviceReady meldet ${PtpConstants.responseName(readyBefore)} " +
                    "(z. B. laufende interne Videoaufnahme oder Kartenzugriff)"
            )
        }

        val response = try {
            session.transact(
                PtpConstants.OC_NIKON_AF_DRIVE,
                timeoutMs = AF_COMMAND_TIMEOUT
            )
        } catch (e: PtpTransportException) {
            return AutofocusResult.Failed(CameraError.NotResponding)
        }

        when (response.responseCode) {
            PtpConstants.RC_OK -> Unit

            PtpConstants.RC_NIKON_OUT_OF_FOCUS -> {
                waitUntilReady(intervalMs = 10, timeoutMs = timeoutMs)
                return AutofocusResult.OutOfFocus
            }

            PtpConstants.RC_OPERATION_NOT_SUPPORTED -> {
                autofocusUnsupported = true
                return AutofocusResult.Unsupported
            }

            PtpConstants.RC_DEVICE_BUSY,
            PtpConstants.RC_NIKON_BULB_RELEASE_BUSY -> return AutofocusResult.Busy(
                PtpConstants.responseName(response.responseCode)
            )

            PtpConstants.RC_NIKON_NOT_LIVE_VIEW -> return AutofocusResult.Failed(
                CameraError.CameraBusy("Kamera ist nicht (mehr) im LiveView-Modus")
            )

            PtpConstants.RC_NIKON_INVALID_STATUS -> return AutofocusResult.Busy(
                "Nikon_InvalidStatus - Kamera im falschen Zustand (z. B. Fokusschalter auf M)"
            )

            else -> return AutofocusResult.Failed(
                CameraError.Protocol(PtpConstants.OC_NIKON_AF_DRIVE, response.responseCode)
            )
        }

        // AF drive accepted. Wait until the body reports idle again - this is exactly the
        // window in which LiveView freezes, and holding the thread here keeps the analyser
        // from consuming stale frames.
        return when (val settled = waitUntilReady(intervalMs = 10, timeoutMs = timeoutMs)) {
            PtpConstants.RC_OK -> AutofocusResult.Focused
            PtpConstants.RC_NIKON_OUT_OF_FOCUS -> AutofocusResult.OutOfFocus
            PtpConstants.RC_DEVICE_BUSY -> AutofocusResult.Busy(
                "Kamera wurde innerhalb von ${timeoutMs} ms nicht wieder bereit"
            )

            else -> AutofocusResult.Failed(
                CameraError.Protocol(PtpConstants.OC_NIKON_DEVICE_READY, settled)
            )
        }
    }

    /** Cancels a running AF drive, if the body supports it. Used when monitoring stops. */
    fun cancelAutofocus() {
        if (!capabilities.afDriveCancel) return
        runCatching { session.transact(PtpConstants.OC_NIKON_AF_DRIVE_CANCEL, timeoutMs = 1000) }
    }

    // ---------------------------------------------------------------- autofocus modes

    /**
     * Reads one of the autofocus mode properties and the values this body accepts for it.
     *
     * The list of options comes from the camera itself (GetDevicePropDesc), not from a
     * hard coded table, so the UI can never offer a mode the body would reject. When the
     * camera reports no enumeration, the documented Nikon values are offered as a
     * fallback - some bodies answer with an empty form even though the values work.
     */
    fun readAfMode(propertyCode: Int): AfModeState? {
        if (!deviceInfo.hasProperty(propertyCode)) return null
        val descriptor = runCatching { session.getDevicePropDesc(propertyCode) }.getOrNull()
            ?: return null

        val labelFor: (Long) -> String = when (propertyCode) {
            PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA -> PtpConstants::afAreaModeName
            PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS -> PtpConstants::afServoModeName
            else -> { value -> value.toString() }
        }

        val values = descriptor.enumValues.ifEmpty {
            when (propertyCode) {
                PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA -> listOf(0L, 1L, 2L, 3L, 4L)
                PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS -> listOf(0L, 1L, 2L)
                else -> emptyList()
            }
        }

        return AfModeState(
            propertyCode = propertyCode,
            current = descriptor.currentValue,
            options = values.map { AfModeOption(it, labelFor(it)) },
            writable = descriptor.writable
        )
    }

    /** Writes an autofocus mode. Returns the response code so callers can explain failures. */
    fun setAfMode(propertyCode: Int, value: Long): Int {
        if (!deviceInfo.hasProperty(propertyCode)) {
            return PtpConstants.RC_OPERATION_NOT_SUPPORTED
        }
        // The property is INT8 on some bodies and UINT8 on others; both are one byte and
        // identical for the values 0..4 that these modes use.
        val response = runCatching {
            session.setDevicePropValue(propertyCode, value, PtpConstants.DTC_UINT8)
        }.getOrNull() ?: return PtpConstants.RC_GENERAL_ERROR
        if (response.isOk) waitUntilReady(intervalMs = 20, timeoutMs = 2000)
        return response.responseCode
    }

    /**
     * Moves the AF frame inside the LiveView image.
     *
     * Coordinates are in the "whole image" space the LiveView header reports at offset
     * 4/6, which is why the caller passes absolute pixels rather than fractions.
     */
    fun changeAfArea(x: Int, y: Int): Int {
        if (!capabilities.changeAfArea) return PtpConstants.RC_OPERATION_NOT_SUPPORTED
        if (!liveViewRunning) return PtpConstants.RC_NIKON_NOT_LIVE_VIEW
        val response = runCatching {
            session.transact(PtpConstants.OC_NIKON_CHANGE_AF_AREA, intArrayOf(x, y))
        }.getOrNull() ?: return PtpConstants.RC_GENERAL_ERROR
        return response.responseCode
    }

    // ---------------------------------------------------------------- still capture

    /**
     * Releases the shutter once.
     *
     * Nikon offers several capture opcodes and no body implements all of them, so this
     * walks a fallback chain and reports which one actually worked:
     *
     *  1. `Nikon_InitiateCaptureRecInMedia` (0x9207) - the modern one, can write straight
     *     to the memory card. Two parameters (af, target); bodies that only accept one
     *     answer ParameterNotSupported and are then retried with the single parameter form.
     *  2. `Nikon_AfCaptureSDRAM` (0x90CB) - autofocus plus capture into SDRAM.
     *  3. `Nikon_InitiateCaptureRecInSdram` (0x90C0).
     *  4. Plain PTP `InitiateCapture` (0x100E).
     *
     * While LiveView is running the capture is always issued **without** autofocus: the
     * mirror is up, so the phase detection AF cannot run and the body would answer
     * InvalidStatus. Use [triggerAutofocus] first if you want focus before the shot -
     * which is exactly what the monitoring loop already does.
     */
    fun captureStill(
        target: CaptureTarget = CaptureTarget.CARD,
        timeoutMs: Long = 20_000
    ): CaptureResult {
        if (captureUnsupported) return CaptureResult.Unsupported
        if (!capabilities.canCaptureStill) {
            captureUnsupported = true
            return CaptureResult.Unsupported
        }
        if (movieRecording) {
            return CaptureResult.Busy("Videoaufnahme laeuft - Foto waehrenddessen nicht moeglich")
        }

        val readyBefore = try {
            probeReady()
        } catch (e: CameraException) {
            return CaptureResult.Failed(e.error)
        }
        if (isBusyCode(readyBefore)) {
            return CaptureResult.Busy(
                "DeviceReady meldet ${PtpConstants.responseName(readyBefore)}"
            )
        }

        // Point the body at the requested destination before triggering. Without this a
        // body left in SDRAM mode by the LiveView start sequence answers StoreNotAvailable.
        if (capabilities.hasRecordingMediaProp) {
            val value = if (target == CaptureTarget.CARD) {
                PtpConstants.RECORDING_MEDIA_CARD
            } else {
                PtpConstants.RECORDING_MEDIA_SDRAM
            }
            runCatching {
                session.setDevicePropValue(
                    PtpConstants.DPC_NIKON_RECORDING_MEDIA, value, PtpConstants.DTC_UINT8
                )
            }
        }

        val sdramFlag = if (target == CaptureTarget.SDRAM) 1 else 0
        var lastCode = PtpConstants.RC_OPERATION_NOT_SUPPORTED
        var lastOperation = PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA

        for (attempt in candidateCaptureOperations()) {
            val (operation, parameters) = when (attempt) {
                PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA ->
                    if (captureNeedsSingleParameter) {
                        attempt to intArrayOf(PtpConstants.CAPTURE_WITHOUT_AF)
                    } else {
                        attempt to intArrayOf(PtpConstants.CAPTURE_WITHOUT_AF, sdramFlag)
                    }

                PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM ->
                    attempt to intArrayOf(PtpConstants.CAPTURE_WITHOUT_AF)

                PtpConstants.OC_INITIATE_CAPTURE -> attempt to intArrayOf(0, 0)

                else -> attempt to IntArray(0)
            }

            val code = runCaptureWithRetries(operation, parameters, timeoutMs)
            lastCode = code
            lastOperation = operation

            when (code) {
                PtpConstants.RC_OK -> {
                    waitUntilReady(intervalMs = 50, timeoutMs = timeoutMs)
                    drainEvents()
                    return CaptureResult.Released(operation, target)
                }

                PtpConstants.RC_OPERATION_NOT_SUPPORTED -> continue

                PtpConstants.RC_DEVICE_BUSY,
                PtpConstants.RC_NIKON_BULB_RELEASE_BUSY -> return CaptureResult.Busy(
                    PtpConstants.responseName(code)
                )

                PtpConstants.RC_STORE_NOT_AVAILABLE -> return CaptureResult.Failed(
                    CameraError.CaptureStoreUnavailable(target == CaptureTarget.CARD)
                )

                else -> return CaptureResult.Failed(CameraError.Protocol(operation, code))
            }
        }

        if (lastCode == PtpConstants.RC_OPERATION_NOT_SUPPORTED) {
            captureUnsupported = true
            return CaptureResult.Unsupported
        }
        return CaptureResult.Failed(CameraError.Protocol(lastOperation, lastCode))
    }

    /** Capture opcodes to try, most capable first. */
    private fun candidateCaptureOperations(): List<Int> = buildList {
        if (capabilities.captureRecInMedia) add(PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA)
        // AfCaptureSDRAM drives the AF itself, which is impossible with the mirror up.
        if (capabilities.afCaptureSdram && !liveViewRunning) {
            add(PtpConstants.OC_NIKON_AF_CAPTURE_SDRAM)
        }
        if (capabilities.captureRecInSdram) add(PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM)
        if (capabilities.standardCapture) add(PtpConstants.OC_INITIATE_CAPTURE)
    }

    /**
     * Sends one capture opcode, absorbing the transient answers Nikon bodies give while
     * the mirror or the buffer is still moving. Returns the final response code.
     */
    private fun runCaptureWithRetries(
        operation: Int,
        parameters: IntArray,
        timeoutMs: Long
    ): Int {
        var currentParameters = parameters
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, CAPTURE_RETRY_WINDOW_MS)
        while (true) {
            val response = try {
                session.transact(
                    operation,
                    currentParameters,
                    timeoutMs = CAPTURE_COMMAND_TIMEOUT
                )
            } catch (e: PtpTransportException) {
                return PtpConstants.RC_GENERAL_ERROR
            }

            when (response.responseCode) {
                // Body wants the single parameter form of 0x9207 (D3x and relatives).
                PtpConstants.RC_PARAMETER_NOT_SUPPORTED -> {
                    if (operation == PtpConstants.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA &&
                        currentParameters.size > 1
                    ) {
                        captureNeedsSingleParameter = true
                        currentParameters = intArrayOf(PtpConstants.CAPTURE_WITHOUT_AF)
                        continue
                    }
                    return response.responseCode
                }

                PtpConstants.RC_DEVICE_BUSY,
                PtpConstants.RC_NIKON_INVALID_STATUS -> {
                    if (System.currentTimeMillis() >= deadline) return response.responseCode
                    Thread.sleep(CAPTURE_RETRY_INTERVAL_MS)
                }

                else -> return response.responseCode
            }
        }
    }

    // ---------------------------------------------------------------- movie recording

    fun readMovieProhibitCondition(): Long? {
        if (!capabilities.hasMovieProhibitProp) return null
        return runCatching {
            session.getDevicePropValue(
                PtpConstants.DPC_NIKON_MOV_REC_PROHIBIT_CONDITION, PtpConstants.DTC_UINT32
            )
        }.getOrNull()
    }

    private fun readPropOrNull(code: Int, dataType: Int): Long? {
        if (!deviceInfo.hasProperty(code)) return null
        return runCatching { session.getDevicePropValue(code, dataType) }.getOrNull()
    }

    /** Reads everything that can explain a refused movie start. */
    fun collectMovieDiagnostics(lastResponseCode: Int): MovieDiagnostics = MovieDiagnostics(
        liveViewStatus = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS, PtpConstants.DTC_UINT8
        ),
        movieProhibit = readPropOrNull(
            PtpConstants.DPC_NIKON_MOV_REC_PROHIBIT_CONDITION, PtpConstants.DTC_UINT32
        ),
        liveViewProhibit = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_PROHIBIT_CONDITION, PtpConstants.DTC_UINT32
        ),
        exposureProgram = readPropOrNull(
            PtpConstants.DPC_EXPOSURE_PROGRAM_MODE, PtpConstants.DTC_UINT16
        ),
        recordingMedia = readPropOrNull(
            PtpConstants.DPC_NIKON_RECORDING_MEDIA, PtpConstants.DTC_UINT8
        ),
        liveViewSelector = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR, PtpConstants.DTC_UINT8
        ),
        liveViewMode = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_MODE, PtpConstants.DTC_UINT8
        ),
        movieCaptureMode = readPropOrNull(
            PtpConstants.DPC_NIKON_MOVIE_CAPTURE_MODE, PtpConstants.DTC_UINT8
        ),
        applicationMode = readPropOrNull(
            PtpConstants.DPC_NIKON_APPLICATION_MODE, PtpConstants.DTC_UINT8
        ),
        hasStartMovie = capabilities.startMovie,
        hasEndMovie = capabilities.endMovie,
        hasOpenCapture = capabilities.openCapture,
        lastResponseCode = lastResponseCode
    )

    /**
     * Starts a movie recording.
     *
     * This is the libgphoto2 `_put_Nikon_Movie` / `ptp_nikon_startmovie` path
     * (`camlibs/ptp2/config.c`, `ptp.h`): ApplicationMode on, LiveView on,
     * then `PTP_OC_NIKON_StartMovieRecInCard` (0x920A) with **no parameters and no
     * data**. That opcode is the tethered equivalent of the red movie button: the
     * D3400 writes the clip to the SD card.
     *
     * RecordingMedia is pointed at the card first, same as a still capture. The
     * LiveView start sequence leaves it on SDRAM; sending StartMovieRec**InCard**
     * against SDRAM is what D3400 answers with InvalidStatus. Camera Connect and
     * Control keeps the destination on the card, which is also the camera default.
     */
    fun startMovieRecording(): MovieResult {
        if (!capabilities.canRecordMovie && !capabilities.openCapture) {
            return MovieResult.Unsupported
        }
        if (movieRecording) return MovieResult.Started

        try {
            if (!liveViewRunning) startLiveView()
        } catch (e: CameraException) {
            return MovieResult.Failed(e.error)
        }

        // libgphoto2: set 0xD1F0 and/or send 0x9435 before 0x920A. The D3400 exposes
        // ApplicationMode (0xD1F0) and leaves it at 0 until a host writes 1.
        enterApplicationMode()
        waitUntilReady(intervalMs = 50, timeoutMs = 1000)

        pointRecordingMediaAtCard()
        waitUntilReady(intervalMs = 50, timeoutMs = 1000)
        ensureLiveViewIsOn()
        drainEvents()

        var lastCode = PtpConstants.RC_OPERATION_NOT_SUPPORTED

        if (capabilities.startMovie) {
            lastCode = attemptNikonMovieStart()
            if (lastCode == PtpConstants.RC_OK) {
                movieRecording = true
                movieUsesOpenCapture = false
                return MovieResult.Started
            }
            if (lastCode == PtpConstants.RC_DEVICE_BUSY ||
                lastCode == PtpConstants.RC_NIKON_BULB_RELEASE_BUSY
            ) {
                return MovieResult.Busy(PtpConstants.responseName(lastCode))
            }

            // Higher-end bodies with a still/movie LiveView switch: 0x920A is refused
            // while the selector is on photo LV. D3400 does not have 0xD1A6, so this
            // is a no-op there.
            if ((lastCode == PtpConstants.RC_NIKON_INVALID_STATUS ||
                    lastCode == PtpConstants.RC_NIKON_NOT_LIVE_VIEW) &&
                deviceInfo.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR)
            ) {
                runCatching { switchToMovieLiveView(allowRestart = true) }
                    .onFailure { Log.w(TAG, "Video-LiveView-Neustart fehlgeschlagen", it) }
                pointRecordingMediaAtCard()
                ensureLiveViewIsOn()
                drainEvents()
                lastCode = attemptNikonMovieStart()
                if (lastCode == PtpConstants.RC_OK) {
                    movieRecording = true
                    movieUsesOpenCapture = false
                    return MovieResult.Started
                }
                if (lastCode == PtpConstants.RC_DEVICE_BUSY ||
                    lastCode == PtpConstants.RC_NIKON_BULB_RELEASE_BUSY
                ) {
                    return MovieResult.Busy(PtpConstants.responseName(lastCode))
                }
            }
        }

        // Vendor neutral fallback.
        if (capabilities.openCapture) {
            waitUntilReady(intervalMs = 20, timeoutMs = 1500)
            val response = runCatching {
                session.transact(
                    PtpConstants.OC_INITIATE_OPEN_CAPTURE,
                    intArrayOf(0, 0),
                    timeoutMs = MOVIE_COMMAND_TIMEOUT
                )
            }.getOrNull()
            if (response != null) {
                if (response.isOk) {
                    movieRecording = true
                    movieUsesOpenCapture = true
                    openCaptureTransactionId = session.lastTransactionId
                    return MovieResult.Started
                }
                lastCode = response.responseCode
            }
        }

        val prohibit = readMovieProhibitCondition()
        if (prohibit != null && prohibit != 0L) return MovieResult.Prohibited(prohibit)

        return MovieResult.Rejected(collectMovieDiagnostics(lastCode))
    }

    /**
     * Sends 0x920A with no parameters and no data phase.
     * libgphoto2: `ptp_generic_no_data(params, PTP_OC_NIKON_StartMovieRecInCard, 0)`.
     * DeviceBusy right after ApplicationMode / RecordingMedia is transient; InvalidStatus is not.
     */
    private fun attemptNikonMovieStart(): Int {
        var lastCode = PtpConstants.RC_NIKON_INVALID_STATUS
        repeat(MOVIE_START_ATTEMPTS) { attempt ->
            waitUntilReady(intervalMs = 50, timeoutMs = 1000)
            if (attempt > 0) Thread.sleep(MOVIE_START_RETRY_DELAY_MS)
            val response = try {
                session.transact(
                    PtpConstants.OC_NIKON_START_MOVIE_REC_IN_CARD,
                    timeoutMs = MOVIE_COMMAND_TIMEOUT
                )
            } catch (e: PtpTransportException) {
                return PtpConstants.RC_GENERAL_ERROR
            }
            lastCode = response.responseCode
            Log.i(TAG, "StartMovieRecInCard 0x920A -> " + PtpConstants.responseName(lastCode))
            if (lastCode == PtpConstants.RC_OK) return lastCode
            if (lastCode != PtpConstants.RC_DEVICE_BUSY) return lastCode
        }
        return lastCode
    }

    /** libgphoto2 `_put_Nikon_Movie`: ApplicationMode property and/or opcode to 1. */
    private fun enterApplicationMode() {
        if (capabilities.hasApplicationModeProp) {
            val current = readPropOrNull(
                PtpConstants.DPC_NIKON_APPLICATION_MODE, PtpConstants.DTC_UINT8
            )
            if (current != 1L) {
                val response = runCatching {
                    session.setDevicePropValue(
                        PtpConstants.DPC_NIKON_APPLICATION_MODE, 1, PtpConstants.DTC_UINT8
                    )
                }.getOrNull()
                Log.i(
                    TAG,
                    "ApplicationMode 0xD1F0: was $current, set 1 -> " +
                        (response?.let { PtpConstants.responseName(it.responseCode) } ?: "kein Antwort")
                )
            }
        }
        if (capabilities.changeApplicationMode) {
            runCatching {
                session.transact(PtpConstants.OC_NIKON_CHANGE_APPLICATION_MODE, intArrayOf(1))
            }
        }
    }

    /**
     * StartMovieRecInCard records onto the SD card, same as the red button.
     * Mirrors the still-capture path: LiveView start leaves RecordingMedia on SDRAM,
     * so it is switched back to card before the record opcode.
     */
    private fun pointRecordingMediaAtCard() {
        if (!capabilities.hasRecordingMediaProp) return
        val current = readPropOrNull(
            PtpConstants.DPC_NIKON_RECORDING_MEDIA, PtpConstants.DTC_UINT8
        )
        if (current == PtpConstants.RECORDING_MEDIA_CARD) return
        val response = runCatching {
            session.setDevicePropValue(
                PtpConstants.DPC_NIKON_RECORDING_MEDIA,
                PtpConstants.RECORDING_MEDIA_CARD,
                PtpConstants.DTC_UINT8
            )
        }.getOrNull()
        Log.i(
            TAG,
            "RecordingMedia 0xD10B: was $current, set Card -> " +
                (response?.let { PtpConstants.responseName(it.responseCode) } ?: "kein Antwort")
        )
        waitUntilReady(intervalMs = 50, timeoutMs = 1000)
    }

    /**
     * Switches LiveViewSelector to movie (1).
     *
     * @param allowRestart when true and the property is only writable with LiveView off,
     * LiveView is ended, the selector is written, then LiveView is started again without
     * handing PC control back. [startLiveView] must not see a stale "already on" status
     * after EndLiveView, otherwise the selector change never takes effect.
     */
    private fun switchToMovieLiveView(allowRestart: Boolean) {
        if (!deviceInfo.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR)) return
        val current = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR, PtpConstants.DTC_UINT8
        )
        if (current == PtpConstants.LIVE_VIEW_SELECTOR_MOVIE) return

        val descriptor = runCatching {
            session.getDevicePropDesc(PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR)
        }.getOrNull()
        if (descriptor != null && !descriptor.writable) {
            Log.w(
                TAG,
                "LiveViewSelector ist schreibgeschuetzt und steht auf " +
                    PtpConstants.liveViewSelectorName(current ?: -1)
            )
            return
        }

        if (trySetLiveViewSelector(PtpConstants.LIVE_VIEW_SELECTOR_MOVIE)) {
            waitUntilReady(intervalMs = 20, timeoutMs = 1500)
            val after = readPropOrNull(
                PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR, PtpConstants.DTC_UINT8
            )
            if (after == PtpConstants.LIVE_VIEW_SELECTOR_MOVIE) return
        }

        if (!allowRestart) return

        stopLiveViewKeepingControl()
        waitUntilLiveViewOff()
        trySetLiveViewSelector(PtpConstants.LIVE_VIEW_SELECTOR_MOVIE)
        waitUntilReady(intervalMs = 20, timeoutMs = 1500)
        try {
            startLiveView()
        } catch (e: CameraException) {
            Log.w(TAG, "LiveView-Neustart nach Video-Selektor fehlgeschlagen: ${e.message}")
        }
        waitUntilReady(intervalMs = 20, timeoutMs = 2000)
    }

    /** Waits until 0xD1A2 reports LiveView off, so a following StartLiveView is not skipped. */
    private fun waitUntilLiveViewOff(timeoutMs: Long = 2500) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = readLiveViewStatus()
            if (status == null || status == 0) {
                waitUntilReady(intervalMs = 20, timeoutMs = 1500)
                return
            }
            Thread.sleep(40)
        }
        Log.w(TAG, "LiveViewStatus blieb nach EndLiveView gesetzt")
        waitUntilReady(intervalMs = 20, timeoutMs = 1500)
    }

    private fun trySetLiveViewSelector(value: Long): Boolean {
        val response = runCatching {
            session.setDevicePropValue(
                PtpConstants.DPC_NIKON_LIVE_VIEW_SELECTOR, value, PtpConstants.DTC_UINT8
            )
        }.getOrNull() ?: return false
        if (!response.isOk) {
            Log.w(
                TAG,
                "LiveViewSelector=$value: " + PtpConstants.responseName(response.responseCode)
            )
        }
        return response.isOk
    }

    /** Ends LiveView without handing the body back to the physical controls. */
    private fun stopLiveViewKeepingControl() {
        liveViewRunning = false
        if (capabilities.endLiveView) {
            runCatching { session.transact(PtpConstants.OC_NIKON_END_LIVE_VIEW) }
        }
    }

    private fun ensureLiveViewIsOn() {
        val reported = readPropOrNull(
            PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS, PtpConstants.DTC_UINT8
        )
        if ((reported != null && reported == 0L) || !liveViewRunning) {
            runCatching { startLiveView() }
            waitUntilReady(intervalMs = 20, timeoutMs = 2000)
        }
    }

    fun stopMovieRecording(): MovieResult {
        val response = try {
            if (movieUsesOpenCapture) {
                session.transact(
                    PtpConstants.OC_TERMINATE_OPEN_CAPTURE,
                    intArrayOf(openCaptureTransactionId),
                    timeoutMs = MOVIE_COMMAND_TIMEOUT
                )
            } else {
                if (!capabilities.endMovie) return MovieResult.Unsupported
                session.transact(
                    PtpConstants.OC_NIKON_END_MOVIE_REC,
                    timeoutMs = MOVIE_COMMAND_TIMEOUT
                )
            }
        } catch (e: PtpTransportException) {
            movieRecording = false
            movieUsesOpenCapture = false
            return MovieResult.Failed(CameraError.NotResponding)
        }

        // Whatever the body answers, stop tracking the recording: leaving the flag set
        // would block every later capture.
        movieRecording = false
        movieUsesOpenCapture = false

        if (response.isOk || response.responseCode == PtpConstants.RC_NIKON_INVALID_STATUS) {
            // The card write continues for a moment after the stop command.
            waitUntilReady(intervalMs = 100, timeoutMs = MOVIE_FLUSH_TIMEOUT)
            drainEvents()
            resetApplicationMode()
            return MovieResult.Stopped
        }

        return MovieResult.Failed(
            CameraError.Protocol(PtpConstants.OC_NIKON_END_MOVIE_REC, response.responseCode)
        )
    }

    private fun resetApplicationMode() {
        if (capabilities.hasApplicationModeProp) {
            runCatching {
                val current = session.getDevicePropValue(
                    PtpConstants.DPC_NIKON_APPLICATION_MODE, PtpConstants.DTC_UINT8
                )
                if (current != 0L) {
                    session.setDevicePropValue(
                        PtpConstants.DPC_NIKON_APPLICATION_MODE, 0, PtpConstants.DTC_UINT8
                    )
                }
            }
        }
        if (capabilities.changeApplicationMode) {
            runCatching {
                session.transact(PtpConstants.OC_NIKON_CHANGE_APPLICATION_MODE, intArrayOf(0))
            }
        }
    }

    /**
     * Reads and discards the vendor event queue. Nikon bodies stop responding once the
     * queue overflows, and this app never needs the events themselves - captured files
     * stay on the card.
     */
    fun drainEvents() {
        if (!capabilities.getEvent) return
        runCatching { session.transact(PtpConstants.OC_NIKON_GET_EVENT, timeoutMs = 1500) }
    }

    // ---------------------------------------------------------------- control handover

    /**
     * Gives the physical camera controls back to the user.
     *
     * Nikon locks the body while a host holds PC control and while tethered LiveView runs,
     * which is why the shutter and the movie button do nothing during a session. This ends
     * LiveView and clears the control mode, at the cost of the preview stream.
     */
    fun releaseToCamera() {
        runCatching { endLiveView() }
        releaseControlIfTaken()
    }

    /** Takes control back and restarts LiveView. */
    fun resumeAppControl() {
        takeControlIfPossible()
        if (!liveViewRunning) startLiveView()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun takeControlIfPossible() {
        if (controlTaken || !capabilities.changeCameraMode || !takeControlOnLiveView) return
        val response = runCatching {
            session.transact(PtpConstants.OC_NIKON_CHANGE_CAMERA_MODE, intArrayOf(1))
        }.getOrNull()
        // Not fatal: several bodies answer ChangeCameraModeFailed and still work fine.
        controlTaken = response?.isOk == true
        if (!controlTaken && response != null) {
            Log.w(TAG, "ChangeCameraMode: ${PtpConstants.responseName(response.responseCode)}")
        }
    }

    private fun releaseControlIfTaken() {
        if (!controlTaken) return
        runCatching { session.transact(PtpConstants.OC_NIKON_CHANGE_CAMERA_MODE, intArrayOf(0)) }
        controlTaken = false
    }

    fun close() {
        if (movieRecording) runCatching { stopMovieRecording() }
        runCatching { endLiveView() }
        runCatching { session.closeSession() }
    }

    companion object {
        private const val TAG = "NikonPtpCamera"
        private const val START_LIVE_VIEW_TIMEOUT = 6000
        private const val AF_COMMAND_TIMEOUT = 8000
        private const val FRAME_TIMEOUT = 3000
        private const val CAPTURE_COMMAND_TIMEOUT = 15000
        private const val CAPTURE_RETRY_WINDOW_MS = 3000L
        private const val CAPTURE_RETRY_INTERVAL_MS = 50L
        private const val MOVIE_COMMAND_TIMEOUT = 10000
        private const val MOVIE_FLUSH_TIMEOUT = 30000L
        private const val MOVIE_START_ATTEMPTS = 3
        private const val MOVIE_START_RETRY_DELAY_MS = 250L
    }
}
