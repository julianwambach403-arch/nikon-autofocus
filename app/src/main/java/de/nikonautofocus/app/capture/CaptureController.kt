package de.nikonautofocus.app.capture

import android.util.Log
import de.nikonautofocus.app.usb.CameraError
import de.nikonautofocus.app.usb.CameraException
import de.nikonautofocus.app.usb.CaptureResult
import de.nikonautofocus.app.usb.CaptureTarget
import de.nikonautofocus.app.usb.MovieResult
import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.UsbPtpManager

/** Result of a shutter release, already translated for the UI. */
data class CaptureOutcome(
    val success: Boolean,
    val description: String,
    /** Non null when the user should see a banner. */
    val warning: String?,
    /** Set when the app must stop offering the shutter button. */
    val disableCapture: Boolean
)

/** Result of a movie start/stop, already translated for the UI. */
data class MovieOutcome(
    val recording: Boolean,
    val description: String,
    val warning: String?,
    val disableMovie: Boolean,
    /** Full camera state readout when a start was refused, for the diagnostics card. */
    val diagnostics: String? = null
)

/**
 * Policy layer for shutter release and movie recording.
 *
 * Mirrors what [de.nikonautofocus.app.focus.FocusController] does for the autofocus: the
 * camera object performs the PTP work, this class decides what an answer means for the app
 * - in particular it latches "not supported" so an opcode the body rejects is never sent
 * again, and it phrases every failure in terms the user can act on.
 */
class CaptureController(private val usbManager: UsbPtpManager) {

    var captureUnsupported: Boolean = false
        private set

    var movieUnsupported: Boolean = false
        private set

    var recording: Boolean = false
        private set

    /** Wall clock start of the running recording, for the elapsed time display. */
    var recordingStartedAt: Long = 0L
        private set

    suspend fun capturePhoto(target: CaptureTarget): CaptureOutcome {
        if (captureUnsupported) {
            return CaptureOutcome(
                success = false,
                description = "Fernausloesung nicht unterstuetzt",
                warning = null,
                disableCapture = true
            )
        }

        val result = try {
            usbManager.withCamera { camera -> camera.captureStill(target) }
        } catch (e: CameraException) {
            CaptureResult.Failed(e.error)
        } catch (t: Throwable) {
            CaptureResult.Failed(CameraError.from(t))
        }

        return when (result) {
            is CaptureResult.Released -> CaptureOutcome(
                success = true,
                description = "Foto ausgeloest (" +
                    PtpConstants.operationName(result.operation) + " -> " +
                    (if (result.target == CaptureTarget.CARD) "Speicherkarte" else "SDRAM") + ")",
                warning = null,
                disableCapture = false
            )

            is CaptureResult.Busy -> CaptureOutcome(
                success = false,
                description = "Kamera beschaeftigt",
                warning = "Ausloesen abgelehnt: ${result.detail}",
                disableCapture = false
            )

            is CaptureResult.Unsupported -> {
                captureUnsupported = true
                CaptureOutcome(
                    success = false,
                    description = "Fernausloesung nicht unterstuetzt",
                    warning = CameraError.CaptureUnsupported.message,
                    disableCapture = true
                )
            }

            is CaptureResult.Failed -> {
                Log.w(TAG, "capture failed: ${result.error.message}")
                CaptureOutcome(
                    success = false,
                    description = "Ausloesen fehlgeschlagen",
                    warning = result.error.message,
                    disableCapture = result.error.permanent
                )
            }
        }
    }

    suspend fun startRecording(): MovieOutcome {
        if (movieUnsupported) {
            return MovieOutcome(
                recording = false,
                description = "Videoaufnahme nicht unterstuetzt",
                warning = null,
                disableMovie = true
            )
        }

        val result = try {
            usbManager.withCamera { camera -> camera.startMovieRecording() }
        } catch (e: CameraException) {
            MovieResult.Failed(e.error)
        } catch (t: Throwable) {
            MovieResult.Failed(CameraError.from(t))
        }

        return translate(result, expectRecording = true)
    }

    suspend fun stopRecording(): MovieOutcome {
        val result = try {
            usbManager.withCamera { camera -> camera.stopMovieRecording() }
        } catch (e: CameraException) {
            MovieResult.Failed(e.error)
        } catch (t: Throwable) {
            MovieResult.Failed(CameraError.from(t))
        }

        return translate(result, expectRecording = false)
    }

    private fun translate(result: MovieResult, expectRecording: Boolean): MovieOutcome =
        when (result) {
            is MovieResult.Started -> {
                recording = true
                recordingStartedAt = System.currentTimeMillis()
                MovieOutcome(true, "Videoaufnahme laeuft", null, false)
            }

            is MovieResult.Stopped -> {
                recording = false
                MovieOutcome(false, "Videoaufnahme beendet", null, false)
            }

            is MovieResult.Prohibited -> {
                recording = false
                MovieOutcome(
                    recording = false,
                    description = "Videoaufnahme verweigert",
                    warning = CameraError.MovieProhibited(result.condition).message,
                    disableMovie = false
                )
            }

            is MovieResult.Busy -> MovieOutcome(
                recording = recording,
                description = "Kamera beschaeftigt",
                warning = "Videobefehl abgelehnt: ${result.detail}",
                disableMovie = false
            )

            is MovieResult.Rejected -> {
                recording = false
                Log.w(TAG, "movie start rejected:\n" + result.diagnostics.details())
                MovieOutcome(
                    recording = false,
                    description = "Videostart abgelehnt",
                    warning = CameraError.MovieRejected(result.diagnostics).message,
                    // Deliberately not latched: the refusal usually depends on the mode
                    // dial or on LiveView, both of which the user can change and retry.
                    disableMovie = false,
                    diagnostics = result.diagnostics.details()
                )
            }

            is MovieResult.Unsupported -> {
                movieUnsupported = true
                recording = false
                MovieOutcome(
                    recording = false,
                    description = "Videoaufnahme nicht unterstuetzt",
                    warning = CameraError.MovieUnsupported.message,
                    disableMovie = true
                )
            }

            is MovieResult.Failed -> {
                Log.w(TAG, "movie ${if (expectRecording) "start" else "stop"} failed")
                if (!expectRecording) recording = false
                MovieOutcome(
                    recording = recording,
                    description = if (expectRecording) {
                        "Videostart fehlgeschlagen"
                    } else {
                        "Videostopp fehlgeschlagen"
                    },
                    warning = result.error.message,
                    disableMovie = result.error.permanent
                )
            }
        }

    /** Called when the connection drops so a stale recording flag cannot survive. */
    fun reset() {
        recording = false
        recordingStartedAt = 0L
    }

    companion object {
        private const val TAG = "CaptureController"
    }
}
