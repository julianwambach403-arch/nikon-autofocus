package de.nikonautofocus.app.analysis

/**
 * Luma histogram over a grayscale buffer. 64 bins keep the overlay cheap to draw while
 * still showing clipped shadows and highlights the way tethered LiveView apps do.
 */
object LumaHistogram {
    const val BINS = 64

    fun compute(gray: IntArray, length: Int, into: IntArray = IntArray(BINS)): IntArray {
        require(into.size == BINS)
        into.fill(0)
        if (length <= 0) return into
        val limit = minOf(length, gray.size)
        for (i in 0 until limit) {
            val luma = gray[i].coerceIn(0, 255)
            into[luma * BINS / 256]++
        }
        return into
    }
}
