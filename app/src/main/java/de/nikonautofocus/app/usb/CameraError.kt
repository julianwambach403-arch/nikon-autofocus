package de.nikonautofocus.app.usb

/**
 * User facing error catalogue. Every failure path in the USB/PTP layer maps onto exactly
 * one of these so the UI can show a specific, actionable German message instead of a
 * stack trace, and so the state machine can decide whether retrying makes sense.
 */
sealed class CameraError(
    val message: String,
    /** false = retrying the exact same operation can succeed, true = do not retry. */
    val permanent: Boolean
) {

    object NotFound : CameraError(
        "Keine Kamera gefunden. Nikon D3400 per USB-OTG anschliessen, einschalten und " +
            "im Kameramenu USB auf MTP/PTP stellen.",
        permanent = false
    )

    object PermissionDenied : CameraError(
        "USB-Berechtigung wurde verweigert. Ohne Zugriff auf das USB-Geraet kann die App " +
            "nicht mit der Kamera sprechen.",
        permanent = false
    )

    class ConnectionFailed(detail: String) : CameraError(
        "PTP-Verbindung fehlgeschlagen: $detail",
        permanent = false
    )

    class NotNikon(model: String) : CameraError(
        "Angeschlossenes Geraet meldet sich als \"$model\" und nutzt nicht die Nikon-" +
            "PTP-Erweiterung. LiveView und Autofokus sind damit nicht ansteuerbar.",
        permanent = true
    )

    class LiveViewUnsupported(detail: String) : CameraError(
        "LiveView nicht verfuegbar: $detail",
        permanent = true
    )

    class LiveViewProhibited(condition: Long) : CameraError(
        "Kamera verweigert LiveView. Grund: " + PtpConstants.describeProhibitCondition(condition),
        permanent = false
    )

    object AutofocusUnsupported : CameraError(
        "Autofokus-Befehl wird von dieser Kamera/Firmware nicht unterstuetzt " +
            "(PTP Nikon_AfDrive 0x90C1 -> OperationNotSupported). Automatische Ausloesung " +
            "wurde deaktiviert.",
        permanent = true
    )

    class CameraBusy(detail: String) : CameraError(
        "Kamera nimmt derzeit keine Befehle an: $detail",
        permanent = false
    )

    object CaptureUnsupported : CameraError(
        "Fernausloesung wird von dieser Kamera/Firmware nicht unterstuetzt. Keiner der " +
            "Ausloese-Opcodes (0x9207, 0x90CB, 0x90C0, 0x100E) wurde akzeptiert.",
        permanent = true
    )

    class CaptureStoreUnavailable(toCard: Boolean) : CameraError(
        if (toCard) {
            "Kamera meldet StoreNotAvailable: kein Speicherziel. Speicherkarte einlegen " +
                "bzw. pruefen, ob sie schreibgeschuetzt oder voll ist."
        } else {
            "Kamera meldet StoreNotAvailable fuer den internen Speicher. In den " +
                "Einstellungen auf Speicherkarte als Ziel umstellen."
        },
        permanent = false
    )

    object MovieUnsupported : CameraError(
        "Videoaufnahme per USB wird von dieser Kamera/Firmware nicht unterstuetzt " +
            "(Nikon_StartMovieRecInCard 0x920A fehlt in der Operationsliste).",
        permanent = true
    )

    class MovieProhibited(condition: Long) : CameraError(
        "Kamera verweigert die Videoaufnahme. Grund: " +
            PtpConstants.describeMovieProhibitCondition(condition),
        permanent = false
    )

    class MovieNotPossible(detail: String) : CameraError(
        "Videoaufnahme nicht moeglich: $detail",
        permanent = false
    )

    /**
     * The body listed the movie opcode but refused the start. The message names the most
     * likely cause taken from the camera state, not a guess.
     */
    class MovieRejected(diagnostics: MovieDiagnostics) : CameraError(
        buildString {
            append("Kamera lehnt den Videostart ab (")
            append(PtpConstants.responseName(diagnostics.lastResponseCode))
            append("). ")
            val cause = diagnostics.likelyCause()
            if (cause != null) {
                append("Wahrscheinliche Ursache: ")
                append(cause)
                append(" ")
            } else {
                append(
                    "Die Kamera meldet keine Sperrgruende, obwohl LiveView bereits im " +
                        "Application-Modus neu gestartet wurde. Pruefe: Moduswahlrad auf " +
                        "P/S/A/M, Speicherkarte eingelegt und nicht voll, Video-Einstellungen " +
                        "am Body nicht auf 'manuell'. "
                )
            }
            append("Vollstaendige Werte unter PTP-Diagnose.")
        },
        permanent = false
    )

    object Disconnected : CameraError(
        "USB-Verbindung getrennt.",
        permanent = false
    )

    object NotResponding : CameraError(
        "Kamera antwortet nicht mehr. Kamera aus- und wieder einschalten oder Kabel neu stecken.",
        permanent = false
    )

    class Protocol(operation: Int, responseCode: Int) : CameraError(
        "PTP-Fehler bei ${PtpConstants.operationName(operation)}: " +
            PtpConstants.responseName(responseCode),
        permanent = responseCode == PtpConstants.RC_OPERATION_NOT_SUPPORTED
    )

    class Unexpected(detail: String) : CameraError(
        "Unerwarteter Fehler: $detail",
        permanent = false
    )

    companion object {
        fun from(t: Throwable): CameraError = when (t) {
            is CameraException -> t.error
            is PtpException -> Protocol(t.operationCode, t.responseCode)
            is PtpTransportException -> ConnectionFailed(t.message ?: "USB-Transportfehler")
            else -> Unexpected(t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Carrier exception so [CameraError] can travel through normal Kotlin control flow. */
class CameraException(val error: CameraError) : Exception(error.message)
