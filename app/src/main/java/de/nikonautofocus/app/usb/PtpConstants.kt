package de.nikonautofocus.app.usb

/**
 * PTP / PIMA 15740 constants plus the Nikon vendor extension.
 *
 * All values are taken from the Nikon SDK opcode ranges as documented in libgphoto2
 * (camlibs/ptp2/ptp.h). They are stable across the whole Nikon DSLR line; whether an
 * individual body actually implements an opcode is decided at runtime from the
 * OperationsSupported array of the DeviceInfo dataset - see [PtpDeviceInfo].
 */
object PtpConstants {

    // ---------------------------------------------------------------- containers
    const val CONTAINER_TYPE_COMMAND = 1
    const val CONTAINER_TYPE_DATA = 2
    const val CONTAINER_TYPE_RESPONSE = 3
    const val CONTAINER_TYPE_EVENT = 4

    /** length + type + code + transactionId */
    const val CONTAINER_HEADER_SIZE = 12

    // ---------------------------------------------------------------- standard operations
    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_INITIATE_CAPTURE = 0x100E
    const val OC_GET_DEVICE_PROP_DESC = 0x1014
    const val OC_GET_DEVICE_PROP_VALUE = 0x1015
    const val OC_SET_DEVICE_PROP_VALUE = 0x1016

    /** Standard PTP open capture, 1 parameter: the transaction id of the initiate. */
    const val OC_TERMINATE_OPEN_CAPTURE = 0x1018

    /**
     * Standard PTP open capture, 2 parameters (storageId, objectFormatCode).
     * This is the vendor neutral way of starting a continuous recording and is used as a
     * fallback when the Nikon movie opcodes are refused.
     */
    const val OC_INITIATE_OPEN_CAPTURE = 0x101C

    // ---------------------------------------------------------------- Nikon vendor operations
    /** Capture into the internal SDRAM of the body. 1 parameter, no data. */
    const val OC_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM = 0x90C0

    /**
     * Runs the autofocus drive. No parameters, no data phase.
     *
     * This is the command this app uses to focus. It is the tethered equivalent of a
     * half press of the shutter release (AF-ON) and, unlike [OC_INITIATE_CAPTURE], it
     * never releases the shutter.
     */
    const val OC_NIKON_AF_DRIVE = 0x90C1

    /** Take or release remote control of the body. 1 parameter (1 = host controls). */
    const val OC_NIKON_CHANGE_CAMERA_MODE = 0x90C2

    /** Vendor event queue. No parameters, data in. */
    const val OC_NIKON_GET_EVENT = 0x90C7

    /**
     * Returns RC_OK when the camera is idle and RC_DEVICE_BUSY while it is still working
     * (mirror movement, AF drive, card write, ...). This is the "may I send a command now?"
     * probe used before and after every autofocus trigger.
     */
    const val OC_NIKON_DEVICE_READY = 0x90C8

    const val OC_NIKON_GET_VENDOR_PROP_CODES = 0x90CA
    const val OC_NIKON_AF_CAPTURE_SDRAM = 0x90CB

    /** Single still preview image. Fallback when LiveView is not offered by the body. */
    const val OC_NIKON_GET_PREVIEW_IMG = 0x9200
    const val OC_NIKON_START_LIVE_VIEW = 0x9201
    const val OC_NIKON_END_LIVE_VIEW = 0x9202
    const val OC_NIKON_GET_LIVE_VIEW_IMG = 0x9203

    /** Manual focus drive. 2 parameters: direction flag, step amount. */
    const val OC_NIKON_MF_DRIVE = 0x9204

    /** Moves the AF area inside the LiveView frame. 2 parameters: x, y. */
    const val OC_NIKON_CHANGE_AF_AREA = 0x9205

    /** Cancels a running AF drive. NOTE: this is NOT an autofocus trigger. */
    const val OC_NIKON_AF_DRIVE_CANCEL = 0x9206

    /**
     * Still capture. Two parameters: (af, target).
     * af     = 0xFFFFFFFE runs the autofocus first, 0xFFFFFFFF captures without AF.
     * target = 0 writes to the memory card, 1 writes into the internal SDRAM.
     * Some bodies (D3x and relatives) only accept a single parameter - see
     * [OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA] handling in NikonPtpCamera.
     */
    const val OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207

    /** Starts an internal movie recording onto the memory card. No parameters. */
    const val OC_NIKON_START_MOVIE_REC_IN_CARD = 0x920A

    /** Stops the internal movie recording. No parameters. */
    const val OC_NIKON_END_MOVIE_REC = 0x920B

    const val OC_NIKON_TERMINATE_CAPTURE = 0x920C

    /** Switches the body into "application mode". 1 parameter. Newer bodies only. */
    const val OC_NIKON_CHANGE_APPLICATION_MODE = 0x9435

    // ---------------------------------------------------------------- response codes
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_STORE_NOT_AVAILABLE = 0x2013
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_INVALID_PARAMETER = 0x201D

    const val RC_NIKON_HARDWARE_ERROR = 0xA001
    /** The AF drive ran but could not lock focus. Not a protocol failure. */
    const val RC_NIKON_OUT_OF_FOCUS = 0xA002
    const val RC_NIKON_CHANGE_CAMERA_MODE_FAILED = 0xA003
    const val RC_NIKON_INVALID_STATUS = 0xA004
    const val RC_NIKON_SET_PROPERTY_NOT_SUPPORTED = 0xA005
    const val RC_NIKON_SHUTTER_SPEED_BULB = 0xA008
    const val RC_NIKON_MIRROR_UP_SEQUENCE = 0xA009
    /** Command requires LiveView, but LiveView is not running. */
    const val RC_NIKON_NOT_LIVE_VIEW = 0xA00B
    const val RC_NIKON_MF_DRIVE_STEP_END = 0xA00C
    const val RC_NIKON_STORE_ERROR = 0xA021
    const val RC_NIKON_BULB_RELEASE_BUSY = 0xA200
    const val RC_NIKON_SILENT_RELEASE_BUSY = 0xA201

    // ---------------------------------------------------------------- events
    const val EC_OBJECT_ADDED = 0x4002
    const val EC_CAPTURE_COMPLETE = 0x400D
    const val EC_NIKON_OBJECT_ADDED_IN_SDRAM = 0xC101
    const val EC_NIKON_CAPTURE_COMPLETE_REC_IN_SDRAM = 0xC102
    const val EC_NIKON_MOVIE_RECORD_COMPLETE = 0xC108

    // ---------------------------------------------------------------- device properties
    /** 0 = memory card, 1 = internal SDRAM. */
    const val DPC_NIKON_RECORDING_MEDIA = 0xD10B

    /** Bitmask of reasons why a movie recording cannot be started. UINT32. */
    const val DPC_NIKON_MOV_REC_PROHIBIT_CONDITION = 0xD0A4

    /** UINT8. Newer bodies only; has to be reset to 0 after a movie recording. */
    const val DPC_NIKON_APPLICATION_MODE = 0xD1F0

    const val RECORDING_MEDIA_CARD = 0L
    const val RECORDING_MEDIA_SDRAM = 1L

    /**
     * [DPC_NIKON_LIVE_VIEW_SELECTOR]: still photography LiveView. Movie recording is
     * refused while this is set (MovRecProhibit bit 13).
     */
    const val LIVE_VIEW_SELECTOR_STILL = 0L

    /** [DPC_NIKON_LIVE_VIEW_SELECTOR]: movie LiveView. Required by StartMovieRecInCard. */
    const val LIVE_VIEW_SELECTOR_MOVIE = 1L

    /** Parameter 1 of [OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA]: capture without autofocus. */
    const val CAPTURE_WITHOUT_AF = -1 // 0xFFFFFFFF

    /** Parameter 1 of [OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA]: run autofocus first. */
    const val CAPTURE_WITH_AF = -2 // 0xFFFFFFFE

    const val DPC_NIKON_AUTOFOCUS_MODE = 0xD161
    const val DPC_NIKON_LIVE_VIEW_STATUS = 0xD1A2
    const val DPC_NIKON_LIVE_VIEW_IMAGE_ZOOM_RATIO = 0xD1A3
    const val DPC_NIKON_LIVE_VIEW_PROHIBIT_CONDITION = 0xD1A4
    const val DPC_NIKON_EXPOSURE_INDICATE_STATUS = 0xD1B1

    /**
     * LiveView AF area mode (0 face priority, 1 wide, 2 normal, 3 subject tracking,
     * 4 spot). Reported as INT8 on some bodies and UINT8 on others - identical on the wire
     * for the values used here.
     */
    const val DPC_NIKON_LIVE_VIEW_AF_AREA = 0xD05D

    /** LiveView servo mode (0 single, 1 continuous, 2 full time, 3/4 manual). */
    const val DPC_NIKON_LIVE_VIEW_AF_FOCUS = 0xD061

    /** Standard PTP exposure program (mode dial). UINT16. */
    const val DPC_EXPOSURE_PROGRAM_MODE = 0x500E

    /** LiveView operating mode. Read for diagnostics; meaning is body-specific. */
    const val DPC_NIKON_LIVE_VIEW_MODE = 0xD1A0

    /**
     * Still vs movie LiveView. 0 = photo, 1 = movie.
     *
     * Nikon Camera Control Pro and digiCamControl only enable REC when this is movie.
     * Bodies that expose the property typically only accept a write while LiveView is off.
     */
    const val DPC_NIKON_LIVE_VIEW_SELECTOR = 0xD1A6

    /** Movie capture mode. Read for diagnostics only. */
    const val DPC_NIKON_MOVIE_CAPTURE_MODE = 0xD304

    /** Labels for the standard exposure program property. */
    fun exposureProgramName(value: Long): String = when (value) {
        0x0001L -> "M (manuell)"
        0x0002L -> "P (Programmautomatik)"
        0x0003L -> "A (Blendenvorwahl)"
        0x0004L -> "S (Zeitvorwahl)"
        0x0005L -> "Creative"
        0x0006L -> "Action"
        0x0007L -> "Portrait"
        0x8010L -> "AUTO"
        0x8011L -> "Motiv: Portrait"
        0x8012L -> "Motiv: Landschaft"
        0x8013L -> "Motiv: Makro"
        0x8014L -> "Motiv: Sport"
        0x8015L -> "Motiv: Nachtportrait"
        0x8016L -> "Motiv: Nachtlandschaft"
        0x8017L -> "Motiv: Kinder"
        0x8018L -> "AUTO (ohne Blitz)"
        else -> hex16(value.toInt())
    }

    /** True for the modes in which Nikon allows tethered control (P/S/A/M). */
    fun isPsamMode(value: Long): Boolean = value in 0x0001L..0x0004L

    fun recordingMediaName(value: Long): String = when (value) {
        RECORDING_MEDIA_CARD -> "Speicherkarte"
        RECORDING_MEDIA_SDRAM -> "SDRAM (intern)"
        else -> value.toString()
    }

    fun liveViewSelectorName(value: Long): String = when (value) {
        LIVE_VIEW_SELECTOR_STILL -> "Foto-LiveView (0)"
        LIVE_VIEW_SELECTOR_MOVIE -> "Video-LiveView (1)"
        else -> "$value (" + hex32(value) + ")"
    }

    // ---------------------------------------------------------------- data type codes
    const val DTC_INT8 = 0x0001
    const val DTC_UINT8 = 0x0002
    const val DTC_INT16 = 0x0003
    const val DTC_UINT16 = 0x0004
    const val DTC_INT32 = 0x0005
    const val DTC_UINT32 = 0x0006

    /** Labels for [DPC_NIKON_LIVE_VIEW_AF_AREA]. */
    fun afAreaModeName(value: Long): String = when (value) {
        0L -> "Gesichtserkennung"
        1L -> "Grosses Messfeld"
        2L -> "Normales Messfeld"
        3L -> "Motivverfolgung"
        4L -> "Spot-Messfeld"
        else -> "Modus $value"
    }

    /** Labels for [DPC_NIKON_LIVE_VIEW_AF_FOCUS]. */
    fun afServoModeName(value: Long): String = when (value) {
        0L -> "AF-S (Einzel)"
        1L -> "AF-C (kontinuierlich)"
        2L -> "AF-F (permanent)"
        3L -> "MF (fest)"
        4L -> "MF (Auswahl)"
        else -> "Modus $value"
    }

    // ---------------------------------------------------------------- ids
    const val VENDOR_EXTENSION_NIKON = 0x0000000A
    const val USB_VENDOR_ID_NIKON = 0x04B0

    /** Session id used by the app. Any non zero value is legal. */
    const val SESSION_ID = 1

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RC_ACCESS_DENIED -> "AccessDenied"
        RC_STORE_NOT_AVAILABLE -> "StoreNotAvailable"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_INVALID_PARAMETER -> "InvalidParameter"
        RC_NIKON_HARDWARE_ERROR -> "Nikon_HardwareError"
        RC_NIKON_OUT_OF_FOCUS -> "Nikon_OutOfFocus"
        RC_NIKON_CHANGE_CAMERA_MODE_FAILED -> "Nikon_ChangeCameraModeFailed"
        RC_NIKON_INVALID_STATUS -> "Nikon_InvalidStatus"
        RC_NIKON_SET_PROPERTY_NOT_SUPPORTED -> "Nikon_SetPropertyNotSupported"
        RC_NIKON_SHUTTER_SPEED_BULB -> "Nikon_ShutterSpeedBulb"
        RC_NIKON_MIRROR_UP_SEQUENCE -> "Nikon_MirrorUpSequence"
        RC_NIKON_NOT_LIVE_VIEW -> "Nikon_NotLiveView"
        RC_NIKON_MF_DRIVE_STEP_END -> "Nikon_MfDriveStepEnd"
        RC_NIKON_STORE_ERROR -> "Nikon_StoreError"
        RC_NIKON_BULB_RELEASE_BUSY -> "Nikon_BulbReleaseBusy"
        RC_NIKON_SILENT_RELEASE_BUSY -> "Nikon_SilentReleaseBusy"
        else -> hex16(code)
    }

    fun operationName(code: Int): String = when (code) {
        OC_GET_DEVICE_INFO -> "GetDeviceInfo"
        OC_OPEN_SESSION -> "OpenSession"
        OC_CLOSE_SESSION -> "CloseSession"
        OC_GET_DEVICE_PROP_VALUE -> "GetDevicePropValue"
        OC_SET_DEVICE_PROP_VALUE -> "SetDevicePropValue"
        OC_NIKON_AF_DRIVE -> "Nikon_AfDrive"
        OC_NIKON_DEVICE_READY -> "Nikon_DeviceReady"
        OC_NIKON_START_LIVE_VIEW -> "Nikon_StartLiveView"
        OC_NIKON_END_LIVE_VIEW -> "Nikon_EndLiveView"
        OC_NIKON_GET_LIVE_VIEW_IMG -> "Nikon_GetLiveViewImg"
        OC_NIKON_GET_PREVIEW_IMG -> "Nikon_GetPreviewImg"
        OC_NIKON_CHANGE_CAMERA_MODE -> "Nikon_ChangeCameraMode"
        OC_NIKON_CHANGE_AF_AREA -> "Nikon_ChangeAfArea"
        OC_NIKON_AF_DRIVE_CANCEL -> "Nikon_AfDriveCancel"
        OC_NIKON_MF_DRIVE -> "Nikon_MfDrive"
        OC_INITIATE_CAPTURE -> "InitiateCapture"
        OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA -> "Nikon_InitiateCaptureRecInMedia"
        OC_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM -> "Nikon_InitiateCaptureRecInSdram"
        OC_NIKON_AF_CAPTURE_SDRAM -> "Nikon_AfCaptureSDRAM"
        OC_NIKON_START_MOVIE_REC_IN_CARD -> "Nikon_StartMovieRecInCard"
        OC_NIKON_END_MOVIE_REC -> "Nikon_EndMovieRec"
        OC_NIKON_CHANGE_APPLICATION_MODE -> "Nikon_ChangeApplicationMode"
        OC_NIKON_GET_EVENT -> "Nikon_GetEvent"
        else -> hex16(code)
    }

    fun hex16(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')

    fun hex32(value: Long): String = "0x" + value.toString(16).uppercase().padStart(8, '0')

    /**
     * Human readable decoding of the Nikon LiveViewProhibitCondition property (0xD1A4).
     * The bit meanings follow the checks libgphoto2 performs before enabling LiveView.
     */
    fun describeProhibitCondition(condition: Long): String {
        if (condition == 0L) return "keine"
        val reasons = mutableListOf<String>()
        fun bit(n: Int, text: String) {
            if (condition and (1L shl n) != 0L) reasons += text
        }
        bit(0, "Aufnahmemedium nicht verfuegbar")
        bit(2, "Sequenzfehler")
        bit(5, "Blendenring nicht auf kleinster Blende")
        bit(8, "Akku leer")
        bit(9, "TTL-Fehler")
        bit(15, "Aufnahmevorgang laeuft")
        bit(17, "Temperatur zu hoch")
        bit(20, "Speicherkarte nicht formatiert")
        bit(21, "Bulb-Warnung")
        bit(22, "Spiegelvorausloesung aktiv")
        bit(31, "Belichtungsprogramm ist nicht P/S/A/M")
        if (reasons.isEmpty()) reasons += "unbekannte Ursache"
        return reasons.joinToString(", ") + " (" + hex32(condition) + ")"
    }

    /**
     * Human readable decoding of Nikon MovRecProhibitCondition (0xD0A4) - the reasons a
     * body refuses to start an internal movie recording. Bit meanings follow libgphoto2.
     */
    fun describeMovieProhibitCondition(condition: Long): String {
        if (condition == 0L) return "keine"
        val reasons = mutableListOf<String>()
        fun bit(n: Int, text: String) {
            if (condition and (1L shl n) != 0L) reasons += text
        }
        bit(0, "keine Speicherkarte")
        bit(1, "Kartenfehler")
        bit(2, "Karte nicht formatiert")
        bit(3, "Karte voll")
        bit(9, "Puffer noch nicht geschrieben")
        bit(10, "Videoaufnahme laeuft bereits")
        bit(11, "Karte schreibgeschuetzt")
        bit(12, "LiveView-Lupe aktiv")
        bit(13, "LiveView steht auf Foto statt Video")
        bit(14, "Kamera nicht im Application-Modus")
        if (reasons.isEmpty()) reasons += "unbekannte Ursache"
        return reasons.joinToString(", ") + " (" + hex32(condition) + ")"
    }
}
