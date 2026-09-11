package de.nikonautofocus.app

import de.nikonautofocus.app.analysis.LumaHistogram
import de.nikonautofocus.app.usb.CameraControlCatalog
import de.nikonautofocus.app.usb.CameraControlKind
import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.PtpDeviceInfo
import de.nikonautofocus.app.usb.PtpDevicePropDesc
import de.nikonautofocus.app.usb.PtpReader
import de.nikonautofocus.app.usb.PtpStorageInfo
import de.nikonautofocus.app.usb.PtpWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlTest {

    @Test
    fun `shutter times use the PTP 1 over 10000 second encoding`() {
        assertEquals("1/80", PtpConstants.exposureTimeName(125))
        assertEquals("1/1000", PtpConstants.exposureTimeName(10))
        assertEquals("1s", PtpConstants.exposureTimeName(10_000))
        assertEquals("2s", PtpConstants.exposureTimeName(20_000))
        assertEquals("Bulb", PtpConstants.exposureTimeName(0))
    }

    @Test
    fun `aperture iso and exposure compensation labels match PTP units`() {
        assertEquals("f/5.6", PtpConstants.fNumberName(560))
        assertEquals("f/11", PtpConstants.fNumberName(1100))
        assertEquals("ISO 400", PtpConstants.isoName(400))
        assertEquals("+0.3 EV", PtpConstants.exposureBiasName(300))
        assertEquals("-1.0 EV", PtpConstants.exposureBiasName(-1000))
        assertEquals("0.0 EV", PtpConstants.exposureBiasName(0))
    }

    @Test
    fun `exposure strip chips stay short`() {
        val iso = CameraControlCatalog.chip(CameraControlKind.ISO, 800)
        val program = CameraControlCatalog.chip(CameraControlKind.PROGRAM, 0x0003)
        val bias = CameraControlCatalog.chip(CameraControlKind.EXPOSURE_COMP, -700)
        assertEquals("ISO 800", iso)
        assertEquals("A", program)
        assertEquals("-0.7", bias)
    }

    @Test
    fun `range form is expanded and always includes the current value`() {
        val values = CameraControlCatalog.expandRange(
            minimum = 100,
            maximum = 1600,
            step = 100,
            current = 250
        )
        assertTrue(values.contains(100L))
        assertTrue(values.contains(250L))
        assertTrue(values.contains(1600L))
        assertTrue(values.size <= 81)
    }

    @Test
    fun `histogram puts highlights in the last bins`() {
        val gray = IntArray(100) { 255 }
        val bins = LumaHistogram.compute(gray, gray.size)
        assertEquals(LumaHistogram.BINS, bins.size)
        assertEquals(100, bins[LumaHistogram.BINS - 1])
        assertEquals(0, bins[0])
    }

    @Test
    fun `storage info dataset is parsed little endian`() {
        val writer = PtpWriter(32)
        writer.writeU16(1) // storage type
        writer.writeU16(2) // filesystem
        writer.writeU16(0) // access
        writer.writeU32(1_000L) // max capacity low
        writer.writeU32(0) // max capacity high
        writer.writeU32(400L) // free bytes low
        writer.writeU32(0)
        writer.writeU32(42)
        writer.writeU8(0) // empty string
        val info = PtpStorageInfo.parse(0x00010001, writer.toByteArray(), writer.toByteArray().size)
        assertEquals(42L, info.freeSpaceInImages)
        assertEquals(400L, info.freeSpaceBytes)
        assertEquals(1_000L, info.maxCapacityBytes)
    }

    @Test
    fun `signed INT16 exposure compensation survives a write-read roundtrip`() {
        val writer = PtpWriter(4)
        PtpDevicePropDesc.writeScalar(writer, PtpConstants.DTC_INT16, -1500)
        val bytes = writer.toByteArray()
        val read = PtpDevicePropDesc.readScalar(PtpReader(bytes), PtpConstants.DTC_INT16)
        assertEquals(-1500L, read)
    }

    @Test
    fun `vendor property codes from 0x90CA count as supported properties`() {
        val info = PtpDeviceInfo(
            standardVersion = 0x64,
            vendorExtensionId = 6,
            vendorExtensionVersion = 0x64,
            vendorExtensionDesc = "Microsoft.com/DeviceServices: 1.0",
            functionalMode = 0,
            operationsSupported = setOf(PtpConstants.OC_NIKON_GET_VENDOR_PROP_CODES),
            eventsSupported = emptySet(),
            devicePropertiesSupported = setOf(PtpConstants.DPC_EXPOSURE_PROGRAM_MODE),
            captureFormats = emptySet(),
            imageFormats = emptySet(),
            manufacturer = "Nikon Corporation",
            model = "D3400",
            deviceVersion = "V1.13",
            serialNumber = ""
        )
        assertTrue(!info.hasProperty(PtpConstants.DPC_NIKON_APPLICATION_MODE))

        val merged = info.withVendorPropertyCodes(
            setOf(
                PtpConstants.DPC_NIKON_APPLICATION_MODE,
                PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS,
                PtpConstants.DPC_NIKON_RECORDING_MEDIA
            )
        )
        assertTrue(merged.hasProperty(PtpConstants.DPC_EXPOSURE_PROGRAM_MODE))
        assertTrue(merged.hasProperty(PtpConstants.DPC_NIKON_APPLICATION_MODE))
        assertTrue(merged.hasProperty(PtpConstants.DPC_NIKON_LIVE_VIEW_STATUS))
        assertTrue(merged.hasProperty(PtpConstants.DPC_NIKON_RECORDING_MEDIA))
        assertEquals(1, merged.devicePropertiesSupported.size)
    }

    @Test
    fun `pointer in a letterboxed liveview maps to image fractions`() {
        val boxW = 300f
        val boxH = 200f
        val imageW = 600
        val imageH = 400
        val center = de.nikonautofocus.app.ui.imageFractionFromPointer(
            androidx.compose.ui.geometry.Offset(150f, 100f),
            boxW, boxH, imageW, imageH
        )
        assertEquals(0.5f, center!!.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)

        val topLeft = de.nikonautofocus.app.ui.imageFractionFromPointer(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            boxW, boxH, imageW, imageH
        )
        assertEquals(0f, topLeft!!.x, 0.001f)
        assertEquals(0f, topLeft.y, 0.001f)
    }

    @Test
    fun `device property descriptor accepts enum and range values`() {
        val enumerated = PtpDevicePropDesc(
            propertyCode = PtpConstants.DPC_NIKON_LIVE_VIEW_AF_AREA,
            dataType = PtpConstants.DTC_UINT8,
            writable = true,
            currentValue = 0,
            defaultValue = 0,
            enumValues = listOf(0, 1, 2, 4),
            minimum = null,
            maximum = null,
            step = null
        )
        assertTrue(enumerated.accepts(PtpConstants.AF_AREA_SPOT))
        assertTrue(!enumerated.accepts(PtpConstants.AF_AREA_TRACKING))
    }
}
