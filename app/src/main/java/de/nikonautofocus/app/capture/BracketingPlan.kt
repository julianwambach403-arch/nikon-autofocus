package de.nikonautofocus.app.capture

import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.PtpDevicePropDesc
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

enum class BracketOrder {
    /** 0, then −, +, −2, +2, … */
    ZERO_MINUS_PLUS,

    /** −n … 0 … +n */
    MINUS_ZERO_PLUS
}

data class BracketingSettings(
    val enabled: Boolean = false,
    val count: Int = 3,
    val stepThirds: Int = 1,
    val order: BracketOrder = BracketOrder.ZERO_MINUS_PLUS
) {
    fun sanitized(): BracketingSettings = copy(
        count = if (count in COUNTS) count else 3,
        stepThirds = stepThirds.coerceIn(STEP_MIN, STEP_MAX)
    )

    val shotsPerSeries: Int get() = if (enabled) count else 1

    companion object {
        val COUNTS = listOf(3, 5, 7)
        const val STEP_MIN = 1
        const val STEP_MAX = 9
    }
}

enum class BracketDrive {
    SHUTTER,
    EXPOSURE_COMP
}

data class BracketShot(
    /** Offset in 1/3 EV units, 0 = no change. */
    val thirds: Int,
    val value: Long,
    val label: String
)

data class PreparedBracket(
    val drive: BracketDrive,
    val propertyCode: Int,
    val dataType: Int,
    val originalValue: Long,
    val shots: List<BracketShot>
)

sealed class BracketValidation {
    data class Ready(val plan: PreparedBracket, val warnings: List<String> = emptyList()) :
        BracketValidation()

    data class Rejected(val reason: String) : BracketValidation()
}

/**
 * Builds the EV-offset list and maps it onto shutter times or exposure compensation.
 *
 * The D3400 has no in-camera AE-bracketing; this is the entire implementation.
 */
object BracketingPlan {

    /** One third of an EV, in PTP thousandths. Nikon enumerations use 333 / 667 / 1000. */
    const val THIRD_MILLI = 333L

    /** PTP exposure-compensation range the D3400 advertises. */
    const val EV_LIMIT_MILLI = 5_000L

    /** Shortest shutter the D3400 offers, in PTP units (seconds × 10_000): 1/4000 s. */
    const val MIN_SHUTTER_PTP = 3L

    /** Longest non-bulb shutter: 30 s. */
    const val MAX_SHUTTER_PTP = 300_000L

    const val PROGRAM_MANUAL = 0x0001L
    const val PROGRAM_P = 0x0002L
    const val PROGRAM_A = 0x0003L
    const val PROGRAM_S = 0x0004L

    fun offsetsThirds(count: Int, stepThirds: Int, order: BracketOrder): List<Int> {
        val n = (count - 1) / 2
        val magnitudes = (1..n).toList()
        return when (order) {
            BracketOrder.MINUS_ZERO_PLUS -> {
                ((-n)..n).map { it * stepThirds }
            }

            BracketOrder.ZERO_MINUS_PLUS -> buildList {
                add(0)
                for (k in magnitudes) {
                    add(-k * stepThirds)
                    add(k * stepThirds)
                }
            }
        }
    }

    fun evLabel(thirds: Int): String {
        if (thirds == 0) return "0 EV"
        val ev = thirds / 3.0
        val sign = if (ev > 0) "+" else ""
        return "%s%.1f EV".format(sign, ev)
    }

    fun isBulb(shutterPtp: Long): Boolean =
        shutterPtp <= 0L || shutterPtp == 0xFFFFFFFFL || shutterPtp == 0x7FFFFFFFL

    /**
     * Applies an EV offset to a PTP shutter value (seconds × 10_000).
     * `newSeconds = currentSeconds * 2^(thirds/3)`.
     */
    fun shutterAfterEv(currentPtp: Long, thirds: Int): Double {
        val seconds = currentPtp / 10_000.0
        if (seconds <= 0.0) return 0.0
        return seconds * 2.0.pow(thirds / 3.0)
    }

    fun shutterPtpFromSeconds(seconds: Double): Long =
        (seconds * 10_000.0).roundToLong()

    fun nearestAllowed(target: Long, allowed: List<Long>): Long? {
        val usable = allowed.filter { !isBulb(it) }
        if (usable.isEmpty()) return null
        return usable.minBy { abs(it - target) }
    }

    fun compensationAfterEv(currentMilli: Long, thirds: Int): Long =
        currentMilli + thirds * THIRD_MILLI

    fun snapCompensation(target: Long, desc: PtpDevicePropDesc): Long? {
        if (desc.enumValues.isNotEmpty()) {
            val nearest = desc.enumValues.minBy { abs(it - target) }
            if (abs(nearest - target) > THIRD_MILLI) return null
            return nearest
        }
        val min = desc.minimum
        val max = desc.maximum
        val step = desc.step?.takeIf { it > 0 } ?: THIRD_MILLI
        if (min != null && max != null) {
            if (target < min || target > max) return null
            val snapped = min + ((target - min + step / 2) / step) * step
            return snapped.coerceIn(min, max)
        }
        return target
    }

    fun validate(
        settings: BracketingSettings,
        exposureProgram: Long?,
        shutterDesc: PtpDevicePropDesc?,
        compensationDesc: PtpDevicePropDesc?
    ): BracketValidation {
        val cfg = settings.sanitized()
        if (!cfg.enabled) {
            return BracketValidation.Ready(
                PreparedBracket(
                    drive = BracketDrive.EXPOSURE_COMP,
                    propertyCode = PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION,
                    dataType = PtpConstants.DTC_INT16,
                    originalValue = compensationDesc?.currentValue ?: 0L,
                    shots = listOf(BracketShot(0, compensationDesc?.currentValue ?: 0L, "0 EV"))
                )
            )
        }

        if (exposureProgram == null) {
            return BracketValidation.Rejected(
                "Belichtungsprogramm (0x500E) ist nicht lesbar. Ohne den Modus kann die " +
                    "Reihe nicht geplant werden."
            )
        }
        if (!PtpConstants.isPsamMode(exposureProgram)) {
            return BracketValidation.Rejected(
                "Belichtungsreihe ist in " +
                    PtpConstants.exposureProgramName(exposureProgram) +
                    " nicht moeglich. Belichtungskorrektur und Zeit lassen sich dort nicht " +
                    "setzen. Bitte P, S, A oder M waehlen."
            )
        }

        val thirds = offsetsThirds(cfg.count, cfg.stepThirds, cfg.order)
        return if (exposureProgram == PROGRAM_MANUAL) {
            validateShutter(thirds, shutterDesc)
        } else {
            validateCompensation(thirds, compensationDesc)
        }
    }

    private fun validateShutter(
        thirds: List<Int>,
        desc: PtpDevicePropDesc?
    ): BracketValidation {
        if (desc == null) {
            return BracketValidation.Rejected(
                "Verschlusszeit (0x500D) ist nicht lesbar. In M wird die Reihe ueber die Zeit gesetzt."
            )
        }
        if (!desc.writable) {
            return BracketValidation.Rejected(
                "Die Verschlusszeit ist gerade nicht schreibbar. In M muss sie sich " +
                    "per USB aendern lassen."
            )
        }
        val current = desc.currentValue
        if (isBulb(current)) {
            return BracketValidation.Rejected(
                "Belichtungsreihe in M ist bei Bulb nicht moeglich. Eine feste Zeit zwischen " +
                    "1/4000 s und 30 s waehlen."
            )
        }
        val allowed = desc.enumValues.ifEmpty {
            val min = (desc.minimum ?: MIN_SHUTTER_PTP).coerceAtLeast(MIN_SHUTTER_PTP)
            val max = (desc.maximum ?: MAX_SHUTTER_PTP).coerceAtMost(MAX_SHUTTER_PTP)
            CameraControlExpand.expand(min, max, desc.step, current)
        }
        val usableMin = allowed.filter { !isBulb(it) }.minOrNull() ?: MIN_SHUTTER_PTP
        val usableMax = allowed.filter { !isBulb(it) }.maxOrNull() ?: MAX_SHUTTER_PTP

        val shots = ArrayList<BracketShot>(thirds.size)
        for (offset in thirds) {
            val seconds = shutterAfterEv(current, offset)
            val target = shutterPtpFromSeconds(seconds)
            if (target < usableMin || target < MIN_SHUTTER_PTP) {
                return BracketValidation.Rejected(
                    "Die Reihe braucht ${evLabel(offset)} gegenueber " +
                        PtpConstants.exposureTimeName(current) +
                        ", das waere kuerzer als 1/4000 s."
                )
            }
            if (target > usableMax || target > MAX_SHUTTER_PTP) {
                return BracketValidation.Rejected(
                    "Die Reihe braucht ${evLabel(offset)} gegenueber " +
                        PtpConstants.exposureTimeName(current) +
                        ", das waere laenger als 30 s."
                )
            }
            val snapped = nearestAllowed(target, allowed)
                ?: return BracketValidation.Rejected("Keine passende Verschlusszeit in der Kameratabelle.")
            shots += BracketShot(offset, snapped, PtpConstants.exposureTimeName(snapped) + " (" + evLabel(offset) + ")")
        }
        return BracketValidation.Ready(
            PreparedBracket(
                drive = BracketDrive.SHUTTER,
                propertyCode = PtpConstants.DPC_EXPOSURE_TIME,
                dataType = desc.dataType,
                originalValue = current,
                shots = shots
            )
        )
    }

    private fun validateCompensation(
        thirds: List<Int>,
        desc: PtpDevicePropDesc?
    ): BracketValidation {
        if (desc == null) {
            return BracketValidation.Rejected(
                "Belichtungskorrektur (0x5010) ist nicht lesbar. In P, A und S wird die " +
                    "Reihe darueber gesetzt."
            )
        }
        if (!desc.writable) {
            return BracketValidation.Rejected(
                "Die Belichtungskorrektur ist gerade nicht schreibbar. In AUTO und den " +
                    "Motivprogrammen geht das nicht — P, S, A oder M waehlen."
            )
        }
        val current = desc.currentValue
        val shots = ArrayList<BracketShot>(thirds.size)
        for (offset in thirds) {
            val target = compensationAfterEv(current, offset)
            if (abs(target) > EV_LIMIT_MILLI) {
                return BracketValidation.Rejected(
                    "Die Reihe braucht ${evLabel(offset)} zusaetzlich zur aktuellen Korrektur " +
                        PtpConstants.exposureBiasName(current) +
                        ". Die D3400 erlaubt nur ±5 EV."
                )
            }
            val snapped = snapCompensation(target, desc)
                ?: return BracketValidation.Rejected(
                    "Die Korrektur ${evLabel(offset)} (" +
                        PtpConstants.exposureBiasName(target) +
                        ") liegt ausserhalb dessen, was die Kamera annimmt."
                )
            shots += BracketShot(
                offset,
                snapped,
                PtpConstants.exposureBiasName(snapped) + " (" + evLabel(offset) + ")"
            )
        }
        return BracketValidation.Ready(
            PreparedBracket(
                drive = BracketDrive.EXPOSURE_COMP,
                propertyCode = PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION,
                dataType = desc.dataType,
                originalValue = current,
                shots = shots
            )
        )
    }

    /** Sum of shutter times in the series, in milliseconds (compensation drive uses [baseMs] each). */
    fun estimatedDurationMs(plan: PreparedBracket, saveBufferMs: Long, baseShutterMs: Long): Long {
        val exposureMs = when (plan.drive) {
            BracketDrive.SHUTTER -> plan.shots.sumOf { shutter ->
                if (isBulb(shutter.value)) 0L else (shutter.value * 1_000L) / 10_000L
            }
            BracketDrive.EXPOSURE_COMP -> plan.shots.size * baseShutterMs
        }
        return exposureMs + saveBufferMs * plan.shots.size
    }
}

/** Local helper so bracketing does not depend on the UI catalog cap. */
internal object CameraControlExpand {
    fun expand(min: Long, max: Long, step: Long?, current: Long, cap: Int = 200): List<Long> {
        if (max < min) return listOf(current)
        val increment = if (step != null && step > 0) step else ((max - min) / 40).coerceAtLeast(1)
        val out = ArrayList<Long>(cap)
        var value = min
        while (value <= max && out.size < cap) {
            out += value
            val next = value + increment
            if (next <= value) break
            value = next
        }
        if (current !in out) {
            out += current
            out.sort()
        }
        return out
    }
}
