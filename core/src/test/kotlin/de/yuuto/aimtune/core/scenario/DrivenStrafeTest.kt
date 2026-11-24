package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import de.yuuto.aimtune.core.math.ViewAngles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Strafe legs driven by the game's movement model (M23): accelerate, hold, brake, stand. */
class DrivenStrafeTest {
    private val nanosPerSecond = 1_000_000_000L
    private val body = AgentBody(0.24, 1.96, 1.75, 1.35, 5.4, 2.97, 1.62)
    private val movement = GroundMovement(
        accel = 18.0, friction = 3.3, brakingFriction = 21.5, frictionMin = 11.0, aboveMaxSpeedDecay = 13.0,
        walkAccelFactor = 0.55, walkBrakingFactor = 0.55, crouchAccelFactor = 0.4, crouchFrictionFactor = 0.8, crouchFrictionMinFactor = 0.85,
    )
    private val pattern = StrafePattern(PositiveRange(1.0, 1.75), PositiveRange(0.2, 0.65), reverseProbability = 0.6, maxOffsetMeters = 2.0)
    private val geometry = TrainerGeometry(body = body, movement = movement, strafe = pattern)
    private val config = StrafeConfig.from(geometry)

    private fun spawnAt(distance: Double): Vec3 = ViewAngles.directionOf(10.0, 0.0) * distance

    private fun motion(distance: Double = 18.0, seed: Long = 7L) =
        StrafeMotion(config, targetId = 0, spawn = spawnAt(distance), spawnNanos = 0L, seed = seed)

    /** Signed meters across the line of sight; legs keep the spawn's distance. */
    private fun lateral(spawn: Vec3, center: Vec3): Double {
        val across = Vec3(-spawn.z, 0.0, spawn.x).normalized()
        return spawn.length * (center dot across) / (center dot spawn.normalized())
    }

    private class Leg(val seconds: Double, val meters: Double)

    /** The legs between standing points: a moving leg spans every waypoint from one stand to the next. */
    private fun legs(distance: Double, seconds: Long): Pair<List<Leg>, List<Leg>> {
        val spawn = spawnAt(distance)
        val waypoints = motion(distance).extendTo(seconds * nanosPerSecond)
        val times = listOf(0L) + waypoints.map { it.timeNanos }
        val offsets = listOf(0.0) + waypoints.map { lateral(spawn, it.center) }
        val moving = mutableListOf<Leg>()
        val standing = mutableListOf<Leg>()
        var legStart = 0
        for (i in 1 until times.size) {
            val still = abs(offsets[i] - offsets[i - 1]) < 1e-9
            if (still) {
                if (i - 1 > legStart) moving += Leg((times[i - 1] - times[legStart]) / 1e9, abs(offsets[i - 1] - offsets[legStart]))
                standing += Leg((times[i] - times[i - 1]) / 1e9, 0.0)
                legStart = i
            }
        }
        return moving to standing
    }

    @Test
    fun `a leg covers its travel and the target stands for the dwell between legs`() {
        val (moving, standing) = legs(18.0, 120)
        assertTrue(moving.size > 50 && standing.size > 50, "moving ${moving.size}, standing ${standing.size}")
        // The key is let go early enough to stop within the leg's travel; the braking way is short, so little is lost.
        assertTrue(moving.all { it.meters in 0.95..1.75 + 1e-6 }, "ways ${moving.map { it.meters }.sorted().take(3)}")
        assertTrue(standing.all { it.seconds in 0.2 - 1e-6..0.65 + 1e-6 })
    }

    @Test
    fun `no leg is faster than running, and short legs are slower on average than the top speed`() {
        val spawn = spawnAt(18.0)
        val waypoints = motion().extendTo(60 * nanosPerSecond)
        val speeds = (listOf(TargetEvent.Waypoint(0L, 0, spawn)) + waypoints).zipWithNext().map { (a, b) ->
            abs(lateral(spawn, b.center) - lateral(spawn, a.center)) / ((b.timeNanos - a.timeNanos) / 1e9)
        }
        assertTrue(speeds.max() <= 5.4 + 1e-6, "max ${speeds.max()}")
        val (moving, _) = legs(18.0, 60)
        val mean = moving.sumOf { it.meters } / moving.sumOf { it.seconds }
        // A mean leg of 1.375 m: 0.3 s accelerating (0.81 m), about 0.07 s at 5.4 m/s, 0.11 s braking (0.19 m): about 2.9 m/s.
        assertTrue(mean in 2.5..3.3, "mean speed $mean m/s")
        // At open-aim's median flash-drill distance this lies in the 95 % interval of its median moving-bot speed (7.8-12.5 deg/s).
        assertTrue(Math.toDegrees(mean / 18.0) in 7.8..12.5, "${Math.toDegrees(mean / 18.0)} deg/s")
    }

    @Test
    fun `the driven path stays in the offset range and does not depend on how often it is advanced`() {
        val spawn = spawnAt(18.0)
        val often = motion()
        repeat(30_000) { often.extendTo(it * 1_000_000L) }
        val once = motion()
        val waypoints = once.extendTo(30 * nanosPerSecond)
        for (ms in 0..29_000 step 113) {
            val t = ms * 1_000_000L
            assertEquals(once.positionAt(t), often.positionAt(t), "at $ms ms")
        }
        assertTrue(waypoints.all { abs(lateral(spawn, it.center)) <= 2.0 + 1e-6 })
        assertTrue(waypoints.zipWithNext().all { (a, b) -> b.timeNanos > a.timeNanos }, "times must increase")
    }

    @Test
    fun `a profile strafe needs a movement model, and without one the strafe keeps its constant speed`() {
        assertFailsWith<IllegalArgumentException> { TrainerGeometry(body = body, strafe = pattern) }
        assertFailsWith<IllegalArgumentException> { pattern.copy(travelMeters = PositiveRange(1.0, 5.0)) }
        assertEquals(null, StrafeConfig.from(TrainerGeometry(body = body, movement = movement)).locomotion)
        assertTrue(config.locomotion != null && config.stopSeconds == pattern.dwellSeconds)
    }

    @Test
    fun `tracking with a movement model moves between walking and running speed`() {
        val speeds = geometry.trackingSpeedDegreesPerSecond
        assertEquals(Math.toDegrees(2.97 / geometry.trackingDistanceMeters), speeds.min, 1e-9)
        assertEquals(Math.toDegrees(5.4 / geometry.trackingDistanceMeters), speeds.max, 1e-9)
    }
}
