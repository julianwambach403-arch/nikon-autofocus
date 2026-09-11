package de.nikonautofocus.app.focus

import android.util.Log
import de.nikonautofocus.app.usb.AutofocusResult
import de.nikonautofocus.app.usb.CameraError
import de.nikonautofocus.app.usb.CameraException
import de.nikonautofocus.app.usb.UsbPtpManager

/** What the caller should do with an autofocus attempt. */
data class AutofocusOutcome(
    val result: AutofocusResult,
    /** Short German text for the UI, e.g. "Fokus gefunden". */
    val description: String,
    /** Whether this attempt counts towards the "autofocus triggered" statistic. */
    val counted: Boolean,
    /** Set when the app must stop trying: the command is not supported at all. */
    val disableAutofocus: Boolean,
    /** Non null when the user should be shown a warning banner. */
    val warning: String?
)

/**
 * Sends the autofocus command and translates the camera answer into a policy decision.
 *
 * Two policies live here rather than in the state machine, because both depend on the
 * camera rather than on the measurement:
 *
 *  - An OperationNotSupported answer permanently disables automatic focusing. The command
 *    is never sent a second time, which is what the specification asks for.
 *  - Repeated DeviceBusy answers are counted. The D3400 reports busy for the whole duration
 *    of an internal SD movie recording, so after [BUSY_STREAK_WARNING] attempts the user is
 *    told what is most likely going on instead of the app silently failing forever.
 */
class FocusController(private val usbManager: UsbPtpManager) {

    private var busyStreak = 0

    /** Latched mirror of the camera side flag, so the UI can read it without USB access. */
    var autofocusUnsupported: Boolean = false
        private set

    suspend fun triggerAutofocus(
        settings: FocusSettings,
        aimX: Int? = null,
        aimY: Int? = null
    ): AutofocusOutcome {
        if (autofocusUnsupported) {
            return AutofocusOutcome(
                result = AutofocusResult.Unsupported,
                description = "Autofokus nicht unterstuetzt",
                counted = false,
                disableAutofocus = true,
                warning = null
            )
        }

        val result = try {
            usbManager.withCamera { camera ->
                camera.triggerAutofocus(
                    timeoutMs = settings.autofocusTimeoutMs,
                    aimX = aimX,
                    aimY = aimY,
                    aimManualField = settings.manualFieldEnabled
                )
            }
        } catch (e: CameraException) {
            AutofocusResult.Failed(e.error)
        } catch (t: Throwable) {
            AutofocusResult.Failed(CameraError.from(t))
        }

        return when (result) {
            is AutofocusResult.Focused -> {
                busyStreak = 0
                AutofocusOutcome(
                    result = result,
                    description = "Fokus gefunden",
                    counted = true,
                    disableAutofocus = false,
                    warning = null
                )
            }

            is AutofocusResult.OutOfFocus -> {
                busyStreak = 0
                AutofocusOutcome(
                    result = result,
                    description = "Autofokus ausgefuehrt, kein Fokus gefunden",
                    counted = true,
                    disableAutofocus = false,
                    warning = "Die Kamera konnte nicht scharfstellen. Objektiv auf AF/M pruefen, " +
                        "Motiv braucht Kontrast."
                )
            }

            is AutofocusResult.Busy -> {
                busyStreak++
                Log.w(TAG, "AF busy (streak=$busyStreak): ${result.detail}")
                AutofocusOutcome(
                    result = result,
                    description = "Kamera beschaeftigt",
                    // Not counted: no AF actually ran. The cooldown still applies so the
                    // camera is not polled aggressively.
                    counted = false,
                    disableAutofocus = false,
                    warning = if (busyStreak >= BUSY_STREAK_WARNING) {
                        "Die Kamera weist Fokusbefehle wiederholt ab (${result.detail}). " +
                            "Laeuft eine interne Videoaufnahme auf die Speicherkarte? " +
                            "Waehrend der Aufzeichnung blockiert die D3400 externe Fokusbefehle."
                    } else {
                        null
                    }
                )
            }

            is AutofocusResult.Unsupported -> {
                autofocusUnsupported = true
                AutofocusOutcome(
                    result = result,
                    description = "Autofokus nicht unterstuetzt",
                    counted = false,
                    disableAutofocus = true,
                    warning = CameraError.AutofocusUnsupported.message
                )
            }

            is AutofocusResult.Failed -> {
                AutofocusOutcome(
                    result = result,
                    description = "Autofokus fehlgeschlagen",
                    counted = false,
                    disableAutofocus = result.error.permanent,
                    warning = result.error.message
                )
            }
        }
    }

    fun reset() {
        busyStreak = 0
    }

    companion object {
        private const val TAG = "FocusController"
        private const val BUSY_STREAK_WARNING = 3
    }
}
