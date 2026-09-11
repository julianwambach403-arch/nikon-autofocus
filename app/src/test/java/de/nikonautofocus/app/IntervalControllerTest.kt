package de.nikonautofocus.app

import de.nikonautofocus.app.capture.IntervalController
import de.nikonautofocus.app.capture.IntervalPhase
import de.nikonautofocus.app.capture.IntervalSettings
import de.nikonautofocus.app.capture.IntervalSlotResult
import de.nikonautofocus.app.capture.IntervalTiming
import de.nikonautofocus.app.capture.PreparedBracket
import de.nikonautofocus.app.capture.BracketDrive
import de.nikonautofocus.app.capture.BracketShot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalControllerTest {

    @Test
    fun `slots are scheduled start to start`() {
        assertEquals(6_000L, IntervalTiming.slotDueAt(1_000, 5_000, 2_000, 0))
        assertEquals(10_000L, IntervalTiming.slotDueAt(1_000, 5_000, 2_000, 2))
        assertTrue(IntervalTiming.isMissed(10_000, 6_000))
        assertTrue(!IntervalTiming.isMissed(6_100, 6_000, graceMs = 250))
    }

    @Test
    fun `pause rebases so the current slot is due now`() {
        val t0 = IntervalTiming.rebaseT0(nowMs = 10_000, delayMs = 0, intervalMs = 2_000, resumeIndex = 3)
        assertEquals(10_000L, IntervalTiming.slotDueAt(t0, 0, 2_000, 3))
        assertEquals(12_000L, IntervalTiming.slotDueAt(t0, 0, 2_000, 4))
    }

    @Test
    fun `controller fires three slots two seconds apart`() {
        var now = 0L
        val firedAt = mutableListOf<Long>()
        val controller = IntervalController(clockMs = { now }, delayMs = { now += it })
        runBlocking {
            controller.run(
                IntervalSettings(hours = 0, minutes = 0, seconds = 2, shotCount = 3)
            ) { _, _ ->
                firedAt += now
                now += 50
                IntervalSlotResult(1)
            }
        }
        assertEquals(listOf(0L, 2_000L, 4_000L), firedAt)
        assertEquals(IntervalPhase.FINISHED, controller.session.value.phase)
        assertEquals(3, controller.session.value.photosTaken)
    }

    @Test
    fun `a capture longer than the interval is logged as missed but still taken`() {
        var now = 0L
        val missedFlags = mutableListOf<Boolean>()
        val controller = IntervalController(clockMs = { now }, delayMs = { d -> now += d })
        runBlocking {
            controller.run(
                IntervalSettings(seconds = 1, shotCount = 3)
            ) { _, missed ->
                missedFlags += missed
                now += 2_500
                IntervalSlotResult(1)
            }
        }
        assertEquals(listOf(false, true, true), missedFlags)
        assertEquals(2, controller.session.value.missedSlots)
        assertEquals(3, controller.session.value.photosTaken)
    }

    @Test
    fun `interval shorter than shutter plus buffer produces a warning`() {
        val warnings = IntervalTiming.durationWarnings(
            intervalMs = 500,
            shutterPtp = 10_000,
            saveBufferMs = 1_500,
            bracket = null
        )
        assertTrue(warnings.single().contains("kuerzer"))
    }

    @Test
    fun `bracket series longer than the interval produces a warning`() {
        val plan = PreparedBracket(
            drive = BracketDrive.SHUTTER,
            propertyCode = 0,
            dataType = 0,
            originalValue = 10_000,
            shots = listOf(
                BracketShot(0, 10_000, "1s"),
                BracketShot(-3, 5_000, "1/2"),
                BracketShot(3, 20_000, "2s")
            )
        )
        val warnings = IntervalTiming.durationWarnings(
            intervalMs = 1_000,
            shutterPtp = 10_000,
            saveBufferMs = 1_500,
            bracket = plan
        )
        assertTrue(warnings.single().contains("Belichtungsreihe"))
    }
}
