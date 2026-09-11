package de.nikonautofocus.app.liveview

/**
 * Locates the JPEG inside a Nikon LiveView payload.
 *
 * A Nikon LiveView data phase is not a bare JPEG: it starts with a vendor header whose size
 * differs per body and firmware (8, 128 and 384 byte variants are all in the wild) and which
 * carries the LiveView frame size, the AF area rectangle and rotation flags. Rather than
 * hard coding a header length per model, the payload is scanned for the JPEG markers, which
 * is what libgphoto2 does as well and is the only approach that survives firmware changes.
 *
 * Two steps, in order:
 *  1. The first four bytes of several Nikon payloads are a little endian offset that points
 *     at the SOI marker. If that offset lands exactly on FF D8, it is trusted.
 *  2. Otherwise the payload is scanned forward for SOI (FF D8) and backwards for EOI (FF D9).
 *
 * This object is pure Kotlin so it can be unit tested without an Android device.
 */
object JpegExtractor {

    /** Start (inclusive) and end (exclusive) of the JPEG inside the payload. */
    data class Range(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    private val MARKER: Byte = 0xFF.toByte()
    private val SOI: Byte = 0xD8.toByte()
    private val EOI: Byte = 0xD9.toByte()

    /** Smallest payload that could plausibly contain a JPEG. */
    private const val MIN_JPEG_BYTES = 128

    fun find(data: ByteArray, length: Int = data.size): Range? {
        if (length < MIN_JPEG_BYTES) return null
        val limit = minOf(length, data.size)

        val hinted = offsetHint(data, limit)
        val start = if (hinted >= 0) hinted else scanForSoi(data, 0, limit)
        if (start < 0) return null

        val end = scanForEoi(data, start + 2, limit)
        // Some bodies truncate the final EOI on the last packet of a frame. A JPEG without
        // EOI still decodes (the decoder simply stops), so fall back to the payload end.
        val effectiveEnd = if (end > start) end else limit
        if (effectiveEnd - start < MIN_JPEG_BYTES) return null
        return Range(start, effectiveEnd)
    }

    /** Reads the leading uint32 and returns it when it points straight at an SOI marker. */
    private fun offsetHint(data: ByteArray, limit: Int): Int {
        if (limit < 8) return -1
        val offset = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
        if (offset < 0 || offset + 1 >= limit) return -1
        return if (data[offset] == MARKER && data[offset + 1] == SOI) offset else -1
    }

    private fun scanForSoi(data: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i < limit - 1) {
            if (data[i] == MARKER && data[i + 1] == SOI) return i
            i++
        }
        return -1
    }

    /** Returns the index just past the EOI marker, searching backwards from the payload end. */
    private fun scanForEoi(data: ByteArray, from: Int, limit: Int): Int {
        var i = limit - 2
        while (i >= from) {
            if (data[i] == MARKER && data[i + 1] == EOI) return i + 2
            i--
        }
        return -1
    }
}
