package de.nikonautofocus.app.analysis

import android.graphics.Bitmap
import de.nikonautofocus.app.focus.MeasuringField

/** Result of measuring one frame. */
data class SharpnessResult(
    val score: Double,
    /** Width/height of the grid the Laplacian actually ran on. */
    val analysisWidth: Int,
    val analysisHeight: Int,
    /** Wall clock cost of the measurement in milliseconds. */
    val durationMs: Long,
    /** True when only a sub region of the frame was measured. */
    val restrictedToField: Boolean = false,
    /** 64-bin luma histogram of the analysed (possibly cropped) region. */
    val histogram: IntArray = IntArray(0)
)

/**
 * Converts a LiveView bitmap to grayscale and measures its sharpness.
 *
 * Performance notes - this runs on every frame, continuously:
 *  - all working arrays are allocated once and reused, so a steady stream produces no
 *    per-frame garbage beyond the decoded bitmap itself;
 *  - the frame is box-averaged down to [targetWidth] before the Laplacian. Averaging (as
 *    opposed to nearest-neighbour subsampling) is deliberate: it acts as a low pass filter
 *    and keeps sensor noise from being mistaken for edge detail, which is what otherwise
 *    makes the score jitter and triggers spurious autofocus runs.
 *
 * NOT thread safe - it is used from a single analysis coroutine.
 *
 * IMPORTANT for calibration: the absolute score depends on [targetWidth] and on the
 * LiveView resolution of the body. Changing the analysis resolution rescales the score, so
 * the threshold has to be re-tuned afterwards. The UI shows the live value for exactly
 * this reason.
 */
class SharpnessAnalyzer(targetWidth: Int = DEFAULT_TARGET_WIDTH) {

    var targetWidth: Int = targetWidth.coerceIn(MIN_TARGET_WIDTH, MAX_TARGET_WIDTH)
        set(value) {
            field = value.coerceIn(MIN_TARGET_WIDTH, MAX_TARGET_WIDTH)
        }

    private var pixels: IntArray = IntArray(0)
    private var gray: IntArray = IntArray(0)
    private var histogram: IntArray = IntArray(LumaHistogram.BINS)

    /**
     * @param field optional measuring field. When given, only that part of the frame is
     *        read and measured - which is both cheaper and more selective, because a busy
     *        background then no longer contributes to the score.
     */
    fun analyze(bitmap: Bitmap, field: MeasuringField? = null): SharpnessResult {
        val started = System.nanoTime()
        val frameWidth = bitmap.width
        val frameHeight = bitmap.height
        if (frameWidth < 8 || frameHeight < 8) {
            return SharpnessResult(0.0, 0, 0, 0)
        }

        // Region of interest in source pixels.
        var regionLeft = 0
        var regionTop = 0
        var regionWidth = frameWidth
        var regionHeight = frameHeight

        if (field != null) {
            val fieldWidth = (field.size * frameWidth).toInt()
                .coerceIn(MIN_FIELD_PIXELS, frameWidth)
            val fieldHeight = (field.heightFraction(frameWidth, frameHeight) * frameHeight)
                .toInt().coerceIn(MIN_FIELD_PIXELS, frameHeight)
            regionWidth = fieldWidth
            regionHeight = fieldHeight
            regionLeft = ((field.centerX * frameWidth) - fieldWidth / 2f).toInt()
                .coerceIn(0, frameWidth - fieldWidth)
            regionTop = ((field.centerY * frameHeight) - fieldHeight / 2f).toInt()
                .coerceIn(0, frameHeight - fieldHeight)
        }

        if (pixels.size < regionWidth * regionHeight) {
            pixels = IntArray(regionWidth * regionHeight)
        }
        bitmap.getPixels(
            pixels, 0, regionWidth, regionLeft, regionTop, regionWidth, regionHeight
        )

        // A small field must not be blown up to targetWidth - that would only interpolate.
        val effectiveTarget = minOf(targetWidth, regionWidth)
        val step = maxOf(1, regionWidth / effectiveTarget)
        val grayWidth = regionWidth / step
        val grayHeight = regionHeight / step
        if (grayWidth < 3 || grayHeight < 3) {
            return SharpnessResult(0.0, grayWidth, grayHeight, 0, field != null)
        }
        if (gray.size < grayWidth * grayHeight) gray = IntArray(grayWidth * grayHeight)

        toGrayDownscaled(regionWidth, step, grayWidth, grayHeight)

        val score = LaplacianVariance.compute(gray, grayWidth, grayHeight)
        LumaHistogram.compute(gray, grayWidth * grayHeight, histogram)
        val durationMs = (System.nanoTime() - started) / 1_000_000
        return SharpnessResult(
            score = score,
            analysisWidth = grayWidth,
            analysisHeight = grayHeight,
            durationMs = durationMs,
            restrictedToField = field != null,
            histogram = histogram.copyOf()
        )
    }

    /**
     * Box-averages [step] x [step] blocks of the ARGB source into the gray buffer.
     * Luma uses the integer approximation of Rec. 601:  Y = (77 R + 151 G + 28 B) >> 8.
     */
    private fun toGrayDownscaled(sourceWidth: Int, step: Int, grayWidth: Int, grayHeight: Int) {
        val blockPixels = step * step
        var target = 0
        for (gy in 0 until grayHeight) {
            val sourceRowStart = gy * step * sourceWidth
            for (gx in 0 until grayWidth) {
                val sourceColumn = gx * step
                var accumulator = 0
                var rowOffset = sourceRowStart + sourceColumn
                for (dy in 0 until step) {
                    var index = rowOffset
                    for (dx in 0 until step) {
                        val argb = pixels[index++]
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        accumulator += (77 * r + 151 * g + 28 * b) shr 8
                    }
                    rowOffset += sourceWidth
                }
                gray[target++] = accumulator / blockPixels
            }
        }
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 320
        const val MIN_TARGET_WIDTH = 120
        const val MAX_TARGET_WIDTH = 1280
        private const val MIN_FIELD_PIXELS = 24
    }
}
