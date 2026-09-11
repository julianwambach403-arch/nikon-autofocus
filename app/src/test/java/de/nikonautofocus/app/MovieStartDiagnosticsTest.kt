package de.nikonautofocus.app

import de.nikonautofocus.app.usb.MovieDiagnostics
import de.nikonautofocus.app.usb.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The movie-start refusal path has no camera in unit tests, but the diagnostic text the
 * UI shows is derived here. These checks lock the mapping taken from digiCamControl
 * (bit 13 = wrong LiveView type) so a later edit cannot silently revert it.
 */
class MovieStartDiagnosticsTest {

    @Test
    fun `prohibit bit 13 is photo live view, matching digiCamControl`() {
        val text = PtpConstants.describeMovieProhibitCondition(1L shl 13)
        assertTrue(text, text.contains("Foto statt Video"))
    }

    @Test
    fun `selector labels match Nikon still vs movie live view`() {
        assertEquals(
            "Foto-LiveView (0)",
            PtpConstants.liveViewSelectorName(PtpConstants.LIVE_VIEW_SELECTOR_STILL)
        )
        assertEquals(
            "Video-LiveView (1)",
            PtpConstants.liveViewSelectorName(PtpConstants.LIVE_VIEW_SELECTOR_MOVIE)
        )
    }

    @Test
    fun `still live view is reported as the likely cause when prohibit is empty`() {
        val cause = diagnostics(liveViewSelector = 0L).likelyCause()
        assertTrue(cause, cause!!.contains("Foto"))
    }

    @Test
    fun `movie live view alone is not treated as a failure`() {
        assertEquals(null, diagnostics(liveViewSelector = 1L).likelyCause())
    }

    @Test
    fun `sdram destination is reported because 0x920A records to the card`() {
        val cause = diagnostics(
            liveViewSelector = 1L,
            recordingMedia = PtpConstants.RECORDING_MEDIA_SDRAM
        ).likelyCause()
        assertTrue(cause, cause!!.contains("SDRAM"))
    }

    @Test
    fun `details print the named selector instead of a raw integer`() {
        val details = diagnostics(liveViewSelector = 0L).details()
        assertTrue(details, details.contains("Foto-LiveView (0)"))
    }

    private fun diagnostics(
        liveViewStatus: Long? = 1L,
        movieProhibit: Long? = null,
        liveViewSelector: Long? = null,
        recordingMedia: Long? = PtpConstants.RECORDING_MEDIA_CARD
    ) = MovieDiagnostics(
        liveViewStatus = liveViewStatus,
        movieProhibit = movieProhibit,
        liveViewProhibit = null,
        exposureProgram = 0x0001L,
        recordingMedia = recordingMedia,
        liveViewSelector = liveViewSelector,
        liveViewMode = null,
        movieCaptureMode = null,
        applicationMode = null,
        hasStartMovie = true,
        hasEndMovie = true,
        hasOpenCapture = false,
        lastResponseCode = PtpConstants.RC_NIKON_INVALID_STATUS
    )
}
