package de.nikonautofocus.app.liveview

import kotlin.math.roundToInt

/**
 * Maps a 0..1 point on the displayed LiveView JPEG onto the pixel grid that
 * [de.nikonautofocus.app.usb.PtpConstants.OC_NIKON_CHANGE_AF_AREA] expects.
 *
 * Nikon reports two sizes in the LiveView header: the JPEG at offset 0/2 and a larger
 * "whole image" grid at offset 4/6 (used only to interpret the AF-frame overlay).
 * ChangeAfArea uses the **JPEG** grid. Sending whole-image pixels (thousands) is out of
 * range on a ~640 px stream; the D3400 then leaves Spot AF at its default top-left corner.
 */
object AfAreaCoordinates {

    fun toLiveViewPixels(
        fractionX: Float,
        fractionY: Float,
        liveViewWidth: Int,
        liveViewHeight: Int
    ): Pair<Int, Int>? {
        if (liveViewWidth <= 0 || liveViewHeight <= 0) return null
        val x = (fractionX.coerceIn(0f, 1f) * liveViewWidth).roundToInt()
            .coerceIn(1, liveViewWidth)
        val y = (fractionY.coerceIn(0f, 1f) * liveViewHeight).roundToInt()
            .coerceIn(1, liveViewHeight)
        return x to y
    }
}
