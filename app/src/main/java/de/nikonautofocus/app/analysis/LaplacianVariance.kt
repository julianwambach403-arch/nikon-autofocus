package de.nikonautofocus.app.analysis

/**
 * Sharpness metric:  score = variance( Laplacian( grayFrame ) )
 *
 * The Laplacian is an edge detector; its response is near zero on flat areas and large on
 * crisp edges. A blurred image has soft edges, so the spread (variance) of the Laplacian
 * response collapses. This is the standard "variance of Laplacian" focus measure.
 *
 * The 4-neighbour kernel is used:
 *
 *        0  1  0
 *        1 -4  1
 *        0  1  0
 *
 * It costs four adds per pixel instead of the eight of the 8-neighbour version and behaves
 * identically for focus detection, which matters when this runs on every LiveView frame.
 *
 * Pure Kotlin, no Android and no OpenCV dependency: for the frame sizes involved (a Nikon
 * LiveView frame is 640x424, downscaled to ~320 px wide before measuring) a hand written
 * loop over an IntArray runs in single digit milliseconds, so pulling in the ~10 MB OpenCV
 * native libraries would only make the APK bigger without making the app faster.
 */
object LaplacianVariance {

    /**
     * @param gray   grayscale samples, 0..255, row major
     * @param width  image width in samples, must be >= 3
     * @param height image height in samples, must be >= 3
     * @return population variance of the Laplacian response, or 0.0 for degenerate input
     */
    fun compute(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        if (gray.size < width * height) return 0.0

        var sum = 0L
        var sumOfSquares = 0L
        var count = 0

        for (y in 1 until height - 1) {
            val row = y * width
            val rowAbove = row - width
            val rowBelow = row + width
            for (x in 1 until width - 1) {
                val laplacian = 4 * gray[row + x] -
                    gray[rowAbove + x] -
                    gray[rowBelow + x] -
                    gray[row + x - 1] -
                    gray[row + x + 1]
                sum += laplacian.toLong()
                sumOfSquares += (laplacian.toLong() * laplacian.toLong())
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum.toDouble() / count
        val variance = (sumOfSquares.toDouble() / count) - (mean * mean)
        return if (variance > 0.0) variance else 0.0
    }
}
