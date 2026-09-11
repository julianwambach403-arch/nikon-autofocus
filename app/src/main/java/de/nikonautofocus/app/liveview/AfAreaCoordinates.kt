package de.nikonautofocus.app.liveview

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Maps a 0..1 point on the displayed LiveView JPEG onto the pixel grid that
 * [de.nikonautofocus.app.usb.PtpConstants.OC_NIKON_CHANGE_AF_AREA] expects.
 *
 * That grid is the **whole image size** from the LiveView header ([LiveViewHeader.imageWidth]
 * / [LiveViewHeader.imageHeight]) - the same numbers the camera uses to report its own AF
 * frame centre. digiCamControl (`LiveViewViewModel.SetFocusPos` -> `NikonBase.Focus(x, y)`)
 * scales tap coordinates by exactly `ImageWidth / ImageHeight` before sending 0x9205.
 *
 * Sending a point in a different grid (the ~640 px JPEG, or a guessed size) puts the
 * request out of range or into a corner; the D3400 then leaves its AF point at the
 * top-left default, which is exactly the symptom this helper exists to avoid. When the
 * header could not be parsed the grid is unknown and callers must **not** send anything.
 */
object AfAreaCoordinates {

    fun toAfAreaPixels(
        fractionX: Float,
        fractionY: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<Int, Int>? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val x = (fractionX.coerceIn(0f, 1f) * imageWidth).roundToInt()
            .coerceIn(1, imageWidth)
        val y = (fractionY.coerceIn(0f, 1f) * imageHeight).roundToInt()
            .coerceIn(1, imageHeight)
        return x to y
    }

    /**
     * Whether the AF frame the camera reports sits on the point that was requested.
     *
     * The camera snaps the request to its own AF grid and clamps it so the frame stays
     * inside the image, so a perfect match is not expected. [toleranceFraction] is
     * measured per axis on the 0..1 image scale.
     */
    fun reportedFrameMatches(
        requestedX: Float,
        requestedY: Float,
        reportedCenterX: Float,
        reportedCenterY: Float,
        toleranceFraction: Float
    ): Boolean =
        abs(requestedX - reportedCenterX) <= toleranceFraction &&
            abs(requestedY - reportedCenterY) <= toleranceFraction
}
