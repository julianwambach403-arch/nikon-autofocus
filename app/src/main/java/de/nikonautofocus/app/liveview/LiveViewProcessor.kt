package de.nikonautofocus.app.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.nikonautofocus.app.usb.RawFrame

/** A decoded LiveView frame plus the metadata the UI wants to show. */
class DecodedFrame(
    val bitmap: Bitmap,
    /** Size of the vendor header that preceded the JPEG, in bytes. */
    val headerSize: Int,
    /** Size of the JPEG payload, in bytes. */
    val jpegSize: Int,
    /** Wall clock time the frame was decoded. */
    val timestampMs: Long,
    /** Parsed vendor header, or null when it did not match the documented layout. */
    val header: LiveViewHeader?
)

/**
 * Turns a raw PTP LiveView payload into a Bitmap.
 *
 * Kept separate from both the USB layer and the analyser so the pipeline stays
 * "transport -> decode -> measure" with no hidden coupling.
 */
class LiveViewProcessor {

    private val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
        inMutable = false
    }

    /** Statistics for diagnostics; a permanently failing decode is a real symptom. */
    var decodedFrames: Long = 0
        private set
    var droppedFrames: Long = 0
        private set

    /** How many frames had a vendor header this app could actually make sense of. */
    var headerParsedFrames: Long = 0
        private set

    /**
     * @return the decoded frame, or null when the payload held no usable JPEG.
     *         A dropped frame is normal right after an autofocus run.
     */
    fun decode(frame: RawFrame): DecodedFrame? {
        val range = JpegExtractor.find(frame.data, frame.length)
        if (range == null) {
            droppedFrames++
            return null
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(frame.data, range.start, range.length, options)
        } catch (e: OutOfMemoryError) {
            droppedFrames++
            null
        }

        if (bitmap == null) {
            droppedFrames++
            return null
        }

        decodedFrames++
        val header = LiveViewHeaderParser.parse(
            data = frame.data,
            headerSize = range.start,
            jpegWidth = bitmap.width,
            jpegHeight = bitmap.height
        )
        if (header != null) headerParsedFrames++

        return DecodedFrame(
            bitmap = bitmap,
            headerSize = range.start,
            jpegSize = range.length,
            timestampMs = System.currentTimeMillis(),
            header = header
        )
    }

    fun resetStatistics() {
        decodedFrames = 0
        droppedFrames = 0
        headerParsedFrames = 0
    }
}
