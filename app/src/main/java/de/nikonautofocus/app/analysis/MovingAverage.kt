package de.nikonautofocus.app.analysis

/**
 * Fixed size moving average over the last N sharpness scores.
 *
 * The raw Laplacian variance fluctuates by a few percent from frame to frame even on a
 * completely static scene (sensor noise, JPEG quantisation, auto exposure micro
 * adjustments). Deciding on the raw value would trigger the autofocus on noise, so every
 * decision in the app is taken on the smoothed value.
 *
 * [isWarm] reports whether the window has been filled yet. The state machine refuses to
 * make blur decisions before that, which is what prevents an autofocus run being fired
 * from a half filled window right after a cooldown.
 */
class MovingAverage(size: Int = DEFAULT_SIZE) {

    var size: Int = size.coerceIn(MIN_SIZE, MAX_SIZE)
        private set

    private var buffer = DoubleArray(this.size)
    private var writeIndex = 0
    private var filled = 0
    private var sum = 0.0

    val isWarm: Boolean get() = filled >= size

    val sampleCount: Int get() = filled

    val average: Double get() = if (filled == 0) 0.0 else sum / filled

    fun add(value: Double): Double {
        if (filled >= size) {
            sum -= buffer[writeIndex]
        } else {
            filled++
        }
        buffer[writeIndex] = value
        sum += value
        writeIndex = (writeIndex + 1) % size
        return average
    }

    /** Resizes the window, discarding the current contents. */
    fun resize(newSize: Int) {
        val clamped = newSize.coerceIn(MIN_SIZE, MAX_SIZE)
        if (clamped == size) return
        size = clamped
        buffer = DoubleArray(clamped)
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(buffer, 0.0)
        writeIndex = 0
        filled = 0
        sum = 0.0
    }

    companion object {
        const val DEFAULT_SIZE = 5
        const val MIN_SIZE = 1
        const val MAX_SIZE = 60
    }
}
