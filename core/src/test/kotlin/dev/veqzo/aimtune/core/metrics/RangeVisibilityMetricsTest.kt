package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.math.ViewAngles
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.Target
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.testing.Reach
import dev.veqzo.aimtune.core.testing.SyntheticMouse
import dev.veqzo.aimtune.core.units.degreesPerCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Range bot that spawns outside the field of view counts from the moment it comes into view. */
class RangeVisibilityMetricsTest {
    private val ms = 1_000_000L
    private val degPerCount = degreesPerCount(800.0, 40.0)

    private fun counts(degrees: Double) = degrees / degPerCount

    private fun recording(scenario: String, yaw: Double, reach: Reach, events: List<TargetEvent>) = RoundRecording(
        scenarioId = scenario, seed = 1, dpi = 800.0, cm360 = 40.0, startNanos = 0, endNanos = 1_000 * ms,
        mouseEvents = SyntheticMouse.events(listOf(reach), 1000.0),
        targetEvents = listOf<TargetEvent>(TargetEvent.Spawned(0, Target(0, ViewAngles.directionOf(yaw, 0.0) * 10.0, 0.25))) + events,
    )

    @Test
    fun `a bot that spawns in sight is measured from its spawn`() {
        val rec = recording("range", 20.0, Reach(100.0, 150.0, dx = counts(20.0)), listOf(TargetEvent.Hit(300 * ms, 0)))
        val metrics = computeRoundMetrics(rec)
        assertEquals(300.0, assertNotNull(metrics.meanTimeToHitMs), 1e-9)
        assertEquals(0, metrics.rangeOffScreenSpawns)
        assertNull(metrics.rangeMedianSearchMs)
    }

    @Test
    fun `a bot that spawns behind the view counts from when it comes into view and a click at nothing is no miss`() {
        val events = listOf(TargetEvent.Miss(50 * ms), TargetEvent.Hit(600 * ms, 0))
        val range = computeRoundMetrics(recording("range", 80.0, Reach(100.0, 400.0, dx = counts(80.0)), events))
        val flick = computeRoundMetrics(recording("flick", 80.0, Reach(100.0, 400.0, dx = counts(80.0)), events))

        assertEquals(1, range.rangeOffScreenSpawns)
        val search = assertNotNull(range.rangeMedianSearchMs)
        assertTrue(search in 100.0..500.0, "search $search ms")
        assertEquals(600.0 - search, assertNotNull(range.meanTimeToHitMs), 6.0)
        assertEquals(1, range.shots, "the click at nothing before the bot is in view is no shot")
        assertEquals(1.0, assertNotNull(range.firstShotAccuracy))
        // The same events in another scenario are measured from the spawn, with the click counted.
        assertEquals(600.0, assertNotNull(flick.meanTimeToHitMs), 1e-9)
        assertEquals(2, flick.shots)
    }

    @Test
    fun `the time to kill of the range metrics starts when the bot was in view`() {
        val rec = recording("range", 80.0, Reach(100.0, 400.0, dx = counts(80.0)), listOf(TargetEvent.Hit(600 * ms, 0)))
        val metrics = computeRoundMetrics(rec)
        assertEquals(metrics.rangeMedianTimeToKillMs, metrics.meanTimeToHitMs)
        assertTrue(assertNotNull(metrics.rangeMedianTimeToKillMs) < 600.0)
    }
}
