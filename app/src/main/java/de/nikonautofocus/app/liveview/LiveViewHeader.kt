package de.nikonautofocus.app.liveview

/**
 * Which of the two known Nikon LiveView header layouts a frame uses.
 *
 * Both layouts carry the same fields in the same order; the extended one simply has
 * eight extra bytes in front. digiCamControl uses [CLASSIC] for the D90 / D5000 /
 * D7000 / D5100 generation (`NikonBase`) and [EXTENDED] for D600 / D800 / D5200 /
 * D5300 / D5500 / D5600 / D3300 / **D3400** (`NikonD600Base`).
 */
enum class LiveViewHeaderLayout(val label: String, val shift: Int) {
    CLASSIC("klassisch (D90-Generation)", 0),
    EXTENDED("erweitert (D600/D3400-Generation, +8 Byte)", 8)
}

/**
 * The vendor header Nikon puts in front of the LiveView JPEG.
 *
 * Field offsets, relative to the layout's [LiveViewHeaderLayout.shift]. Note that unlike
 * PTP itself, this header is **big endian**.
 *
 *   offset  type  field
 *   +0      u16   LiveView image width  (width of the JPEG that follows)
 *   +2      u16   LiveView image height
 *   +4      u16   whole image width     (coordinate space of the AF frame **and of
 *                                        Nikon_ChangeAfArea 0x9205**)
 *   +6      u16   whole image height
 *   +16     u16   AF frame width
 *   +18     u16   AF frame height
 *   +20     u16   AF frame centre x
 *   +22     u16   AF frame centre y
 *   +29     u8    rotation (1 = -90 degrees, 2 = +90 degrees)
 *   +40     u8    focus state (1 = not focused)
 *   +60     u8    movie recording flag
 *
 * The header length itself varies per body and firmware (8, 64, 128 and 384 byte variants
 * exist), which is why the JPEG is located by marker scan and the header size is whatever
 * precedes it. Everything parsed here is validated against the decoded JPEG before it is
 * trusted - see [LiveViewHeaderParser.parse].
 */
data class LiveViewHeader(
    val layout: LiveViewHeaderLayout,
    val liveViewWidth: Int,
    val liveViewHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val focusFrameWidth: Int,
    val focusFrameHeight: Int,
    val focusX: Int,
    val focusY: Int,
    val focused: Boolean,
    val rotationDegrees: Int,
    val movieRecording: Boolean
) {
    /** AF frame centre as a fraction of the image width, 0..1. */
    val focusCenterXFraction: Float get() = focusX.toFloat() / imageWidth

    /** AF frame centre as a fraction of the image height, 0..1. */
    val focusCenterYFraction: Float get() = focusY.toFloat() / imageHeight

    val focusWidthFraction: Float get() = focusFrameWidth.toFloat() / imageWidth

    val focusHeightFraction: Float get() = focusFrameHeight.toFloat() / imageHeight
}

object LiveViewHeaderParser {

    /** Smallest header (relative to the layout shift) that still holds the movie byte. */
    private const val MIN_PARSABLE_FIELDS = 62

    /** Header length from which the extended layout is the more likely one. */
    private const val EXTENDED_LAYOUT_MIN_HEADER = 384

    /**
     * @param data       the whole LiveView payload
     * @param headerSize number of bytes before the JPEG (from the marker scan)
     * @param jpegWidth  width of the decoded JPEG, used to validate the parse
     * @param jpegHeight height of the decoded JPEG
     * @return the parsed header, or null when it matches neither documented layout.
     *
     * The validation is the important part. If a body uses a shorter header or a different
     * field order, the numbers read here are nonsense - and a nonsense AF frame drawn
     * confidently over the preview is worse than no frame at all. The strongest check is
     * that the LiveView width/height stored in the header must match the JPEG that follows.
     *
     * Bodies with a 384 byte header (the D3400 among them) are tried with the extended
     * layout first; everything else with the classic one. The other layout is the
     * fallback either way, so a body that does not follow the digiCamControl mapping
     * still gets a frame as long as one of the two layouts describes its JPEG.
     */
    fun parse(
        data: ByteArray,
        headerSize: Int,
        jpegWidth: Int,
        jpegHeight: Int
    ): LiveViewHeader? {
        if (headerSize > data.size) return null
        val order = if (headerSize >= EXTENDED_LAYOUT_MIN_HEADER) {
            listOf(LiveViewHeaderLayout.EXTENDED, LiveViewHeaderLayout.CLASSIC)
        } else {
            listOf(LiveViewHeaderLayout.CLASSIC, LiveViewHeaderLayout.EXTENDED)
        }
        for (layout in order) {
            parse(data, headerSize, jpegWidth, jpegHeight, layout)?.let { return it }
        }
        return null
    }

    private fun parse(
        data: ByteArray,
        headerSize: Int,
        jpegWidth: Int,
        jpegHeight: Int,
        layout: LiveViewHeaderLayout
    ): LiveViewHeader? {
        val s = layout.shift
        if (headerSize < s + MIN_PARSABLE_FIELDS) return null

        val liveViewWidth = u16(data, s + 0)
        val liveViewHeight = u16(data, s + 2)

        // The header must describe the image it is attached to.
        if (liveViewWidth != jpegWidth || liveViewHeight != jpegHeight) return null

        val imageWidth = u16(data, s + 4)
        val imageHeight = u16(data, s + 6)
        if (imageWidth <= 0 || imageHeight <= 0) return null

        val focusFrameWidth = u16(data, s + 16)
        val focusFrameHeight = u16(data, s + 18)
        val focusX = u16(data, s + 20)
        val focusY = u16(data, s + 22)

        // The AF frame has to sit inside the image and have a plausible size.
        if (focusFrameWidth <= 0 || focusFrameHeight <= 0) return null
        if (focusFrameWidth > imageWidth || focusFrameHeight > imageHeight) return null
        if (focusX <= 0 || focusY <= 0) return null
        if (focusX > imageWidth || focusY > imageHeight) return null

        val rotation = when (data[s + 29].toInt() and 0xFF) {
            1 -> -90
            2 -> 90
            else -> 0
        }

        return LiveViewHeader(
            layout = layout,
            liveViewWidth = liveViewWidth,
            liveViewHeight = liveViewHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focusFrameWidth = focusFrameWidth,
            focusFrameHeight = focusFrameHeight,
            focusX = focusX,
            focusY = focusY,
            // digiCamControl: a value of 1 means "not focused".
            focused = (data[s + 40].toInt() and 0xFF) != 1,
            rotationDegrees = rotation,
            movieRecording = (data[s + 60].toInt() and 0xFF) == 1
        )
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
