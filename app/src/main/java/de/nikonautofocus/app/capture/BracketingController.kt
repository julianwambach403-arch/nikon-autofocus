package de.nikonautofocus.app.capture

import android.util.Log
import de.nikonautofocus.app.usb.CaptureTarget
import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.UsbPtpManager
import kotlinx.coroutines.delay

data class BracketSeriesOutcome(
    val photos: Int,
    val aborted: Boolean,
    val warning: String?,
    val restored: Boolean
)

/**
 * Shoots one exposure series by writing shutter or compensation, releasing, then putting
 * the original value back — including on abort and on error.
 */
class BracketingController(
    private val usbManager: UsbPtpManager,
    private val captureController: CaptureController
) {

    suspend fun prepare(settings: BracketingSettings): BracketValidation {
        return try {
            usbManager.withCamera { camera ->
                BracketingPlan.validate(
                    settings = settings,
                    exposureProgram = camera.readExposureProgram(),
                    shutterDesc = camera.readPropertyDesc(PtpConstants.DPC_EXPOSURE_TIME),
                    compensationDesc = camera.readPropertyDesc(
                        PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION
                    )
                )
            }
        } catch (t: Throwable) {
            BracketValidation.Rejected("Kamera nicht erreichbar: ${t.message}")
        }
    }

    suspend fun shootSeries(
        plan: PreparedBracket,
        target: CaptureTarget,
        cancelled: () -> Boolean = { false }
    ): BracketSeriesOutcome {
        if (plan.shots.size == 1 && plan.shots.first().thirds == 0) {
            val outcome = captureWithBusyRetry(target)
            return BracketSeriesOutcome(
                photos = if (outcome.success) 1 else 0,
                aborted = !outcome.success,
                warning = outcome.warning,
                restored = true
            )
        }

        var photos = 0
        var warning: String? = null
        var aborted = false
        var restored = false
        try {
            for (shot in plan.shots) {
                if (cancelled()) {
                    aborted = true
                    warning = "Belichtungsreihe abgebrochen"
                    break
                }
                val setCode = try {
                    usbManager.withCamera { camera ->
                        camera.setCameraProperty(plan.propertyCode, shot.value, plan.dataType)
                    }
                } catch (t: Throwable) {
                    aborted = true
                    warning = "Wert ${shot.label} nicht gesetzt: ${t.message}"
                    break
                }
                if (setCode != PtpConstants.RC_OK) {
                    aborted = true
                    warning = "Wert ${shot.label} nicht gesetzt: " +
                        PtpConstants.responseName(setCode)
                    break
                }
                val outcome = captureWithBusyRetry(target)
                if (outcome.success) {
                    photos++
                } else {
                    aborted = true
                    warning = outcome.warning ?: outcome.description
                    break
                }
            }
        } finally {
            restored = restore(plan)
        }
        if (!restored && warning == null) {
            warning = "Urspruengliche Belichtung konnte nicht wiederhergestellt werden"
        }
        return BracketSeriesOutcome(
            photos = photos,
            aborted = aborted,
            warning = warning,
            restored = restored
        )
    }

    suspend fun restore(plan: PreparedBracket): Boolean {
        return try {
            val code = usbManager.withCamera { camera ->
                camera.setCameraProperty(plan.propertyCode, plan.originalValue, plan.dataType)
            }
            if (code != PtpConstants.RC_OK) {
                Log.w(TAG, "restore ${plan.propertyCode} -> " + PtpConstants.responseName(code))
            }
            code == PtpConstants.RC_OK
        } catch (t: Throwable) {
            Log.w(TAG, "restore failed", t)
            false
        }
    }

    private suspend fun captureWithBusyRetry(
        target: CaptureTarget,
        timeoutMs: Long = BUSY_RETRY_MS
    ): CaptureOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = captureController.capturePhoto(target)
        while (!last.success && !last.disableCapture && System.currentTimeMillis() < deadline) {
            val busy = last.warning?.contains("beschaeftigt", ignoreCase = true) == true ||
                last.description.contains("beschaeftigt", ignoreCase = true)
            if (!busy) return last
            Log.i(TAG, "DeviceBusy, warte und versuche erneut")
            delay(BUSY_POLL_MS)
            last = captureController.capturePhoto(target)
        }
        return last
    }

    companion object {
        private const val TAG = "BracketingController"
        private const val BUSY_RETRY_MS = 20_000L
        private const val BUSY_POLL_MS = 250L
    }
}
