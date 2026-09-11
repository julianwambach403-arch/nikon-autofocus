package de.nikonautofocus.app

import de.nikonautofocus.app.capture.BracketOrder
import de.nikonautofocus.app.capture.BracketValidation
import de.nikonautofocus.app.capture.BracketingPlan
import de.nikonautofocus.app.capture.BracketingSettings
import de.nikonautofocus.app.usb.PtpConstants
import de.nikonautofocus.app.usb.PtpDevicePropDesc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BracketingPlanTest {

    @Test
    fun `zero minus plus offsets for 3 5 and 7`() {
        assertEquals(listOf(0, -1, 1), BracketingPlan.offsetsThirds(3, 1, BracketOrder.ZERO_MINUS_PLUS))
        assertEquals(
            listOf(0, -2, 2, -4, 4),
            BracketingPlan.offsetsThirds(5, 2, BracketOrder.ZERO_MINUS_PLUS)
        )
        assertEquals(
            listOf(0, -1, 1, -2, 2, -3, 3),
            BracketingPlan.offsetsThirds(7, 1, BracketOrder.ZERO_MINUS_PLUS)
        )
    }

    @Test
    fun `minus zero plus offsets are sorted by exposure`() {
        assertEquals(listOf(-1, 0, 1), BracketingPlan.offsetsThirds(3, 1, BracketOrder.MINUS_ZERO_PLUS))
        assertEquals(
            listOf(-2, -1, 0, 1, 2),
            BracketingPlan.offsetsThirds(5, 1, BracketOrder.MINUS_ZERO_PLUS)
        )
    }

    @Test
    fun `one EV of shutter is a doubling of the PTP time value`() {
        val twoSeconds = BracketingPlan.shutterAfterEv(10_000, 3)
        assertEquals(2.0, twoSeconds, 1e-9)
        assertEquals(20_000L, BracketingPlan.shutterPtpFromSeconds(twoSeconds))
        val half = BracketingPlan.shutterAfterEv(10_000, -3)
        assertEquals(0.5, half, 1e-9)
    }

    @Test
    fun `nearest shutter snaps onto the camera table`() {
        val table = listOf(5L, 10L, 20L, 40L, 80L, 10_000L)
        assertEquals(10L, BracketingPlan.nearestAllowed(12, table))
        assertEquals(null, BracketingPlan.nearestAllowed(10, listOf(0L, 0xFFFFFFFFL)))
    }

    @Test
    fun `manual mode rejects a series shorter than 1 over 4000`() {
        val shutter = shutterDesc(current = 3L, values = listOf(3L, 5L, 10L, 20L, 10_000L))
        val result = BracketingPlan.validate(
            settings = BracketingSettings(enabled = true, count = 3, stepThirds = 3),
            exposureProgram = BracketingPlan.PROGRAM_MANUAL,
            shutterDesc = shutter,
            compensationDesc = compensationDesc(0)
        )
        assertTrue(result is BracketValidation.Rejected)
        assertTrue((result as BracketValidation.Rejected).reason.contains("1/4000"))
    }

    @Test
    fun `manual mode maps a 1 EV series around 1s onto 1 over 2 1s 2s`() {
        val values = listOf(2_500L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L)
        val result = BracketingPlan.validate(
            settings = BracketingSettings(
                enabled = true,
                count = 3,
                stepThirds = 3,
                order = BracketOrder.MINUS_ZERO_PLUS
            ),
            exposureProgram = BracketingPlan.PROGRAM_MANUAL,
            shutterDesc = shutterDesc(10_000L, values),
            compensationDesc = compensationDesc(0)
        )
        val ready = result as BracketValidation.Ready
        assertEquals(listOf(5_000L, 10_000L, 20_000L), ready.plan.shots.map { it.value })
    }

    @Test
    fun `program mode uses exposure compensation and rejects over plus minus 5 EV`() {
        val ok = BracketingPlan.validate(
            settings = BracketingSettings(enabled = true, count = 3, stepThirds = 3),
            exposureProgram = BracketingPlan.PROGRAM_P,
            shutterDesc = shutterDesc(125, listOf(125, 250, 10_000)),
            compensationDesc = compensationDesc(0)
        )
        assertTrue(ok is BracketValidation.Ready)

        val tooMuch = BracketingPlan.validate(
            settings = BracketingSettings(enabled = true, count = 5, stepThirds = 9),
            exposureProgram = BracketingPlan.PROGRAM_A,
            shutterDesc = shutterDesc(125, listOf(125)),
            compensationDesc = compensationDesc(0)
        )
        assertTrue(tooMuch is BracketValidation.Rejected)
        assertTrue((tooMuch as BracketValidation.Rejected).reason.contains("±5 EV"))
    }

    @Test
    fun `AUTO is rejected with a German hint`() {
        val result = BracketingPlan.validate(
            settings = BracketingSettings(enabled = true),
            exposureProgram = 0x8010L,
            shutterDesc = shutterDesc(125, listOf(125)),
            compensationDesc = compensationDesc(0)
        )
        val rejected = result as BracketValidation.Rejected
        assertTrue(rejected.reason.contains("AUTO"))
        assertTrue(rejected.reason.contains("P, S, A oder M"))
    }

    @Test
    fun `compensation snap stays on the Nikon 333 thousandths grid`() {
        val desc = compensationDesc(0)
        val plusOneThird = BracketingPlan.compensationAfterEv(0, 1)
        assertEquals(333L, plusOneThird)
        assertEquals(333L, BracketingPlan.snapCompensation(plusOneThird, desc))
        assertEquals(null, BracketingPlan.snapCompensation(6_000L, desc))
    }

    @Test
    fun `existing plus 3 EV plus a plus 2 EV step exceeds the D3400 limit`() {
        val result = BracketingPlan.validate(
            settings = BracketingSettings(enabled = true, count = 3, stepThirds = 9),
            exposureProgram = BracketingPlan.PROGRAM_S,
            shutterDesc = shutterDesc(125, listOf(125)),
            compensationDesc = compensationDesc(3_000L)
        )
        assertTrue(result is BracketValidation.Rejected)
    }

    private fun shutterDesc(current: Long, values: List<Long>) = PtpDevicePropDesc(
        propertyCode = PtpConstants.DPC_EXPOSURE_TIME,
        dataType = PtpConstants.DTC_UINT32,
        writable = true,
        currentValue = current,
        defaultValue = current,
        enumValues = values,
        minimum = values.minOrNull(),
        maximum = values.maxOrNull(),
        step = null
    )

    private fun compensationDesc(current: Long): PtpDevicePropDesc {
        val values = (-15..15).map { it * BracketingPlan.THIRD_MILLI }
        return PtpDevicePropDesc(
            propertyCode = PtpConstants.DPC_EXPOSURE_BIAS_COMPENSATION,
            dataType = PtpConstants.DTC_INT16,
            writable = true,
            currentValue = current,
            defaultValue = 0,
            enumValues = values,
            minimum = -BracketingPlan.EV_LIMIT_MILLI,
            maximum = BracketingPlan.EV_LIMIT_MILLI,
            step = BracketingPlan.THIRD_MILLI
        )
    }

    @Test
    fun `unused abs import stays honest for shutter distance`() {
        assertTrue(abs(BracketingPlan.shutterAfterEv(10_000, 1) - 1.25992) < 0.001)
    }
}
