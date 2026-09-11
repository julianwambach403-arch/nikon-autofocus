package de.nikonautofocus.app.usb

/**
 * Value objects and (de)serialisation helpers for the PTP wire format.
 * PTP is little endian throughout.
 */

/** Result of one complete PTP transaction (command / optional data / response). */
class PtpResponse(
    val responseCode: Int,
    val parameters: IntArray,
    /** Payload of the data phase, or null when the operation had none. */
    val data: ByteArray?,
    /** Valid length inside [data]. May be shorter than data.size when a pooled buffer is used. */
    val dataLength: Int
) {
    val isOk: Boolean get() = responseCode == PtpConstants.RC_OK

    fun param(index: Int): Int = if (index < parameters.size) parameters[index] else 0

    override fun toString(): String =
        "PtpResponse(${PtpConstants.responseName(responseCode)}, params=${parameters.size}, data=$dataLength)"
}

/**
 * A PTP operation returned a response code other than OK.
 * [responseCode] is preserved so callers can react to specific conditions
 * (DeviceBusy, OutOfFocus, OperationNotSupported, ...) instead of parsing text.
 */
class PtpException(
    val operationCode: Int,
    val responseCode: Int,
    message: String? = null
) : Exception(
    message ?: "PTP ${PtpConstants.operationName(operationCode)} failed: " +
        PtpConstants.responseName(responseCode)
) {
    val isNotSupported: Boolean get() = responseCode == PtpConstants.RC_OPERATION_NOT_SUPPORTED
    val isBusy: Boolean
        get() = responseCode == PtpConstants.RC_DEVICE_BUSY ||
            responseCode == PtpConstants.RC_NIKON_BULB_RELEASE_BUSY ||
            responseCode == PtpConstants.RC_NIKON_SILENT_RELEASE_BUSY
}

/** Raised for transport level problems: cable pulled, timeouts, malformed containers. */
class PtpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The DeviceInfo dataset (PIMA 15740, 5.5.1). The interesting part for this app is
 * [operationsSupported]: it is the only reliable way to know whether a particular body
 * implements LiveView and the AF drive. Nikon entry level bodies differ noticeably here.
 */
data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: Set<Int>,
    val eventsSupported: Set<Int>,
    val devicePropertiesSupported: Set<Int>,
    val captureFormats: Set<Int>,
    val imageFormats: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    fun supports(operation: Int): Boolean = operationsSupported.contains(operation)
    fun hasProperty(property: Int): Boolean = devicePropertiesSupported.contains(property)

    val isNikon: Boolean
        get() = vendorExtensionId == PtpConstants.VENDOR_EXTENSION_NIKON.toLong() ||
            manufacturer.contains("Nikon", ignoreCase = true)

    fun operationsAsHex(): String =
        operationsSupported.sorted().joinToString(" ") { PtpConstants.hex16(it) }

    companion object {
        fun parse(data: ByteArray, length: Int): PtpDeviceInfo {
            val r = PtpReader(data, length)
            val standardVersion = r.readU16()
            val vendorExtensionId = r.readU32()
            val vendorExtensionVersion = r.readU16()
            val vendorExtensionDesc = r.readString()
            val functionalMode = r.readU16()
            val operations = r.readU16Array()
            val events = r.readU16Array()
            val deviceProps = r.readU16Array()
            val captureFormats = r.readU16Array()
            val imageFormats = r.readU16Array()
            val manufacturer = r.readString()
            val model = r.readString()
            val deviceVersion = r.readString()
            val serial = r.readString()
            return PtpDeviceInfo(
                standardVersion = standardVersion,
                vendorExtensionId = vendorExtensionId,
                vendorExtensionVersion = vendorExtensionVersion,
                vendorExtensionDesc = vendorExtensionDesc,
                functionalMode = functionalMode,
                operationsSupported = operations,
                eventsSupported = events,
                devicePropertiesSupported = deviceProps,
                captureFormats = captureFormats,
                imageFormats = imageFormats,
                manufacturer = manufacturer,
                model = model,
                deviceVersion = deviceVersion,
                serialNumber = serial
            )
        }
    }
}

/**
 * A DevicePropDesc dataset (PIMA 15740, 5.5.2).
 *
 * The app uses it to find out which values a property actually accepts, so the UI can offer
 * exactly the autofocus modes the connected body implements instead of guessing from the
 * model name and sending values the camera would reject.
 */
data class PtpDevicePropDesc(
    val propertyCode: Int,
    val dataType: Int,
    val writable: Boolean,
    val currentValue: Long,
    val defaultValue: Long,
    /** Allowed values when the property is an enumeration, empty otherwise. */
    val enumValues: List<Long>,
    val minimum: Long?,
    val maximum: Long?,
    val step: Long?
) {
    companion object {
        private const val FORM_NONE = 0
        private const val FORM_RANGE = 1
        private const val FORM_ENUM = 2

        /**
         * @return the parsed descriptor, or null when the property uses a data type this
         *         app has no reason to understand (strings, arrays). Returning null is
         *         deliberate: guessing at an unknown layout would misreport what a camera
         *         supports.
         */
        fun parse(data: ByteArray, length: Int): PtpDevicePropDesc? {
            val r = PtpReader(data, length)
            val propertyCode = r.readU16()
            val dataType = r.readU16()
            val getSet = r.readU8()
            if (!isScalar(dataType)) return null

            val defaultValue = readValue(r, dataType)
            val currentValue = readValue(r, dataType)

            val enumValues = mutableListOf<Long>()
            var minimum: Long? = null
            var maximum: Long? = null
            var step: Long? = null

            when (runCatching { r.readU8() }.getOrDefault(FORM_NONE)) {
                FORM_RANGE -> {
                    minimum = readValue(r, dataType)
                    maximum = readValue(r, dataType)
                    step = readValue(r, dataType)
                }

                FORM_ENUM -> {
                    val count = r.readU16()
                    for (i in 0 until count) enumValues.add(readValue(r, dataType))
                }

                else -> Unit
            }

            return PtpDevicePropDesc(
                propertyCode = propertyCode,
                dataType = dataType,
                writable = getSet == 1,
                currentValue = currentValue,
                defaultValue = defaultValue,
                enumValues = enumValues,
                minimum = minimum,
                maximum = maximum,
                step = step
            )
        }

        private fun isScalar(dataType: Int): Boolean = when (dataType) {
            PtpConstants.DTC_INT8, PtpConstants.DTC_UINT8,
            PtpConstants.DTC_INT16, PtpConstants.DTC_UINT16,
            PtpConstants.DTC_INT32, PtpConstants.DTC_UINT32 -> true

            else -> false
        }

        private fun readValue(r: PtpReader, dataType: Int): Long = when (dataType) {
            PtpConstants.DTC_INT8 -> r.readU8().toByte().toLong()
            PtpConstants.DTC_UINT8 -> r.readU8().toLong()
            PtpConstants.DTC_INT16 -> r.readU16().toShort().toLong()
            PtpConstants.DTC_UINT16 -> r.readU16().toLong()
            PtpConstants.DTC_INT32 -> r.readU32().toInt().toLong()
            else -> r.readU32()
        }
    }
}

/** Little endian reader over a PTP data payload. */
class PtpReader(private val buf: ByteArray, private val limit: Int = buf.size) {
    var position: Int = 0
        private set

    private fun require(n: Int) {
        if (position + n > limit) {
            throw PtpTransportException(
                "PTP dataset truncated: need $n bytes at $position, only $limit available"
            )
        }
    }

    fun readU8(): Int {
        require(1)
        return buf[position++].toInt() and 0xFF
    }

    fun readU16(): Int {
        require(2)
        val v = (buf[position].toInt() and 0xFF) or ((buf[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    fun readU32(): Long {
        require(4)
        val v = (buf[position].toLong() and 0xFF) or
            ((buf[position + 1].toLong() and 0xFF) shl 8) or
            ((buf[position + 2].toLong() and 0xFF) shl 16) or
            ((buf[position + 3].toLong() and 0xFF) shl 24)
        position += 4
        return v
    }

    /** AUINT16: u32 element count followed by that many u16 values. */
    fun readU16Array(): Set<Int> {
        val count = readU32()
        if (count <= 0 || count > 4096) {
            // Empty or implausible: consume nothing further and return empty.
            if (count <= 0) return emptySet()
            throw PtpTransportException("Implausible array length $count in PTP dataset")
        }
        val out = LinkedHashSet<Int>(count.toInt())
        for (i in 0 until count.toInt()) out.add(readU16())
        return out
    }

    /** PTP string: u8 character count (including the trailing NUL) then UTF-16LE. */
    fun readString(): String {
        val chars = readU8()
        if (chars == 0) return ""
        require(chars * 2)
        val sb = StringBuilder(chars)
        for (i in 0 until chars) {
            val c = readU16()
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }
}

/** Little endian writer for the few outgoing datasets the app needs. */
class PtpWriter(initialCapacity: Int = 32) {
    private var buf = ByteArray(initialCapacity)
    private var size = 0

    private fun ensure(n: Int) {
        if (size + n > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }
    }

    fun writeU8(v: Int): PtpWriter {
        ensure(1)
        buf[size++] = (v and 0xFF).toByte()
        return this
    }

    fun writeU16(v: Int): PtpWriter {
        ensure(2)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v shr 8) and 0xFF).toByte()
        return this
    }

    fun writeU32(v: Long): PtpWriter {
        ensure(4)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v shr 8) and 0xFF).toByte()
        buf[size++] = ((v shr 16) and 0xFF).toByte()
        buf[size++] = ((v shr 24) and 0xFF).toByte()
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
