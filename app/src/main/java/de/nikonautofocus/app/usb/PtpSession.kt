package de.nikonautofocus.app.usb

import android.util.Log

/**
 * PTP transaction layer on top of [UsbPtpTransport].
 *
 * Implements the three phase PTP exchange:
 *   1. command container (host -> device)
 *   2. optional data container (either direction)
 *   3. response container (device -> host)
 *
 * A PTP device can only process one transaction at a time. This class is NOT thread safe
 * on purpose - serialising access is the job of the single PTP dispatcher used by
 * [NikonPtpCamera], which also guarantees that a LiveView poll can never interleave with
 * an autofocus command.
 */
class PtpSession(private val transport: UsbPtpTransport) {

    private val scratch = ByteArray(maxOf(transport.inputMaxPacketSize, 512))
    private val commandBuffer = ByteArray(PtpConstants.CONTAINER_HEADER_SIZE + 5 * 4)

    private var transactionId: Int = 0
    var sessionOpen: Boolean = false
        private set

    /**
     * Transaction id of the most recently sent command. TerminateOpenCapture needs the id
     * of the InitiateOpenCapture that started the recording.
     */
    var lastTransactionId: Int = 0
        private set

    /** Result of the last container header that was parsed. */
    private var lastLength: Long = 0
    private var lastType: Int = 0
    private var lastCode: Int = 0

    // ------------------------------------------------------------------ session handling

    fun openSession(timeoutMs: Int = DEFAULT_TIMEOUT) {
        transactionId = 0
        val response = transact(
            PtpConstants.OC_OPEN_SESSION,
            intArrayOf(PtpConstants.SESSION_ID),
            timeoutMs = timeoutMs
        )
        // A session that is already open is not an error for our purposes.
        if (!response.isOk && response.responseCode != PtpConstants.RC_SESSION_NOT_OPEN) {
            if (response.responseCode != RC_SESSION_ALREADY_OPEN) {
                throw PtpException(PtpConstants.OC_OPEN_SESSION, response.responseCode)
            }
        }
        sessionOpen = true
    }

    fun closeSession(timeoutMs: Int = DEFAULT_TIMEOUT) {
        if (!sessionOpen) return
        runCatching { transact(PtpConstants.OC_CLOSE_SESSION, intArrayOf(), timeoutMs = timeoutMs) }
        sessionOpen = false
    }

    // ------------------------------------------------------------------ high level helpers

    fun getDeviceInfo(timeoutMs: Int = DEFAULT_TIMEOUT): PtpDeviceInfo {
        val response = execute(PtpConstants.OC_GET_DEVICE_INFO, timeoutMs = timeoutMs)
        val data = response.data
            ?: throw PtpTransportException("GetDeviceInfo lieferte keine Daten")
        return PtpDeviceInfo.parse(data, response.dataLength)
    }

    /** Reads a device property. [dataType] is one of PtpConstants.DTC_*. */
    fun getDevicePropValue(property: Int, dataType: Int, timeoutMs: Int = DEFAULT_TIMEOUT): Long {
        val response = execute(
            PtpConstants.OC_GET_DEVICE_PROP_VALUE,
            intArrayOf(property),
            timeoutMs = timeoutMs
        )
        val data = response.data ?: return 0
        val reader = PtpReader(data, response.dataLength)
        return when (dataType) {
            PtpConstants.DTC_UINT8 -> reader.readU8().toLong()
            PtpConstants.DTC_UINT16 -> reader.readU16().toLong()
            else -> reader.readU32()
        }
    }

    /**
     * Reads the descriptor of a device property: current value plus the set of values the
     * body accepts. Returns null when the camera refuses the request or uses a data type
     * this app does not parse.
     */
    fun getDevicePropDesc(property: Int, timeoutMs: Int = DEFAULT_TIMEOUT): PtpDevicePropDesc? {
        val response = transact(
            PtpConstants.OC_GET_DEVICE_PROP_DESC,
            intArrayOf(property),
            timeoutMs = timeoutMs
        )
        if (!response.isOk) return null
        val data = response.data ?: return null
        return runCatching { PtpDevicePropDesc.parse(data, response.dataLength) }.getOrNull()
    }

    fun setDevicePropValue(
        property: Int,
        value: Long,
        dataType: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT
    ): PtpResponse {
        val writer = PtpWriter(8)
        when (dataType) {
            PtpConstants.DTC_UINT8 -> writer.writeU8(value.toInt())
            PtpConstants.DTC_UINT16 -> writer.writeU16(value.toInt())
            else -> writer.writeU32(value)
        }
        return transact(
            PtpConstants.OC_SET_DEVICE_PROP_VALUE,
            intArrayOf(property),
            dataOut = writer.toByteArray(),
            timeoutMs = timeoutMs
        )
    }

    // ------------------------------------------------------------------ transactions

    /** Runs a transaction and throws [PtpException] unless the camera answered OK. */
    fun execute(
        operationCode: Int,
        parameters: IntArray = EMPTY_PARAMS,
        dataOut: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT,
        reuseDataBuffer: ByteArray? = null
    ): PtpResponse {
        val response = transact(operationCode, parameters, dataOut, timeoutMs, reuseDataBuffer)
        if (!response.isOk) throw PtpException(operationCode, response.responseCode)
        return response
    }

    /**
     * Runs a transaction and returns the response as-is, including error codes.
     * Use this whenever a non-OK answer is a normal outcome (DeviceReady, AfDrive, ...).
     */
    fun transact(
        operationCode: Int,
        parameters: IntArray = EMPTY_PARAMS,
        dataOut: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT,
        reuseDataBuffer: ByteArray? = null
    ): PtpResponse {
        require(parameters.size <= 5) { "PTP allows at most 5 parameters" }
        val tid = transactionId++
        lastTransactionId = tid

        writeCommand(operationCode, parameters, tid, timeoutMs)
        if (dataOut != null) writeData(operationCode, dataOut, tid, timeoutMs)

        var dataBuffer: ByteArray? = null
        var dataLength = 0

        // Read containers until the response container shows up.
        var guard = 0
        while (true) {
            if (++guard > MAX_CONTAINERS) {
                throw PtpTransportException("Kamera sendet unerwartet viele PTP-Container")
            }
            val bytesInScratch = readContainerHeader(timeoutMs)
            when (lastType) {
                PtpConstants.CONTAINER_TYPE_DATA -> {
                    val payloadLength = (lastLength - PtpConstants.CONTAINER_HEADER_SIZE).toInt()
                    if (payloadLength < 0 || lastLength > MAX_DATA_BYTES) {
                        throw PtpTransportException("Unplausible PTP-Datenlaenge $lastLength")
                    }
                    val buffer = if (reuseDataBuffer != null && reuseDataBuffer.size >= payloadLength) {
                        reuseDataBuffer
                    } else {
                        ByteArray(payloadLength)
                    }
                    val already = minOf(
                        bytesInScratch - PtpConstants.CONTAINER_HEADER_SIZE,
                        payloadLength
                    ).coerceAtLeast(0)
                    if (already > 0) {
                        System.arraycopy(
                            scratch, PtpConstants.CONTAINER_HEADER_SIZE, buffer, 0, already
                        )
                    }
                    if (already < payloadLength) {
                        transport.readFully(buffer, already, payloadLength - already, timeoutMs)
                    }
                    dataBuffer = buffer
                    dataLength = payloadLength
                }

                PtpConstants.CONTAINER_TYPE_RESPONSE -> {
                    val paramCount =
                        ((lastLength - PtpConstants.CONTAINER_HEADER_SIZE) / 4).toInt()
                            .coerceIn(0, 5)
                    val params = IntArray(paramCount)
                    for (i in 0 until paramCount) {
                        val off = PtpConstants.CONTAINER_HEADER_SIZE + i * 4
                        if (off + 4 <= bytesInScratch) params[i] = readInt(scratch, off)
                    }
                    return PtpResponse(lastCode, params, dataBuffer, dataLength)
                }

                PtpConstants.CONTAINER_TYPE_EVENT -> {
                    // Asynchronous events can appear on the bulk pipe on some bodies. Ignore.
                    Log.d(TAG, "Ignoring PTP event ${PtpConstants.hex16(lastCode)}")
                }

                else -> throw PtpTransportException(
                    "Unbekannter PTP-Containertyp $lastType (code ${PtpConstants.hex16(lastCode)})"
                )
            }
        }
    }

    // ------------------------------------------------------------------ wire format

    private fun writeCommand(operationCode: Int, parameters: IntArray, tid: Int, timeoutMs: Int) {
        val length = PtpConstants.CONTAINER_HEADER_SIZE + parameters.size * 4
        writeInt(commandBuffer, 0, length)
        writeShort(commandBuffer, 4, PtpConstants.CONTAINER_TYPE_COMMAND)
        writeShort(commandBuffer, 6, operationCode)
        writeInt(commandBuffer, 8, tid)
        for (i in parameters.indices) {
            writeInt(commandBuffer, PtpConstants.CONTAINER_HEADER_SIZE + i * 4, parameters[i])
        }
        transport.write(commandBuffer, length, timeoutMs)
    }

    private fun writeData(operationCode: Int, payload: ByteArray, tid: Int, timeoutMs: Int) {
        val total = PtpConstants.CONTAINER_HEADER_SIZE + payload.size
        val out = ByteArray(total)
        writeInt(out, 0, total)
        writeShort(out, 4, PtpConstants.CONTAINER_TYPE_DATA)
        writeShort(out, 6, operationCode)
        writeInt(out, 8, tid)
        System.arraycopy(payload, 0, out, PtpConstants.CONTAINER_HEADER_SIZE, payload.size)
        transport.write(out, total, timeoutMs)
    }

    /**
     * Reads one packet and parses the container header from it.
     * @return number of valid bytes currently in [scratch].
     */
    private fun readContainerHeader(timeoutMs: Int): Int {
        var read: Int
        var attempts = 0
        do {
            read = transport.readOnce(scratch, 0, scratch.size, timeoutMs)
            if (read == 0 && ++attempts > MAX_EMPTY_PACKETS) {
                throw PtpTransportException("Kamera antwortet nicht (nur Leerpakete)")
            }
        } while (read == 0)

        if (read < PtpConstants.CONTAINER_HEADER_SIZE) {
            throw PtpTransportException("PTP-Container zu kurz ($read Byte)")
        }
        lastLength = readInt(scratch, 0).toLong() and 0xFFFFFFFFL
        lastType = readShort(scratch, 4)
        lastCode = readShort(scratch, 6)
        return read
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readShort(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val TAG = "PtpSession"
        private const val RC_SESSION_ALREADY_OPEN = 0x201E
        private const val MAX_CONTAINERS = 16
        private const val MAX_EMPTY_PACKETS = 8
        private const val MAX_DATA_BYTES = 32L * 1024 * 1024
        const val DEFAULT_TIMEOUT = 4000
        private val EMPTY_PARAMS = IntArray(0)
    }
}
