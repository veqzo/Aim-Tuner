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

class RoundMetricsTest {
    private val dpi = 800.0
    private val cm360 = 40.0
    private val degPerCount = degreesPerCount(dpi, cm360)
    private val ms = 1_000_000L

    private fun counts(degrees: Double) = degrees / degPerCount

    private fun target(id: Int, yaw: Double, pitch: Double = 0.0) =
        Target(id, ViewAngles.directionOf(yaw, pitch) * 10.0, 0.25)

    private fun recording(
        reaches: List<Reach>,
        targetEvents: List<TargetEvent>,
        totalMs: Double = 1000.0,
    ) = RoundRecording(
        scenarioId = "flick", seed = 1, dpi = dpi, cm360 = cm360, startNanos = 0, endNanos = (totalMs * ms).toLong(),
        mouseEvents = SyntheticMouse.events(reaches, totalMs), targetEvents = targetEvents,
    )

    @Test
    fun `an accurate flick has no error no correction and full path efficiency`() {
        val t = target(0, yaw = 20.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(20.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(300 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(300 * ms, m.timeToTargetNanos)
        assertEquals(20.0, m.initialDistanceDegrees, 0.01)
        assertEquals(0.0, assertNotNull(m.firstErrorRatio), 0.03)
        assertEquals(0, m.correctiveSubmovements)
        assertEquals(1.0, assertNotNull(m.pathEfficiency), 0.03)
    }

    @Test
    fun `overshoot is positive and the correction is counted`() {
        val t = target(0, yaw = 20.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(25.0)), Reach(330.0, 120.0, dx = -counts(5.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(550 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(0.25, assertNotNull(m.firstErrorRatio), 0.04)
        assertEquals(1, m.correctiveSubmovements)
        assertEquals(20.0 / 30.0, assertNotNull(m.pathEfficiency), 0.04)
    }

    @Test
    fun `undershoot is negative`() {
        val t = target(0, yaw = -20.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = -counts(16.0)), Reach(330.0, 120.0, dx = -counts(4.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(550 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(-0.2, assertNotNull(m.firstErrorRatio), 0.04)
        assertEquals(1, m.correctiveSubmovements)
    }

    @Test
    fun `the endpoint error is split into a horizontal and a vertical part`() {
        val t = target(0, yaw = 20.0, pitch = 10.0)
        // Exactly the right distance sideways, but 4 degrees too high (mouse up looks up).
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(20.0 * Math.cos(Math.toRadians(10.0))), dy = -counts(14.0)), Reach(350.0, 120.0, dx = 0.0, dy = counts(4.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(560 * ms, 0)),
        )
        val metrics = computeRoundMetrics(rec)
        val m = metrics.movements.single()
        assertEquals(0.0, assertNotNull(m.firstErrorHDegrees), 0.7)
        assertEquals(4.0, assertNotNull(m.firstErrorVDegrees), 0.7)
        assertEquals(4.0, assertNotNull(metrics.meanFirstErrorVDegrees), 0.7)
        assertTrue(assertNotNull(metrics.verticalErrorShare) > 0.75, "share ${metrics.verticalErrorShare}")
    }

    @Test
    fun `a round without movements has no axis errors`() {
        val metrics = computeRoundMetrics(recording(emptyList(), emptyList()))
        assertNull(metrics.verticalErrorShare)
        assertNull(metrics.meanAbsFirstErrorHDegrees)
    }

    @Test
    fun `vertical targets use the pitch direction (mouse up looks up)`() {
        val t = target(0, yaw = 0.0, pitch = 15.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = 0.0, dy = -counts(15.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(300 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(15.0, m.initialDistanceDegrees, 0.05)
        assertEquals(0.0, assertNotNull(m.firstErrorRatio), 0.03)
    }

    @Test
    fun `movement peak speed is reported for later accel analysis`() {
        val t = target(0, yaw = 20.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(20.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(300 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(1.875 * counts(20.0) / 150.0, assertNotNull(m.firstPeakSpeedCountsPerMs), 0.5)
    }

    @Test
    fun `a target that is never hit produces no movement`() {
        val rec = recording(listOf(Reach(100.0, 150.0, dx = 300.0)), listOf(TargetEvent.Spawned(0, target(0, 20.0))))
        assertTrue(computeRoundMetrics(rec).movements.isEmpty())
    }

    @Test
    fun `round aggregates hits shots and mean time to hit`() {
        val a = target(0, yaw = 20.0)
        val b = target(1, yaw = -10.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(20.0)), Reach(600.0, 150.0, dx = -counts(30.0))),
            listOf(
                TargetEvent.Spawned(0, a),
                TargetEvent.Miss(200 * ms),
                TargetEvent.Hit(300 * ms, 0),
                TargetEvent.Spawned(300 * ms, b),
                TargetEvent.Hit(900 * ms, 1),
            ),
        )
        val r = computeRoundMetrics(rec)
        assertEquals(2, r.hits)
        assertEquals(3, r.shots)
        assertEquals(2.0 / 3.0, r.accuracy, 1e-9)
        assertEquals(2, r.movements.size)
        assertEquals((300.0 + 600.0) / 2, assertNotNull(r.meanTimeToHitMs), 1e-6)
        assertNotNull(r.errorSpread)
    }

    @Test
    fun `empty round has no aggregates`() {
        val r = computeRoundMetrics(recording(emptyList(), emptyList()))
        assertEquals(0, r.shots)
        assertNull(r.meanTimeToHitMs)
        assertNull(r.errorSpread)
    }

    @Test
    fun `a tremor fragment before the flick is not the first reach`() {
        val t = target(0, yaw = 20.0)
        val rec = recording(
            // 0.6 degrees of drift at 160 ms, then the real flick at 300 ms.
            listOf(Reach(150.0, 80.0, dx = counts(0.6)), Reach(300.0, 150.0, dx = counts(20.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(650 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        assertEquals(0.0, assertNotNull(m.firstErrorRatio), 0.06)
        assertEquals(0, m.correctiveSubmovements)
    }

    @Test
    fun `movement left over from the previous click right after the spawn is not the first reach`() {
        val t = target(0, yaw = 20.0)
        val rec = recording(
            // 8 degrees the wrong way, peaking about 70 ms after the spawn (no human reacts that fast), then the real flick.
            listOf(Reach(0.0, 140.0, dx = -counts(8.0)), Reach(300.0, 150.0, dx = counts(28.0))),
            listOf(TargetEvent.Spawned(0, t), TargetEvent.Hit(650 * ms, 0)),
        )
        val m = computeRoundMetrics(rec).movements.single()
        // From the spawn the view moved 8 degrees away and then 28 back: the flick lands 20 degrees from the start.
        assertEquals(0.0, assertNotNull(m.firstErrorRatio), 0.2)
        assertTrue(assertNotNull(m.reactionNanos) > 100 * ms)
    }

    @Test
    fun `a reach toward a target that was already visible may start at once`() {
        val first = target(0, yaw = 20.0)
        val second = target(1, yaw = -20.0)
        val rec = recording(
            listOf(Reach(100.0, 150.0, dx = counts(20.0)), Reach(310.0, 150.0, dx = -counts(40.0))),
            listOf(TargetEvent.Spawned(0, first), TargetEvent.Spawned(0, second), TargetEvent.Hit(300 * ms, 0), TargetEvent.Hit(700 * ms, 1)),
            totalMs = 800.0,
        )
        val movements = computeRoundMetrics(rec).movements
        assertNotNull(movements.single { it.targetId == 1 }.firstErrorRatio)
    }
}
