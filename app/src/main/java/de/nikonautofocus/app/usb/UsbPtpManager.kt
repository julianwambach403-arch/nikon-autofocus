package de.nikonautofocus.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Owns everything that is USB specific: enumeration, the runtime permission dance,
 * opening/closing the PTP session and the single I/O thread all camera traffic runs on.
 *
 * The rest of the app never touches android.hardware.usb - it only calls [withCamera].
 * Swapping this class for a PTP/IP or native implementation would not affect the
 * analysis, focus or UI layers.
 */
class UsbPtpManager(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * A PTP device processes exactly one transaction at a time, so every single call is
     * funnelled through this one thread. It also serialises LiveView polling against the
     * autofocus command for free.
     */
    private val ptpExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ptp-io").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    val ptpDispatcher: CoroutineDispatcher = ptpExecutor.asCoroutineDispatcher()

    @Volatile
    private var transport: UsbPtpTransport? = null

    @Volatile
    private var camera: NikonPtpCamera? = null

    @Volatile
    private var connectedDevice: UsbDevice? = null

    val isConnected: Boolean get() = camera != null

    private var permissionReceiver: BroadcastReceiver? = null
    private var hotplugReceiver: BroadcastReceiver? = null

    // ------------------------------------------------------------------ discovery

    /** Returns the first attached device that exposes a PTP still image interface. */
    fun findCamera(): UsbDevice? {
        val devices = usbManager.deviceList.values
        // Prefer an actual Nikon body if several PTP devices are attached.
        return devices.firstOrNull {
            it.vendorId == PtpConstants.USB_VENDOR_ID_NIKON &&
                UsbPtpTransport.findStillImageInterface(it) != null
        } ?: devices.firstOrNull { UsbPtpTransport.findStillImageInterface(it) != null }
    }

    fun describeDevice(device: UsbDevice): String {
        val name = device.productName ?: "USB-Geraet"
        val manufacturer = device.manufacturerName ?: ""
        val ids = "VID 0x%04X / PID 0x%04X".format(device.vendorId, device.productId)
        return listOf(manufacturer, name).filter { it.isNotBlank() }.joinToString(" ") + " ($ids)"
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    // ------------------------------------------------------------------ permission

    /**
     * Asks the user for access to [device]. Resumes with the user decision.
     *
     * The intent is explicitly addressed to our own package and the receiver is registered
     * as NOT_EXPORTED, which is the combination required from Android 14 onwards.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    unregisterPermissionReceiver()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            permissionReceiver = receiver
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pendingIntent)

            continuation.invokeOnCancellation { unregisterPermissionReceiver() }
        }
    }

    private fun unregisterPermissionReceiver() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    // ------------------------------------------------------------------ hotplug

    /** Registers attach/detach callbacks. Both fire on the main thread. */
    fun registerHotplug(onAttached: (UsbDevice) -> Unit, onDetached: (UsbDevice) -> Unit) {
        if (hotplugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(
                    intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                ) ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        if (UsbPtpTransport.findStillImageInterface(device) != null) {
                            onAttached(device)
                        }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (device.deviceName == connectedDevice?.deviceName) {
                            forceCloseQuietly()
                        }
                        onDetached(device)
                    }
                }
            }
        }
        hotplugReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregisterHotplug() {
        hotplugReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        hotplugReceiver = null
    }

    // ------------------------------------------------------------------ connection

    /**
     * Full connect sequence: open the USB device, claim the PTP interface, open a PTP
     * session and read DeviceInfo so the capability set is known before anything else runs.
     */
    suspend fun connect(device: UsbDevice): NikonPtpCamera = withContext(ptpDispatcher) {
        closeInternal()

        val usbInterface = UsbPtpTransport.findStillImageInterface(device)
            ?: throw CameraException(
                CameraError.ConnectionFailed(
                    "Das Geraet stellt keine PTP-Schnittstelle bereit (Klasse 6/1/1). " +
                        "Steht die Kamera im USB-Menue auf MTP/PTP?"
                )
            )

        val connection = usbManager.openDevice(device)
            ?: throw CameraException(
                CameraError.ConnectionFailed(
                    "USB-Geraet liess sich nicht oeffnen. Fehlt die USB-Berechtigung?"
                )
            )

        val newTransport = try {
            UsbPtpTransport.open(connection, usbInterface)
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw CameraException(CameraError.from(t))
        }

        try {
            val session = PtpSession(newTransport)

            // GetDeviceInfo is legal outside a session and is the cheapest liveness probe.
            var info = try {
                session.getDeviceInfo()
            } catch (t: PtpTransportException) {
                // A stale endpoint state from a previous app is the usual cause.
                newTransport.clearHalt()
                session.getDeviceInfo()
            }

            session.openSession()

            // Some bodies only report the full vendor operation set inside a session.
            runCatching { session.getDeviceInfo() }.getOrNull()?.let { inSession ->
                if (inSession.operationsSupported.size >= info.operationsSupported.size) {
                    info = inSession
                }
            }

            if (!info.isNikon) {
                throw CameraException(CameraError.NotNikon(info.model.ifBlank { "unbekannt" }))
            }

            // Nikon hides its 0xD0xx/0xD1xx properties behind 0x90CA. Without this query the
            // D3400 looks like it had neither LiveViewStatus nor RecordingMedia nor
            // ApplicationMode, and every property-gated code path silently does nothing.
            if (info.supports(PtpConstants.OC_NIKON_GET_VENDOR_PROP_CODES)) {
                val vendorProps = runCatching { session.getNikonVendorPropCodes() }
                    .getOrDefault(emptySet())
                Log.i(TAG, "Nikon_GetVendorPropCodes: ${vendorProps.size} Properties")
                info = info.withVendorPropertyCodes(vendorProps)
            }

            val newCamera = NikonPtpCamera(session, info)
            transport = newTransport
            camera = newCamera
            connectedDevice = device
            Log.i(
                TAG,
                "Connected to ${info.manufacturer} ${info.model} fw=${info.deviceVersion} " +
                    "ops=${info.operationsSupported.size}"
            )
            newCamera
        } catch (t: Throwable) {
            runCatching { newTransport.close() }
            throw if (t is CameraException) t else CameraException(CameraError.from(t))
        }
    }

    /** Runs [block] on the PTP thread with the live camera object. */
    suspend fun <T> withCamera(block: (NikonPtpCamera) -> T): T = withContext(ptpDispatcher) {
        val current = camera ?: throw CameraException(CameraError.Disconnected)
        block(current)
    }

    /** Same as [withCamera] but returns null instead of throwing when disconnected. */
    suspend fun <T> withCameraOrNull(block: (NikonPtpCamera) -> T): T? =
        withContext(ptpDispatcher) {
            val current = camera ?: return@withContext null
            block(current)
        }

    suspend fun disconnect() = withContext(ptpDispatcher) { closeInternal() }

    private fun closeInternal() {
        camera?.let { runCatching { it.close() } }
        transport?.let { runCatching { it.close() } }
        camera = null
        transport = null
        connectedDevice = null
    }

    /** Called from the detach broadcast: the device is already gone, so do not talk to it. */
    private fun forceCloseQuietly() {
        camera = null
        transport?.let { runCatching { it.close() } }
        transport = null
        connectedDevice = null
    }

    fun release() {
        unregisterHotplug()
        unregisterPermissionReceiver()
        ptpExecutor.execute { closeInternal() }
        ptpExecutor.shutdown()
    }

    companion object {
        private const val TAG = "UsbPtpManager"
        private const val ACTION_USB_PERMISSION = "de.nikonautofocus.app.USB_PERMISSION"
    }
}
