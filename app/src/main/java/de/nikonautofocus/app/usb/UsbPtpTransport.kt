package de.nikonautofocus.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Raw bulk transport for PTP over USB.
 *
 * This is the lowest layer of the USB stack and is deliberately free of any PTP semantics:
 * it only claims the still image interface, locates the three endpoints and moves bytes.
 * Everything protocol related lives in [PtpSession] / [NikonPtpCamera], which keeps the
 * transport swappable (for example against a Wi-Fi/PTP-IP or a native implementation).
 */
class UsbPtpTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointOut: UsbEndpoint,
    private val endpointIn: UsbEndpoint,
    @Suppress("unused") private val endpointInterrupt: UsbEndpoint?
) {

    /** Largest single bulkTransfer. Larger requests are unreliable on some Android USB stacks. */
    private val chunkSize = 16384

    val inputMaxPacketSize: Int = endpointIn.maxPacketSize.coerceAtLeast(64)

    @Volatile
    private var closed = false

    /** Writes [length] bytes from [data], chunked. Throws on short write or error. */
    fun write(data: ByteArray, length: Int, timeoutMs: Int) {
        checkOpen()
        var offset = 0
        while (offset < length) {
            val toSend = minOf(chunkSize, length - offset)
            val sent = connection.bulkTransfer(endpointOut, data, offset, toSend, timeoutMs)
            if (sent < 0) {
                throw PtpTransportException(
                    "USB bulk OUT failed at offset $offset ($toSend bytes requested)"
                )
            }
            if (sent == 0) {
                throw PtpTransportException("USB bulk OUT stalled at offset $offset")
            }
            offset += sent
        }
    }

    /**
     * Reads up to [length] bytes into [dest] starting at [offset].
     * Returns the number of bytes read; 0 means a zero length packet was received.
     */
    fun readOnce(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        checkOpen()
        val toRead = minOf(chunkSize, length)
        val read = connection.bulkTransfer(endpointIn, dest, offset, toRead, timeoutMs)
        if (read < 0) {
            throw PtpTransportException("USB bulk IN failed (timeout or device gone)")
        }
        return read
    }

    /**
     * Reads exactly [length] bytes. Zero length packets (which the device emits whenever a
     * payload is an exact multiple of the endpoint packet size) are skipped rather than
     * treated as end of stream.
     */
    fun readFully(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        var done = 0
        var emptyReads = 0
        while (done < length) {
            val read = readOnce(dest, offset + done, length - done, timeoutMs)
            if (read == 0) {
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw PtpTransportException("USB bulk IN returned only empty packets")
                }
                continue
            }
            emptyReads = 0
            done += read
        }
    }

    /** Clears a stalled endpoint so the next transaction has a chance to succeed. */
    fun clearHalt() {
        // UsbDeviceConnection has no public clearFeature(); a control transfer does the job.
        // bmRequestType 0x02 (endpoint), bRequest 0x01 (CLEAR_FEATURE), wValue 0 (ENDPOINT_HALT).
        runCatching {
            connection.controlTransfer(0x02, 0x01, 0, endpointIn.address, null, 0, 500)
            connection.controlTransfer(0x02, 0x01, 0, endpointOut.address, null, 0, 500)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    private fun checkOpen() {
        if (closed) throw PtpTransportException("USB-Verbindung wurde bereits geschlossen")
    }

    companion object {
        private const val TAG = "UsbPtpTransport"
        private const val MAX_EMPTY_READS = 8

        /**
         * Finds the USB Still Image Capture interface (class 6 / subclass 1 / protocol 1).
         * Every Nikon DSLR set to "PTP" USB mode exposes exactly one.
         */
        fun findStillImageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.interfaceSubclass == 1 &&
                    intf.interfaceProtocol == 1
                ) {
                    return intf
                }
            }
            // Some bodies report the PTP interface with a vendor specific class while still
            // speaking plain PTP. Accept an interface that has the expected endpoint layout.
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return intf
            }
            return null
        }

        /**
         * Claims the interface and returns a ready to use transport.
         * @throws PtpTransportException if the endpoints are missing or the interface is
         *         held by another driver that refuses to release it.
         */
        fun open(connection: UsbDeviceConnection, usbInterface: UsbInterface): UsbPtpTransport {
            var epOut: UsbEndpoint? = null
            var epIn: UsbEndpoint? = null
            var epInterrupt: UsbEndpoint? = null

            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK ->
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            if (epIn == null) epIn = ep
                        } else {
                            if (epOut == null) epOut = ep
                        }

                    UsbConstants.USB_ENDPOINT_XFER_INT ->
                        if (ep.direction == UsbConstants.USB_DIR_IN && epInterrupt == null) {
                            epInterrupt = ep
                        }
                }
            }

            if (epOut == null || epIn == null) {
                throw PtpTransportException(
                    "USB-Schnittstelle besitzt keine Bulk-Endpunkte (in=$epIn, out=$epOut)"
                )
            }

            // force = true detaches the kernel/system MTP driver, which Android otherwise
            // attaches to every PTP device it enumerates.
            if (!connection.claimInterface(usbInterface, true)) {
                throw PtpTransportException(
                    "USB-Schnittstelle konnte nicht belegt werden. Meist haelt der Android-" +
                        "MTP-Dienst die Kamera noch. Kabel kurz abziehen und erneut versuchen."
                )
            }

            Log.i(TAG, "PTP interface claimed, maxPacket=${epIn.maxPacketSize}")
            return UsbPtpTransport(connection, usbInterface, epOut, epIn, epInterrupt)
        }
    }
}
