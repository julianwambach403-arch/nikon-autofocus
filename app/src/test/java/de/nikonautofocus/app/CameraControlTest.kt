package de.nikonautofocus.app

import de.nikonautofocus.app.analysis.LumaHistogram
import de.nikonautofocus.app.usb.CameraControlCatalog
import de.nikonautofocus.app.usb.CameraControlKind
import de.nikonautofocus.app.usb.PtpConstants
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
}
