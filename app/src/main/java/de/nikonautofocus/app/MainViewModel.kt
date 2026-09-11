package de.nikonautofocus.app

import android.app.Application
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.nikonautofocus.app.analysis.SharpnessAnalyzer
import de.nikonautofocus.app.capture.CaptureController
import de.nikonautofocus.app.capture.IntervalController
import de.nikonautofocus.app.capture.IntervalSession
import de.nikonautofocus.app.capture.IntervalSlotResult
import de.nikonautofocus.app.capture.IntervalTiming
import de.nikonautofocus.app.capture.BracketingController
import de.nikonautofocus.app.capture.BracketValidation
import de.nikonautofocus.app.capture.PreparedBracket
import de.nikonautofocus.app.capture.IntervalSettings
import de.nikonautofocus.app.service.IntervalCaptureService
import de.nikonautofocus.app.focus.FocusAction
import de.nikonautofocus.app.focus.FocusController
import de.nikonautofocus.app.focus.FocusSettings
import de.nikonautofocus.app.focus.FocusState
import de.nikonautofocus.app.focus.FocusStateMachine
import de.nikonautofocus.app.focus.FocusStatus
import de.nikonautofocus.app.liveview.AfAreaCoordinates
import de.nikonautofocus.app.liveview.LiveViewHeader
import de.nikonautofocus.app.liveview.LiveViewHeaderLayout
import de.nikonautofocus.app.liveview.LiveViewProcessor
import de.nikonautofocus.app.settings.SettingsRepository
import de.nikonautofocus.app.usb.AfModeState
import de.nikonautofocus.app.usb.CameraCapabilities
import de.nikonautofocus.app.usb.CameraError
import de.nikonautofocus.app.usb.CameraException
import de.nikonautofocus.app.usb.CameraPropertyState
import de.nikonautofocus.app.usb.CaptureTarget
import de.nikonautofocus.app.usb.FrameSource
import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.PtpDeviceInfo
import de.nikonautofocus.app.usb.UsbPtpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything the screen renders. */
data class UiState(
    val focus: FocusStatus,
    val deviceDetected: Boolean = false,
    val deviceDescription: String? = null,
    val connected: Boolean = false,
    val cameraModel: String? = null,
    val cameraFirmware: String? = null,
    val capabilities: CameraCapabilities? = null,
    val frameSource: FrameSource = FrameSource.NONE,
    val fps: Double = 0.0,
    val analysisDurationMs: Long = 0,
    val analysisResolution: String = "",
    val jpegSizeKb: Int = 0,
    val liveViewHeaderBytes: Int = 0,
    val droppedFrames: Long = 0,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val infoMessage: String? = null,
    val autofocusFlashActive: Boolean = false,
    val scoreHistory: List<Float> = emptyList(),
    val diagnostics: String? = null,
    val busy: Boolean = false,

    // --- shutter release and movie recording ---
    val captureAvailable: Boolean = false,
    val movieAvailable: Boolean = false,
    val captureBusy: Boolean = false,
    val recording: Boolean = false,
    val recordingElapsedMs: Long = 0,
    val captureFlashActive: Boolean = false,
    val lastCaptureInfo: String? = null,
    /** false once the app has handed the physical controls back to the camera. */
    val appControlsCamera: Boolean = true,

    // --- autofocus frame and modes ---
    /** AF frame from the LiveView header, null when the camera does not report one. */
    val afFrame: AfFrameOverlay? = null,
    val afAreaMode: AfModeState? = null,
    val afServoMode: AfModeState? = null,
    val afModeBusy: Boolean = false,
    /** True when tapping the preview moves the camera AF area. */
    val canMoveAfArea: Boolean = false,
    /** True when tapping the preview does anything at all. */
    val canTapPreview: Boolean = false,
    /** The user placed measuring field, drawn on the preview. */
    val manualField: AfFrameOverlay? = null,
    /** Where the displayed focus information comes from. */
    val focusTargetSource: FocusTargetSource = FocusTargetSource.NONE,

    // --- Camera Connect style liveview controls ---
    val cameraControls: List<CameraPropertyState> = emptyList(),
    val cameraControlBusy: Boolean = false,
    val batteryPercent: Int? = null,
    val remainingImages: Long? = null,
    val histogram: List<Int> = emptyList(),
    val interval: IntervalSession = IntervalSession.idle()
)

/** What the app can actually say about where the camera is focusing. */
enum class FocusTargetSource {
    /** The camera reports its AF frame in the LiveView header - this is the real thing. */
    CAMERA_REPORTED,

    /** The camera reports nothing usable, but the user has placed a field themselves. */
    MANUAL_FIELD,

    /** Neither: sharpness is measured over the whole frame. */
    WHOLE_FRAME,

    /** Not connected / no LiveView. */
    NONE
}

/** A ChangeAfArea request (image fractions) that still awaits confirmation by a header. */
private data class PendingAfAim(val x: Float, val y: Float, val expiresAt: Long)

/** AF frame position for the preview overlay, in fractions of the displayed image. */
data class AfFrameOverlay(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val focused: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val usbManager = UsbPtpManager(application)
    private val settingsRepository = SettingsRepository(application)
    private val liveViewProcessor = LiveViewProcessor()
    private val analyzer = SharpnessAnalyzer(settingsRepository.current.analysisWidth)
    private val focusController = FocusController(usbManager)
    private val captureController = CaptureController(usbManager)
    private val bracketingController = BracketingController(usbManager, captureController)
    private val intervalController = IntervalController()
    private val stateMachine = FocusStateMachine(settingsRepository.current)

    private val _uiState = MutableStateFlow(UiState(focus = stateMachine.snapshot()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()

    val settings: StateFlow<FocusSettings> = settingsRepository.settings

    private var frameJob: Job? = null
    private var connectJob: Job? = null

    private var deviceInfo: PtpDeviceInfo? = null
    private var capabilities: CameraCapabilities? = null
    private var frameSource: FrameSource = FrameSource.NONE

    private val history = ArrayDeque<Float>(HISTORY_LENGTH)
    private var smoothedFps = 0.0
    private var lastFrameAt = 0L
    private var autofocusFlashUntil = 0L
    private var consecutiveEmptyFrames = 0
    private var lastGoodFrameAt = 0L

    /** Built once per connection - it is far too expensive to rebuild per frame. */
    private var diagnosticsText: String? = null

    /** Camera state captured the last time a movie start was refused. */
    private var movieDiagnosticsText: String? = null

    // Per frame statistics, folded into the UI state by publish().
    private var analysisDurationMs = 0L
    private var analysisResolution = ""
    private var jpegSizeKb = 0
    private var liveViewHeaderBytes = 0

    // Autofocus frame overlay and mode properties.
    private var afFrame: AfFrameOverlay? = null
    private var afAreaMode: AfModeState? = null
    private var afServoMode: AfModeState? = null
    private var lastAfImageWidth = 0
    private var lastAfImageHeight = 0
    private var lastJpegWidth = 0
    private var lastJpegHeight = 0
    private var lastHeaderLayout: LiveViewHeaderLayout? = null
    private var afModeJob: Job? = null

    /** Position the camera was last asked to put its AF frame at, until a header confirms. */
    private var pendingAfAim: PendingAfAim? = null

    /** Outcome of the last AF-frame verification, shown on the diagnostics page. */
    private var afAimReport: String? = null
    private var lastAfAreaText: String? = null
    private var unknownAfGridWarned = false
    private var lastExposureProgram: Long? = null

    private var cameraControls: List<CameraPropertyState> = emptyList()
    private var statusHudBattery: Int? = null
    private var statusHudRemaining: Long? = null
    private var histogramBins: List<Int> = emptyList()
    private var cameraControlJob: Job? = null

    // Shutter release / movie recording.
    private var captureJob: Job? = null
    private var intervalJob: Job? = null
    private var recordingTickerJob: Job? = null
    private var captureFlashUntil = 0L
    private var appControlsCamera = true
    private var monitoringPausedByRecording = false

    init {
        usbManager.registerHotplug(
            onAttached = { device -> onDeviceAttached(device) },
            onDetached = { onDeviceDetached() }
        )
        refreshDeviceState()
    }

    // ------------------------------------------------------------------ device discovery

    fun refreshDeviceState() {
        val device = usbManager.findCamera()
        _uiState.value = _uiState.value.copy(
            deviceDetected = device != null,
            deviceDescription = device?.let { usbManager.describeDevice(it) },
            infoMessage = if (device == null && !usbManager.isConnected) {
                CameraError.NotFound.message
            } else {
                _uiState.value.infoMessage
            }
        )
    }

    private fun onDeviceAttached(device: UsbDevice) {
        _uiState.value = _uiState.value.copy(
            deviceDetected = true,
            deviceDescription = usbManager.describeDevice(device),
            infoMessage = "Kamera erkannt: ${usbManager.describeDevice(device)}"
        )
    }

    private fun onDeviceDetached() {
        stopFrameLoop()
        stateMachine.onDisconnected()
        deviceInfo = null
        diagnosticsText = null
        movieDiagnosticsText = null
        resetCaptureState()
        capabilities = null
        frameSource = FrameSource.NONE
        _preview.value = null
        _uiState.value = _uiState.value.copy(
            connected = false,
            deviceDetected = usbManager.findCamera() != null,
            cameraModel = null,
            cameraFirmware = null,
            capabilities = null,
            frameSource = FrameSource.NONE,
            errorMessage = CameraError.Disconnected.message,
            focus = stateMachine.snapshot()
        )
    }

    // ------------------------------------------------------------------ connect / disconnect

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            stateMachine.onConnecting()
            publish(busy = true, error = null, warning = null, info = "Verbinde mit Kamera ...")

            val device = usbManager.findCamera()
            if (device == null) {
                stateMachine.onError(CameraError.NotFound.message)
                publish(busy = false, error = CameraError.NotFound.message)
                return@launch
            }

            if (!usbManager.hasPermission(device)) {
                publish(busy = true, info = "Warte auf USB-Berechtigung ...")
                val granted = usbManager.requestPermission(device)
                if (!granted) {
                    stateMachine.onError(CameraError.PermissionDenied.message)
                    publish(busy = false, error = CameraError.PermissionDenied.message)
                    return@launch
                }
            }

            try {
                val camera = usbManager.connect(device)
                deviceInfo = camera.deviceInfo
                capabilities = camera.capabilities
                frameSource = camera.capabilities.frameSource
                diagnosticsText = buildDiagnostics(camera.deviceInfo)

                publish(
                    busy = true,
                    info = "Verbunden mit ${camera.deviceInfo.model}. Starte LiveView ..."
                )

                captureController.reset()
                appControlsCamera = true
                monitoringPausedByRecording = false
                usbManager.withCamera { cam ->
                    cam.takeControlOnLiveView = settingsRepository.current.takeCameraControl
                    cam.startLiveView()
                }
                frameSource = camera.capabilities.frameSource

                stateMachine.onLiveViewReady()
                liveViewProcessor.resetStatistics()
                history.clear()
                lastGoodFrameAt = SystemClock.elapsedRealtime()
                startFrameLoop()

                val warning = when (frameSource) {
                    FrameSource.PREVIEW_IMAGE ->
                        "Diese Kamera meldet keinen LiveView-Stream (0x9201/0x9203). Die App " +
                            "nutzt ersatzweise Nikon_GetPreviewImg (0x9200) - niedrigere " +
                            "Bildrate, sonst identische Schaerfeanalyse."

                    else -> if (camera.capabilities.afDrive) {
                        null
                    } else {
                        CameraError.AutofocusUnsupported.message
                    }
                }
                if (!camera.capabilities.afDrive) {
                    stateMachine.onAutofocusUnsupported(CameraError.AutofocusUnsupported.message)
                }

                publish(
                    busy = false,
                    connected = true,
                    info = "LiveView aktiv",
                    warning = warning
                )
                refreshAfModes()
                refreshCameraControls()
            } catch (e: CameraException) {
                Log.w(TAG, "connect failed", e)
                handleFatal(e.error)
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                handleFatal(CameraError.from(t))
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopFrameLoop()
            runCatching { usbManager.disconnect() }
            stateMachine.onDisconnected()
            deviceInfo = null
            diagnosticsText = null
            movieDiagnosticsText = null
            resetCaptureState()
            capabilities = null
            frameSource = FrameSource.NONE
            _preview.value = null
            refreshDeviceState()
            publish(
                busy = false,
                connected = false,
                info = "Verbindung getrennt",
                error = null,
                warning = null
            )
        }
    }

    private suspend fun handleFatal(error: CameraError) {
        stopFrameLoop()
        runCatching { usbManager.disconnect() }
        stateMachine.onError(error.message)
        deviceInfo = null
        diagnosticsText = null
        movieDiagnosticsText = null
        resetCaptureState()
        capabilities = null
        _preview.value = null
        publish(busy = false, connected = false, error = error.message)
    }

    // ------------------------------------------------------------------ monitoring

    fun startMonitoring() {
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        if (focusController.autofocusUnsupported ||
            capabilities?.afDrive == false
        ) {
            publish(warning = CameraError.AutofocusUnsupported.message)
            return
        }
        if (stateMachine.startMonitoring()) {
            focusController.reset()
            publish(info = "Fokusueberwachung aktiv", error = null)
        }
    }

    fun stopMonitoring() {
        stateMachine.stopMonitoring()
        publish(info = "Fokusueberwachung gestoppt")
    }

    fun toggleMonitoring() {
        if (stateMachine.snapshot().monitoring) stopMonitoring() else startMonitoring()
    }

    /** Manual one shot focus, independent of the automatic logic. */
    fun focusNow() {
        viewModelScope.launch {
            if (!usbManager.isConnected) return@launch
            runAutofocus(manual = true)
        }
    }

    // ------------------------------------------------------------------ autofocus modes

    /** Re-reads both AF mode properties from the camera. */
    fun refreshAfModes() {
        if (!usbManager.isConnected) return
        viewModelScope.launch {
            runCatching {
                usbManager.withCameraOrNull { camera ->
                    afAreaMode = camera.readAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA)
                    afServoMode = camera.readAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS)
                }
            }
            publish()
        }
    }

    /** Switches the AF area mode - this is where "Motivverfolgung" lives (value 3). */
    fun setAfAreaMode(value: Long) =
        setAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA, value, PtpConstants::afAreaModeName)

    /** Switches the AF servo mode (AF-S / AF-C / AF-F). */
    fun setAfServoMode(value: Long) =
        setAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS, value, PtpConstants::afServoModeName)

    private fun setAfMode(propertyCode: Int, value: Long, labelFor: (Long) -> String) {
        if (afModeJob?.isActive == true) return
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        afModeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(afModeBusy = true)
            val code = try {
                usbManager.withCamera { it.setAfMode(propertyCode, value) }
            } catch (e: CameraException) {
                publish(error = e.error.message)
                _uiState.value = _uiState.value.copy(afModeBusy = false)
                return@launch
            }

            // Read back rather than assume: a body may clamp or ignore the write.
            runCatching {
                usbManager.withCameraOrNull { camera ->
                    afAreaMode = camera.readAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA)
                    afServoMode = camera.readAfMode(PtpConstants.DPC_NIKON_LIVE_VIEW_AF_FOCUS)
                }
            }

            _uiState.value = _uiState.value.copy(afModeBusy = false)
            if (code == PtpConstants.RC_OK) {
                publish(info = "Fokusmodus: ${labelFor(value)}")
            } else {
                publish(
                    info = "Fokusmodus nicht uebernommen",
                    warning = "Die Kamera hat den Moduswechsel mit " +
                        "${PtpConstants.responseName(code)} beantwortet. Viele Bodies " +
                        "erlauben das nur im LiveView und nicht waehrend einer Aufnahme."
                )
            }
        }
    }

    /**
     * Moves the user measuring field and optionally the camera AF area.
     *
     * [fractionX] / [fractionY] are relative to the displayed LiveView image, 0..1.
     * During a finger drag [syncCamera] is false so SharedPreferences and ChangeAfArea
     * are not hit on every pointer event; the last call of a gesture passes true.
     */
    fun moveFocusField(fractionX: Float, fractionY: Float, syncCamera: Boolean = true) {
        val x = fractionX.coerceIn(0f, 1f)
        val y = fractionY.coerceIn(0f, 1f)

        if (settingsRepository.current.manualFieldEnabled) {
            settingsRepository.update(persist = syncCamera) {
                it.copy(manualFieldX = x, manualFieldY = y)
            }
            stateMachine.updateSettings(settingsRepository.current)
            publish()
        }

        if (!syncCamera) return
        changeCameraAfArea(x, y)
    }

    /** Tap on the preview: move the field (if on) and send ChangeAfArea. */
    fun setAfPoint(fractionX: Float, fractionY: Float) =
        moveFocusField(fractionX, fractionY, syncCamera = true)

    private fun changeCameraAfArea(x: Float, y: Float) {
        if (!usbManager.isConnected) return
        if (capabilities?.changeAfArea != true) return
        val aim = afAreaPixels(x, y)
        if (aim == null) {
            warnUnknownAfGrid()
            return
        }
        viewModelScope.launch {
            val code = try {
                usbManager.withCamera { it.changeAfArea(aim.first, aim.second) }
            } catch (e: CameraException) {
                publish(error = e.error.message)
                return@launch
            }
            lastAfAreaText = "ChangeAfArea 0x9205 (${aim.first}, ${aim.second}) -> " +
                PtpConstants.responseName(code)
            if (code == PtpConstants.RC_OK) {
                expectAfFrameAt(x, y)
            } else {
                publish(
                    warning = "AF-Messfeld liess sich nicht verschieben: " +
                        PtpConstants.responseName(code)
                )
            }
        }
    }

    /**
     * Pixel coordinates for ChangeAfArea in the camera's whole-image grid, taken from
     * the last parsed LiveView header. Null while no header has been understood - then
     * the grid is unknown and nothing may be sent (see [AfAreaCoordinates]).
     */
    private fun afAreaPixels(fractionX: Float, fractionY: Float): Pair<Int, Int>? =
        AfAreaCoordinates.toAfAreaPixels(fractionX, fractionY, lastAfImageWidth, lastAfImageHeight)

    private fun warnUnknownAfGrid() {
        if (unknownAfGridWarned) return
        unknownAfGridWarned = true
        publish(
            warning = "Der LiveView-Header dieser Kamera wird nicht erkannt " +
                "($liveViewHeaderBytes Byte). Ohne ihn ist der Koordinatenraum fuer " +
                "ChangeAfArea unbekannt; das eigene Fokusfeld wird nur in der App gemessen, " +
                "der Autofokus laeuft auf dem AF-Messfeld der Kamera."
        )
    }

    /** Remember where the AF frame should show up so the next header can confirm it. */
    private fun expectAfFrameAt(fractionX: Float, fractionY: Float) {
        pendingAfAim = PendingAfAim(
            x = fractionX,
            y = fractionY,
            expiresAt = SystemClock.elapsedRealtime() + AF_AIM_CHECK_MS
        )
    }

    /**
     * Compares the AF frame the camera reports with the last requested position. Runs on
     * the first parsed header after a ChangeAfArea; the outcome goes to the diagnostics
     * and, when the camera clearly ignored the request, to the warning banner.
     */
    private fun verifyAfAim(header: LiveViewHeader) {
        val aim = pendingAfAim ?: return
        pendingAfAim = null
        if (SystemClock.elapsedRealtime() > aim.expiresAt) return

        val fieldHalf = settingsRepository.current.measuringField?.size?.div(2f) ?: 0f
        val frameHalf = maxOf(header.focusWidthFraction, header.focusHeightFraction) / 2f
        val tolerance = maxOf(fieldHalf, frameHalf) + AF_AIM_TOLERANCE
        val matches = AfAreaCoordinates.reportedFrameMatches(
            requestedX = aim.x,
            requestedY = aim.y,
            reportedCenterX = header.focusCenterXFraction,
            reportedCenterY = header.focusCenterYFraction,
            toleranceFraction = tolerance
        )
        val report = "Ziel ${pct(aim.x)}/${pct(aim.y)}, Kamera meldet " +
            "${pct(header.focusCenterXFraction)}/${pct(header.focusCenterYFraction)} " +
            "(Gesamtbild ${header.imageWidth}x${header.imageHeight}, " +
            "Layout ${header.layout.label})"
        afAimReport = (if (matches) "OK: " else "ABWEICHUNG: ") + report
        if (!matches) {
            publish(
                warning = "Die Kamera hat das AF-Messfeld nicht dort gesetzt, wo das " +
                    "Fokusfeld liegt ($report). Pruefe den AF-Messfeldmodus " +
                    "(0xD05D): Gesichtserkennung und Motivverfolgung ignorieren die Vorgabe."
            )
        }
    }

    private fun pct(fraction: Float): String = "${(fraction * 100).roundToInt()} %"

    fun setManualFieldEnabled(enabled: Boolean) {
        updateSettings { it.copy(manualFieldEnabled = enabled) }
        publish(
            info = if (enabled) {
                "Fokusfeld aktiv - Schaerfe wird nur darin gemessen"
            } else {
                "Fokusfeld aus - Schaerfe wird ueber das ganze Bild gemessen"
            }
        )
    }

    fun setManualFieldSize(size: Float) = updateSettings { it.copy(manualFieldSize = size) }

    // ------------------------------------------------------------------ camera control properties

    fun refreshCameraControls() {
        if (!usbManager.isConnected) return
        viewModelScope.launch {
            runCatching {
                usbManager.withCameraOrNull { camera ->
                    cameraControls = camera.readCameraControls()
                    val hud = camera.readStorageHud()
                    statusHudBattery = hud.batteryPercent
                    statusHudRemaining = hud.remainingImages
                }
            }
            publish()
        }
    }

    fun setCameraProperty(propertyCode: Int, value: Long, dataType: Int) {
        if (cameraControlJob?.isActive == true) return
        if (intervalController.session.value.active) return
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        cameraControlJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(cameraControlBusy = true)
            val code = try {
                usbManager.withCamera { it.setCameraProperty(propertyCode, value, dataType) }
            } catch (e: CameraException) {
                publish(error = e.error.message)
                _uiState.value = _uiState.value.copy(cameraControlBusy = false)
                return@launch
            }
            runCatching {
                usbManager.withCameraOrNull { camera ->
                    cameraControls = camera.readCameraControls()
                    val hud = camera.readStorageHud()
                    statusHudBattery = hud.batteryPercent
                    statusHudRemaining = hud.remainingImages
                }
            }
            _uiState.value = _uiState.value.copy(cameraControlBusy = false)
            if (code == PtpConstants.RC_OK) {
                val label = cameraControls.firstOrNull { it.propertyCode == propertyCode }
                    ?.currentLabel ?: value.toString()
                publish(info = "Kamera: $label")
            } else {
                publish(
                    warning = "Einstellung nicht uebernommen: " +
                        PtpConstants.responseName(code) +
                        ". Manche Werte sind im aktuellen Belichtungsmodus gesperrt."
                )
            }
        }
    }

    // ------------------------------------------------------------------ shutter and movie

    /**
     * Releases the shutter once.
     *
     * Runs on the same single PTP thread as everything else, so the LiveView poll and the
     * capture can never overlap. Afterwards the focus state machine is told that the
     * stream was interrupted, which throws away the measurement window and starts a
     * cooldown - the black frames right after a capture must not be read as "blurry".
     */
    fun capturePhoto() {
        if (captureJob?.isActive == true || intervalController.session.value.active) return
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        captureJob = viewModelScope.launch {
            publish(info = "Ausloesen ...")
            _uiState.value = _uiState.value.copy(captureBusy = true)

            val target = if (settingsRepository.current.captureToCard) {
                CaptureTarget.CARD
            } else {
                CaptureTarget.SDRAM
            }
            val outcome = captureController.capturePhoto(target)

            if (outcome.success) captureFlashUntil = SystemClock.elapsedRealtime() + AF_FLASH_MS
            stateMachine.onCameraInterruption()
            recoverLiveViewIfNeeded()

            _uiState.value = _uiState.value.copy(
                captureBusy = false,
                lastCaptureInfo = outcome.description
            )
            publish(info = outcome.description, warning = outcome.warning)
            refreshCameraControls()
        }
    }

    fun startIntervalSeries() {
        if (intervalJob?.isActive == true) return
        if (captureJob?.isActive == true) return
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        if (!appControlsCamera) {
            publish(warning = "Intervallaufnahme braucht die Kamerasteuerung durch die App.")
            return
        }
        if (captureController.recording) {
            publish(warning = "Waehrend einer Videoaufnahme ist keine Intervallserie moeglich.")
            return
        }
        intervalJob = viewModelScope.launch {
            val plan = settingsRepository.current.intervalPlan()
            val prepared: PreparedBracket? = if (plan.bracketing.enabled) {
                when (val validation = bracketingController.prepare(plan.bracketing)) {
                    is BracketValidation.Rejected -> {
                        publish(error = validation.reason)
                        return@launch
                    }
                    is BracketValidation.Ready -> validation.plan
                }
            } else {
                null
            }

            val shutterPtp = cameraControls
                .firstOrNull { it.propertyCode == PtpConstants.DPC_EXPOSURE_TIME }
                ?.current ?: 10_000L
            val warnings = IntervalTiming.durationWarnings(
                intervalMs = plan.intervalMs,
                shutterPtp = shutterPtp,
                saveBufferMs = IntervalSettings.DEFAULT_SAVE_BUFFER_MS,
                bracket = prepared
            )
            if (warnings.isNotEmpty()) {
                publish(warning = warnings.joinToString(" "))
            }

            val collectJob = launch {
                intervalController.session.collect {
                    IntervalCaptureService.startOrUpdate(getApplication(), it)
                    publish()
                }
            }
            var end = IntervalSession.idle()
            try {
                intervalController.run(plan) { _, missed ->
                    shootIntervalSlot(prepared, missed)
                }
            } finally {
                end = intervalController.session.value
                collectJob.cancel()
                IntervalCaptureService.stop(getApplication())
                refreshCameraControls()
                recoverLiveViewIfNeeded()
                publish(
                    info = end.lastMessage ?: "Intervallserie beendet",
                    warning = if (end.missedSlots > 0) {
                        "${end.missedSlots} Aufnahme(n) verpasst (Kamera war beschaeftigt oder das Intervall war zu kurz)."
                    } else {
                        KEEP
                    }
                )
            }
        }
    }

    fun pauseIntervalSeries() = intervalController.pause()

    fun resumeIntervalSeries() = intervalController.resume()

    fun cancelIntervalSeries() {
        intervalController.requestCancel()
    }

    private suspend fun shootIntervalSlot(
        prepared: PreparedBracket?,
        missed: Boolean
    ): IntervalSlotResult {
        val target = if (settingsRepository.current.captureToCard) {
            CaptureTarget.CARD
        } else {
            CaptureTarget.SDRAM
        }
        if (prepared != null) {
            val outcome = bracketingController.shootSeries(
                plan = prepared,
                target = target,
                cancelled = { intervalController.cancelling }
            )
            if (outcome.photos > 0) captureFlashUntil = SystemClock.elapsedRealtime() + AF_FLASH_MS
            stateMachine.onCameraInterruption()
            recoverLiveViewIfNeeded()
            return IntervalSlotResult(
                photos = outcome.photos,
                warning = listOfNotNull(
                    if (missed) "Slot verpasst" else null,
                    outcome.warning
                ).joinToString(". ").ifBlank { null }
            )
        }
        val deadline = System.currentTimeMillis() + 20_000L
        var last = captureController.capturePhoto(target)
        while (!last.success && !last.disableCapture && System.currentTimeMillis() < deadline) {
            val busy = last.description.contains("beschaeftigt", ignoreCase = true) ||
                (last.warning?.contains("beschaeftigt", ignoreCase = true) == true)
            if (!busy) break
            kotlinx.coroutines.delay(250)
            last = captureController.capturePhoto(target)
        }
        if (last.success) captureFlashUntil = SystemClock.elapsedRealtime() + AF_FLASH_MS
        stateMachine.onCameraInterruption()
        recoverLiveViewIfNeeded()
        return IntervalSlotResult(
            photos = if (last.success) 1 else 0,
            warning = listOfNotNull(
                if (missed) "Slot verpasst" else null,
                last.warning ?: last.takeIf { !it.success }?.description
            ).joinToString(". ").ifBlank { null }
        )
    }

    fun toggleRecording() {
        if (captureJob?.isActive == true || intervalController.session.value.active) return
        if (!usbManager.isConnected) {
            publish(error = CameraError.Disconnected.message)
            return
        }
        captureJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(captureBusy = true)
            val wasRecording = captureController.recording
            publish(info = if (wasRecording) "Stoppe Videoaufnahme ..." else "Starte Videoaufnahme ...")

            val outcome = if (wasRecording) {
                captureController.stopRecording()
            } else {
                captureController.startRecording()
            }
            movieDiagnosticsText = outcome.diagnostics ?: movieDiagnosticsText

            stateMachine.onCameraInterruption()

            if (outcome.recording) {
                // The D3400 rejects autofocus commands while it writes a movie to the card.
                if (settingsRepository.current.pauseMonitoringWhileRecording &&
                    stateMachine.snapshot().monitoring
                ) {
                    stateMachine.stopMonitoring()
                    monitoringPausedByRecording = true
                }
                startRecordingTicker()
            } else {
                stopRecordingTicker()
                recoverLiveViewIfNeeded()
                if (monitoringPausedByRecording) {
                    monitoringPausedByRecording = false
                    stateMachine.startMonitoring()
                }
            }

            _uiState.value = _uiState.value.copy(
                captureBusy = false,
                lastCaptureInfo = outcome.description
            )
            publish(info = outcome.description, warning = outcome.warning)
        }
    }

    /**
     * Hands the physical camera controls back to the user, or takes them again.
     *
     * Nikon locks the shutter and movie buttons on the body while a host holds PC control
     * and while tethered LiveView runs. Releasing means ending LiveView, so the preview and
     * the focus watchdog stop - that trade is unavoidable and is stated in the UI.
     */
    fun setAppControlsCamera(appControl: Boolean) {
        if (!usbManager.isConnected) return
        if (appControl == appControlsCamera) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            try {
                if (appControl) {
                    usbManager.withCamera { cam ->
                        cam.takeControlOnLiveView = settingsRepository.current.takeCameraControl
                        cam.resumeAppControl()
                    }
                    appControlsCamera = true
                    stateMachine.onLiveViewReady()
                    liveViewProcessor.resetStatistics()
                    history.clear()
                    lastGoodFrameAt = SystemClock.elapsedRealtime()
                    startFrameLoop()
                    publish(busy = false, info = "App steuert die Kamera, LiveView aktiv")
                } else {
                    stopFrameLoop()
                    stateMachine.stopMonitoring()
                    usbManager.withCamera { it.releaseToCamera() }
                    appControlsCamera = false
                    _preview.value = null
                    publish(
                        busy = false,
                        info = "Kamera-Bedienung freigegeben - LiveView und " +
                            "Fokusueberwachung sind dafuer aus"
                    )
                }
            } catch (e: CameraException) {
                publish(busy = false, error = e.error.message)
            } catch (t: Throwable) {
                publish(busy = false, error = CameraError.from(t).message)
            }
        }
    }

    /**
     * A capture or a movie stop can leave the body outside LiveView. Restart it once
     * instead of letting the frame loop fail with "Nikon_NotLiveView".
     */
    private suspend fun recoverLiveViewIfNeeded() {
        if (!appControlsCamera) return
        runCatching {
            usbManager.withCameraOrNull { cam ->
                if (!cam.liveViewRunning) cam.startLiveView()
            }
        }
        liveViewProcessor.resetStatistics()
        consecutiveEmptyFrames = 0
        lastGoodFrameAt = SystemClock.elapsedRealtime()
        if (frameJob?.isActive != true && appControlsCamera) startFrameLoop()
    }

    /** Clears everything capture related. Called on every teardown path. */
    private fun resetCaptureState() {
        stopRecordingTicker()
        captureJob?.cancel()
        captureJob = null
        intervalController.requestCancel()
        intervalJob?.cancel()
        intervalJob = null
        IntervalCaptureService.stop(getApplication())
        intervalController.reset()
        afModeJob?.cancel()
        afModeJob = null
        captureController.reset()
        appControlsCamera = true
        monitoringPausedByRecording = false
        captureFlashUntil = 0
        afFrame = null
        afAreaMode = null
        afServoMode = null
        lastAfImageWidth = 0
        lastAfImageHeight = 0
        lastJpegWidth = 0
        lastJpegHeight = 0
        lastHeaderLayout = null
        pendingAfAim = null
        afAimReport = null
        lastAfAreaText = null
        unknownAfGridWarned = false
        lastExposureProgram = null
        cameraControls = emptyList()
        statusHudBattery = null
        statusHudRemaining = null
        histogramBins = emptyList()
        cameraControlJob?.cancel()
        cameraControlJob = null
    }

    private fun startRecordingTicker() {
        stopRecordingTicker()
        recordingTickerJob = viewModelScope.launch {
            while (isActive && captureController.recording) {
                publish()
                delay(RECORDING_TICK_MS)
            }
        }
    }

    private fun stopRecordingTicker() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (FocusSettings) -> FocusSettings) {
        settingsRepository.update(transform = transform)
        val updated = settingsRepository.current
        stateMachine.updateSettings(updated)
        analyzer.targetWidth = updated.analysisWidth
        // Only affects the next LiveView start; the camera keeps whatever it has now.
        viewModelScope.launch {
            runCatching {
                usbManager.withCameraOrNull { it.takeControlOnLiveView = updated.takeCameraControl }
            }
        }
        publish()
    }

    fun resetSettings() {
        settingsRepository.resetToDefaults()
        val updated = settingsRepository.current
        stateMachine.updateSettings(updated)
        analyzer.targetWidth = updated.analysisWidth
        publish(info = "Einstellungen zurueckgesetzt")
    }

    fun dismissMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, warningMessage = null)
        stateMachine.clearError()
        publish()
    }

    // ------------------------------------------------------------------ frame loop

    private fun startFrameLoop() {
        stopFrameLoop()
        frameJob = viewModelScope.launch(Dispatchers.Default) {
            consecutiveEmptyFrames = 0
            while (isActive) {
                val loopStart = SystemClock.elapsedRealtime()
                val currentSettings = settingsRepository.current

                try {
                    processOneFrame(currentSettings)
                } catch (e: CameraException) {
                    Log.w(TAG, "frame loop error: ${e.error.message}")
                    handleFatal(e.error)
                    return@launch
                } catch (t: Throwable) {
                    Log.e(TAG, "frame loop crashed", t)
                    handleFatal(CameraError.from(t))
                    return@launch
                }

                stateMachine.tick()

                val elapsed = SystemClock.elapsedRealtime() - loopStart
                val remaining = currentSettings.frameIntervalMs - elapsed
                if (remaining > 0) delay(remaining) else delay(1)
            }
        }
    }

    private fun stopFrameLoop() {
        frameJob?.cancel()
        frameJob = null
    }

    private suspend fun processOneFrame(currentSettings: FocusSettings) {
        val raw = usbManager.withCameraOrNull { it.fetchFrame() }
        if (raw == null) {
            onEmptyFrame()
            publish()
            return
        }

        val decoded = liveViewProcessor.decode(raw)
        if (decoded == null) {
            onEmptyFrame()
            publish()
            return
        }

        consecutiveEmptyFrames = 0
        lastGoodFrameAt = SystemClock.elapsedRealtime()

        lastJpegWidth = decoded.bitmap.width
        lastJpegHeight = decoded.bitmap.height

        val result = analyzer.analyze(decoded.bitmap, currentSettings.measuringField)
        _preview.value = decoded.bitmap.asImageBitmap()

        updateFps()
        pushHistory(result.score.toFloat())

        analysisDurationMs = result.durationMs
        analysisResolution = "${result.analysisWidth}x${result.analysisHeight}"
        jpegSizeKb = decoded.jpegSize / 1024
        liveViewHeaderBytes = decoded.headerSize
        histogramBins = if (result.histogram.isEmpty()) emptyList() else result.histogram.toList()

        // The AF frame the camera itself reports, converted into fractions of the frame so
        // the overlay does not have to know anything about the LiveView resolution.
        afFrame = decoded.header?.let { header ->
            lastAfImageWidth = header.imageWidth
            lastAfImageHeight = header.imageHeight
            lastHeaderLayout = header.layout
            verifyAfAim(header)
            AfFrameOverlay(
                centerX = header.focusCenterXFraction,
                centerY = header.focusCenterYFraction,
                width = header.focusWidthFraction,
                height = header.focusHeightFraction,
                focused = header.focused
            )
        }

        val action = stateMachine.onFrameScore(result.score)

        if (action == FocusAction.TRIGGER_AUTOFOCUS) {
            runAutofocus(manual = false)
        } else {
            publish()
        }
    }

    private fun onEmptyFrame() {
        consecutiveEmptyFrames++
        val silentForMs = SystemClock.elapsedRealtime() - lastGoodFrameAt
        if (consecutiveEmptyFrames == EMPTY_FRAME_WARNING &&
            silentForMs > EMPTY_FRAME_WARNING_MS
        ) {
            _uiState.value = _uiState.value.copy(
                warningMessage = "Die Kamera liefert seit ${silentForMs / 1000} s keine " +
                    "verwertbaren LiveView-Bilder. Bei der D3400 passiert das, wenn der " +
                    "LiveView-Hebel nicht aktiv ist oder die Kamera in den Standby gegangen ist."
            )
        }
    }

    /** Runs the whole trigger -> wait -> cooldown sequence exactly once. */
    private suspend fun runAutofocus(manual: Boolean) {
        stateMachine.onAutofocusStarted()
        autofocusFlashUntil = SystemClock.elapsedRealtime() + AF_FLASH_MS
        publish(info = if (manual) "Manueller Autofokus ..." else "Unschaerfe erkannt - Autofokus")

        val settings = settingsRepository.current
        val field = settings.measuringField
        val aim = if (settings.manualFieldEnabled && field != null) {
            afAreaPixels(field.centerX, field.centerY)
        } else {
            null
        }
        if (settings.manualFieldEnabled && aim == null && capabilities?.changeAfArea == true) {
            warnUnknownAfGrid()
        }
        val outcome = focusController.triggerAutofocus(
            settings = settings,
            aimX = aim?.first,
            aimY = aim?.second
        )
        if (aim != null && field != null) expectAfFrameAt(field.centerX, field.centerY)
        if (settings.manualFieldEnabled) {
            refreshAfModes()
        }
        runCatching {
            usbManager.withCameraOrNull { camera ->
                lastExposureProgram = camera.readExposureProgram()
                camera.lastAfAreaReport?.let { lastAfAreaText = it }
            }
        }

        if (outcome.disableAutofocus) {
            stateMachine.onAutofocusUnsupported(outcome.warning ?: outcome.description)
        } else {
            stateMachine.onAutofocusFinished(outcome.description, outcome.counted)
        }

        // Whatever the camera did, the LiveView buffer now holds frames from during the AF
        // run. Drop them so they cannot influence the next decision.
        liveViewProcessor.resetStatistics()
        consecutiveEmptyFrames = 0
        lastGoodFrameAt = SystemClock.elapsedRealtime()

        publish(
            info = outcome.description,
            warning = outcome.warning
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun updateFps() {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameAt > 0) {
            val delta = (now - lastFrameAt).coerceAtLeast(1)
            val instant = 1000.0 / delta
            smoothedFps = if (smoothedFps == 0.0) instant else smoothedFps * 0.8 + instant * 0.2
        }
        lastFrameAt = now
    }

    private fun pushHistory(score: Float) {
        history.addLast(score)
        while (history.size > HISTORY_LENGTH) history.removeFirst()
    }

    /**
     * Folds the current machine state into [UiState].
     *
     * [error] and [warning] accept a String to set the banner, null to clear it and the
     * [KEEP] sentinel (the default) to leave it untouched - which is why they are typed
     * Any? rather than String?.
     */
    private fun publish(
        busy: Boolean? = null,
        connected: Boolean? = null,
        info: String? = null,
        error: Any? = KEEP,
        warning: Any? = KEEP
    ) {
        val previous = _uiState.value
        val snapshot = stateMachine.snapshot()
        _uiState.value = previous.copy(
            focus = snapshot,
            connected = connected ?: (usbManager.isConnected && snapshot.state != FocusState.DISCONNECTED),
            cameraModel = deviceInfo?.model,
            cameraFirmware = deviceInfo?.deviceVersion,
            capabilities = capabilities,
            frameSource = frameSource,
            fps = smoothedFps,
            analysisDurationMs = analysisDurationMs,
            analysisResolution = analysisResolution,
            jpegSizeKb = jpegSizeKb,
            liveViewHeaderBytes = liveViewHeaderBytes,
            droppedFrames = liveViewProcessor.droppedFrames,
            busy = busy ?: previous.busy,
            infoMessage = info ?: previous.infoMessage,
            errorMessage = if (error === KEEP) previous.errorMessage else error as String?,
            warningMessage = if (warning === KEEP) previous.warningMessage else warning as String?,
            autofocusFlashActive = SystemClock.elapsedRealtime() < autofocusFlashUntil,
            captureFlashActive = SystemClock.elapsedRealtime() < captureFlashUntil,
            scoreHistory = history.toList(),
            diagnostics = assembleDiagnostics(),
            captureAvailable = capabilities?.canCaptureStill == true &&
                !captureController.captureUnsupported,
            captureBusy = captureJob?.isActive == true || intervalController.session.value.active,
            movieAvailable = capabilities?.canRecordMovie == true &&
                !captureController.movieUnsupported,
            recording = captureController.recording,
            recordingElapsedMs = if (captureController.recording) {
                System.currentTimeMillis() - captureController.recordingStartedAt
            } else {
                0
            },
            appControlsCamera = appControlsCamera,
            afFrame = afFrame,
            afAreaMode = afAreaMode,
            afServoMode = afServoMode,
            canMoveAfArea = capabilities?.changeAfArea == true &&
                appControlsCamera && lastAfImageWidth > 0,
            canTapPreview = appControlsCamera &&
                (settingsRepository.current.manualFieldEnabled ||
                    (capabilities?.changeAfArea == true && lastAfImageWidth > 0)),
            manualField = manualFieldOverlay(),
            focusTargetSource = when {
                !usbManager.isConnected || !appControlsCamera -> FocusTargetSource.NONE
                afFrame != null -> FocusTargetSource.CAMERA_REPORTED
                settingsRepository.current.manualFieldEnabled -> FocusTargetSource.MANUAL_FIELD
                else -> FocusTargetSource.WHOLE_FRAME
            },
            cameraControls = cameraControls,
            batteryPercent = statusHudBattery,
            remainingImages = statusHudRemaining,
            histogram = histogramBins,
            interval = intervalController.session.value
        )
    }

    /**
     * The measuring field as a preview overlay. Height is derived from the LiveView aspect
     * so the field is square on the sensor rather than stretched with the frame.
     */
    private fun manualFieldOverlay(): AfFrameOverlay? {
        val current = settingsRepository.current
        if (!current.manualFieldEnabled) return null
        val field = current.measuringField ?: return null
        val width = _preview.value?.width ?: 0
        val height = _preview.value?.height ?: 0
        val heightFraction = if (width > 0 && height > 0) {
            field.heightFraction(width, height)
        } else {
            field.size
        }
        return AfFrameOverlay(
            centerX = field.centerX,
            centerY = field.centerY,
            width = field.size,
            height = heightFraction,
            focused = stateMachine.snapshot().isSharp
        )
    }

    private fun assembleDiagnostics(): String? {
        val base = diagnosticsText ?: return null
        return buildString {
            append(base)
            afFieldDiagnostics()?.let { append("\n--- AF-Feld / LiveView-Header ---\n").append(it) }
            movieDiagnosticsText?.let { append("\n--- Videostart abgelehnt ---\n").append(it) }
        }
    }

    /**
     * Everything needed to judge whether ChangeAfArea can work on this body: which header
     * layout was recognised, the grid the camera expects, what was last sent and what the
     * camera reported afterwards. Null before the first LiveView frame.
     */
    private fun afFieldDiagnostics(): String? {
        if (lastJpegWidth <= 0) return null
        return buildString {
            appendLine("LiveView-JPEG: ${lastJpegWidth}x$lastJpegHeight, Header $liveViewHeaderBytes Byte")
            appendLine(
                "Header-Layout: " + (lastHeaderLayout?.label ?: "NICHT erkannt - AF-Rahmen und " +
                    "ChangeAfArea-Koordinaten unbekannt")
            )
            if (lastAfImageWidth > 0) {
                appendLine("Gesamtbild (Koordinatenraum 0x9205): ${lastAfImageWidth}x$lastAfImageHeight")
            }
            appendLine("Letztes ChangeAfArea: " + (lastAfAreaText ?: "noch keins gesendet"))
            appendLine("Kontrolle AF-Rahmen: " + (afAimReport ?: "noch nicht geprueft"))
            appendLine(
                "Belichtungsprogramm 0x500E: " +
                    (lastExposureProgram?.let { PtpConstants.exposureProgramName(it) } ?: "n/v") +
                    " (kein Einfluss auf AfDrive/ChangeAfArea)"
            )
        }
    }

    private fun buildDiagnostics(info: PtpDeviceInfo): String {
        val caps = capabilities
        return buildString {
            appendLine("Hersteller: ${info.manufacturer}")
            appendLine("Modell: ${info.model}")
            appendLine("Firmware: ${info.deviceVersion}")
            appendLine("PTP-Standard: ${PtpConstants.hex16(info.standardVersion)}")
            appendLine("Vendor-Extension: ${PtpConstants.hex32(info.vendorExtensionId)} " +
                "(${info.vendorExtensionDesc})")
            appendLine("Unterstuetzte Operationen: ${info.operationsSupported.size}")
            appendLine(
                "Properties: ${info.devicePropertiesSupported.size} in DeviceInfo, " +
                    "${info.vendorPropertyCodes.size} via GetVendorPropCodes 0x90CA"
            )
            if (caps != null) {
                appendLine("  StartLiveView  0x9201: ${yesNo(caps.startLiveView)}")
                appendLine("  GetLiveViewImg 0x9203: ${yesNo(caps.getLiveViewImage)}")
                appendLine("  EndLiveView    0x9202: ${yesNo(caps.endLiveView)}")
                appendLine("  GetPreviewImg  0x9200: ${yesNo(caps.getPreviewImage)}")
                appendLine("  AfDrive        0x90C1: ${yesNo(caps.afDrive)}")
                appendLine("  AfDriveCancel  0x9206: ${yesNo(caps.afDriveCancel)}")
                appendLine("  DeviceReady    0x90C8: ${yesNo(caps.deviceReady)}")
                appendLine("  ChangeCamMode  0x90C2: ${yesNo(caps.changeCameraMode)}")
                appendLine("  ChangeAfArea   0x9205: ${yesNo(caps.changeAfArea)}")
                appendLine("  MfDrive        0x9204: ${yesNo(caps.mfDrive)}")
                appendLine("  CaptRecInMedia 0x9207: ${yesNo(caps.captureRecInMedia)}")
                appendLine("  CaptRecInSdram 0x90C0: ${yesNo(caps.captureRecInSdram)}")
                appendLine("  AfCaptureSDRAM 0x90CB: ${yesNo(caps.afCaptureSdram)}")
                appendLine("  InitiateCapture 0x100E: ${yesNo(caps.standardCapture)}")
                appendLine("  StartMovieRec  0x920A: ${yesNo(caps.startMovie)}")
                appendLine("  EndMovieRec    0x920B: ${yesNo(caps.endMovie)}")
                appendLine("  OpenCapture    0x101C: ${yesNo(caps.openCapture)}")
                appendLine("  GetEvent       0x90C7: ${yesNo(caps.getEvent)}")
                appendLine("Bildquelle: ${caps.frameSource}")
                appendLine("Fernausloesung moeglich: ${yesNo(caps.canCaptureStill)}")
                appendLine("Videoaufnahme moeglich: ${yesNo(caps.canRecordMovie)}")
                appendLine("  LiveViewStatus   0xD1A2: ${yesNo(caps.hasLiveViewStatusProp)}")
                appendLine("  RecordingMedia   0xD10B: ${yesNo(caps.hasRecordingMediaProp)}")
                appendLine("  ApplicationMode  0xD1F0: ${yesNo(caps.hasApplicationModeProp)}")
                appendLine("  MovRecProhibit   0xD0A4: ${yesNo(caps.hasMovieProhibitProp)}")
                appendLine("  AF-Messfeldmodus 0xD05D: ${yesNo(caps.hasAfAreaModeProp)}")
                appendLine("  AF-Betriebsart   0xD061: ${yesNo(caps.hasAfServoModeProp)}")
                appendLine("  AF-Feld bewegen  0x9205: ${yesNo(caps.changeAfArea)}")
                appendLine("  BatteryLevel     0x5001: ${yesNo(info.hasProperty(PtpConstants.DPC_BATTERY_LEVEL))}")
                appendLine("  ExposureTime     0x500D: ${yesNo(info.hasProperty(PtpConstants.DPC_EXPOSURE_TIME))}")
                appendLine("  FNumber          0x5007: ${yesNo(info.hasProperty(PtpConstants.DPC_F_NUMBER))}")
                appendLine("  ExposureIndex    0x500F: ${yesNo(info.hasProperty(PtpConstants.DPC_EXPOSURE_INDEX))}")
                appendLine("  ExposureBias     0x5010: ${yesNo(info.hasProperty(PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION))}")
                appendLine("  WhiteBalance     0x5005: ${yesNo(info.hasProperty(PtpConstants.DPC_WHITE_BALANCE))}")
                appendLine("  GetStorageInfo   0x1005: ${yesNo(info.supports(PtpConstants.OC_GET_STORAGE_INFO))}")
            }
            appendLine("Alle Opcodes: ${info.operationsAsHex()}")
        }
    }

    private fun yesNo(value: Boolean) = if (value) "ja" else "NEIN"

    override fun onCleared() {
        super.onCleared()
        stopFrameLoop()
        stopRecordingTicker()
        intervalController.requestCancel()
        IntervalCaptureService.stop(getApplication())
        usbManager.release()
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val HISTORY_LENGTH = 150
        private const val AF_FLASH_MS = 1_800L
        private const val EMPTY_FRAME_WARNING = 25
        private const val EMPTY_FRAME_WARNING_MS = 4_000L
        private const val RECORDING_TICK_MS = 500L

        /** How long a ChangeAfArea request waits for a header frame to confirm it. */
        private const val AF_AIM_CHECK_MS = 4_000L

        /** Extra slack on top of the field / frame half size, in image fractions. */
        private const val AF_AIM_TOLERANCE = 0.06f

        /** Sentinel so publish() can tell "clear this banner" from "leave it alone". */
        private val KEEP = Any()
    }
}
