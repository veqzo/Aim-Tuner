package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.math.ViewAngles
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.testing.Reach
import dev.veqzo.aimtune.core.testing.SyntheticMouse
import dev.veqzo.aimtune.core.units.degreesPerCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovingTargetMetricsTest {
    private val dpi = 800.0
    private val cm360 = 40.0
    private val ms = 1_000_000L
    private val degPerCount = degreesPerCount(dpi, cm360)

    private fun at(yaw: Double) = ViewAngles.directionOf(yaw, 0.0) * 10.0

    /** A target at yaw 20 that [moves] to yaw 30 by 400 ms, and a 25 degree reach that ends at 250 ms; the hit comes at 500 ms. */
    private fun recording(moves: Boolean): RoundRecording {
        val events = buildList<TargetEvent> {
            add(TargetEvent.Spawned(0, Target(0, at(20.0), 0.25)))
            if (moves) add(TargetEvent.Waypoint(400 * ms, 0, at(30.0)))
            add(TargetEvent.Hit(500 * ms, 0))
        }
        return RoundRecording(
            scenarioId = "micro-strafe", seed = 1, dpi = dpi, cm360 = cm360, startNanos = 0, endNanos = 1000 * ms,
            mouseEvents = SyntheticMouse.events(listOf(Reach(100.0, 150.0, dx = 25.0 / degPerCount)), 1000.0), targetEvents = events,
        )
    }

    @Test
    fun `the error of a reach toward a moving target is measured against the target where the reach ended`() {
        val m = computeRoundMetrics(recording(moves = true)).movements.single()
        assertTrue(m.targetMoved)
        // At 250 ms the target is 62.5 % of the way to yaw 30, so the 25 degree reach is about 1.3 degrees short of it. Against the spawn it would be 25 % over.
        assertEquals(-0.05, assertNotNull(m.firstErrorRatio), 0.03)
        assertEquals(-1.3, assertNotNull(m.firstErrorHDegrees), 0.5)
        assertEquals(0.0, assertNotNull(m.firstErrorVDegrees), 0.3)
    }

    @Test
    fun `the error splits into a lead along the target's motion and an aim across it`() {
        val m = computeRoundMetrics(recording(moves = true)).movements.single()
        // The target moves right; the crosshair ended behind it (negative lead) and level with it (no aim error).
        assertEquals(-1.3, assertNotNull(m.leadErrorDegrees), 0.5)
        assertEquals(0.0, assertNotNull(m.aimErrorDegrees), 0.3)
        val round = computeRoundMetrics(recording(moves = true))
        assertNotNull(round.meanAbsLeadErrorDegrees)
        assertNotNull(round.meanAbsAimErrorDegrees)
    }

    @Test
    fun `a static target has no moved flag and no lead or aim`() {
        val m = computeRoundMetrics(recording(moves = false)).movements.single()
        assertFalse(m.targetMoved)
        assertNull(m.leadErrorDegrees)
        assertNull(m.aimErrorDegrees)
        assertEquals(0.25, assertNotNull(m.firstErrorRatio), 0.04)
        assertNull(computeRoundMetrics(recording(moves = false)).meanAbsLeadErrorDegrees)
    }

    @Test
    fun `the speed-error analysis only sees reaches toward targets that stood still`() {
        val moving = computeRoundMetrics(recording(moves = true))
        val still = computeRoundMetrics(recording(moves = false))
        assertEquals(0, movementSamples(listOf(moving), dpi).size)
        assertEquals(1, movementSamples(listOf(still), dpi).size)
        assertEquals(1, movementSamples(listOf(moving, still), dpi).size)
    }
}
