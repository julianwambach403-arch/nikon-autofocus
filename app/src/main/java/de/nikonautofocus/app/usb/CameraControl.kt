package de.nikonautofocus.app.usb

/**
 * Tethered shooting controls that Camera Connect & Control style apps expose on LiveView:
 * shutter, aperture, ISO, exposure compensation, white balance, metering, drive and
 * quality. Each entry is built from the connected body's DevicePropDesc, so the UI never
 * offers a value the camera did not list.
 */
enum class CameraControlKind {
    PROGRAM,
    SHUTTER,
    APERTURE,
    ISO,
    EXPOSURE_COMP,
    WHITE_BALANCE,
    METERING,
    DRIVE,
    QUALITY,
    FLASH
}

data class CameraPropertyOption(
    val value: Long,
    val label: String
)

data class CameraPropertyState(
    val kind: CameraControlKind,
    val propertyCode: Int,
    val dataType: Int,
    val current: Long,
    val options: List<CameraPropertyOption>,
    val writable: Boolean
) {
    val title: String get() = CameraControlCatalog.title(kind)

    val currentLabel: String
        get() = options.firstOrNull { it.value == current }?.label
            ?: CameraControlCatalog.label(kind, current)

    /** Compact HUD chip, e.g. "1/125" or "ISO 400". */
    val chipLabel: String get() = CameraControlCatalog.chip(kind, current)
}

data class CameraStatusHud(
    val batteryPercent: Int?,
    val remainingImages: Long?,
    val freeSpaceBytes: Long?
)

object CameraControlCatalog {

    data class Spec(
        val kind: CameraControlKind,
        val propertyCode: Int
    )

    /** Display order of the LiveView exposure strip. */
    val SPECS: List<Spec> = listOf(
        Spec(CameraControlKind.PROGRAM, PtpConstants.DPC_EXPOSURE_PROGRAM_MODE),
        Spec(CameraControlKind.SHUTTER, PtpConstants.DPC_EXPOSURE_TIME),
        Spec(CameraControlKind.APERTURE, PtpConstants.DPC_F_NUMBER),
        Spec(CameraControlKind.ISO, PtpConstants.DPC_EXPOSURE_INDEX),
        Spec(CameraControlKind.EXPOSURE_COMP, PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION),
        Spec(CameraControlKind.WHITE_BALANCE, PtpConstants.DPC_WHITE_BALANCE),
        Spec(CameraControlKind.METERING, PtpConstants.DPC_EXPOSURE_METERING_MODE),
        Spec(CameraControlKind.DRIVE, PtpConstants.DPC_STILL_CAPTURE_MODE),
        Spec(CameraControlKind.QUALITY, PtpConstants.DPC_COMPRESSION_SETTING),
        Spec(CameraControlKind.FLASH, PtpConstants.DPC_FLASH_MODE)
    )

    fun title(kind: CameraControlKind): String = when (kind) {
        CameraControlKind.PROGRAM -> "Belichtungsmodus"
        CameraControlKind.SHUTTER -> "Verschlusszeit"
        CameraControlKind.APERTURE -> "Blende"
        CameraControlKind.ISO -> "ISO"
        CameraControlKind.EXPOSURE_COMP -> "Belichtungskorrektur"
        CameraControlKind.WHITE_BALANCE -> "Weissabgleich"
        CameraControlKind.METERING -> "Belichtungsmessung"
        CameraControlKind.DRIVE -> "Aufnahmebetrieb"
        CameraControlKind.QUALITY -> "Bildqualitaet"
        CameraControlKind.FLASH -> "Blitz"
    }

    fun label(kind: CameraControlKind, value: Long): String = when (kind) {
        CameraControlKind.PROGRAM -> PtpConstants.exposureProgramName(value)
        CameraControlKind.SHUTTER -> PtpConstants.exposureTimeName(value)
        CameraControlKind.APERTURE -> PtpConstants.fNumberName(value)
        CameraControlKind.ISO -> PtpConstants.isoName(value)
        CameraControlKind.EXPOSURE_COMP -> PtpConstants.exposureBiasName(value)
        CameraControlKind.WHITE_BALANCE -> PtpConstants.whiteBalanceName(value)
        CameraControlKind.METERING -> PtpConstants.meteringModeName(value)
        CameraControlKind.DRIVE -> PtpConstants.stillCaptureModeName(value)
        CameraControlKind.QUALITY -> PtpConstants.compressionSettingName(value)
        CameraControlKind.FLASH -> PtpConstants.flashModeName(value)
    }

    fun chip(kind: CameraControlKind, value: Long): String = when (kind) {
        CameraControlKind.PROGRAM -> programChip(value)
        CameraControlKind.ISO -> if (value <= 0L) "ISO Auto" else "ISO $value"
        CameraControlKind.EXPOSURE_COMP -> {
            val ev = value / 1000.0
            val sign = if (ev > 0) "+" else ""
            "%s%.1f".format(sign, ev)
        }
        else -> label(kind, value)
    }

    fun fromDescriptor(spec: Spec, descriptor: PtpDevicePropDesc): CameraPropertyState {
        val values = descriptor.enumValues.ifEmpty {
            expandRange(descriptor.minimum, descriptor.maximum, descriptor.step, descriptor.currentValue)
        }
        return CameraPropertyState(
            kind = spec.kind,
            propertyCode = spec.propertyCode,
            dataType = descriptor.dataType,
            current = descriptor.currentValue,
            options = values.distinct().map { CameraPropertyOption(it, label(spec.kind, it)) },
            writable = descriptor.writable
        )
    }

    /**
     * Turns a PTP range form into a pickable list. Capped so a 0..2^32 range cannot
     * freeze the UI; the current value is always included.
     */
    fun expandRange(
        minimum: Long?,
        maximum: Long?,
        step: Long?,
        current: Long,
        cap: Int = 80
    ): List<Long> {
        val min = minimum ?: return listOf(current)
        val max = maximum ?: return listOf(current)
        if (max < min) return listOf(current)
        val increment = if (step != null && step > 0) step else ((max - min) / 20).coerceAtLeast(1)
        val out = ArrayList<Long>(cap.coerceAtMost(81))
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
        if (max !in out) out += max
        return out
    }

    private fun programChip(value: Long): String = when (value) {
        0x0001L -> "M"
        0x0002L -> "P"
        0x0003L -> "A"
        0x0004L -> "S"
        0x8010L -> "AUTO"
        0x8018L -> "AUTO"
        else -> PtpConstants.exposureProgramName(value).substringBefore(' ')
    }
}
