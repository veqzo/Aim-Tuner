package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrafeMotionTest {
    private val nanosPerSecond = 1_000_000_000L
    private val distance = 10.0
    private val spawn: Vec3 = ViewAngles.directionOf(15.0, 2.0) * distance
    private val across: Vec3 = Vec3(-spawn.z, 0.0, spawn.x).normalized()

    private fun motion(config: StrafeConfig = StrafeConfig(), seed: Long = 5L) =
        StrafeMotion(config, targetId = 0, spawn = spawn, spawnNanos = 0L, seed = seed)

    /** Signed meters across the line of sight from the spawn; the legs keep the spawn's distance, so this is the distance times the tangent of the angle. */
    private fun lateralMeters(center: Vec3): Double = distance * (center dot across) / (center dot spawn.normalized())

    private fun lateralOffsets(waypoints: List<TargetEvent.Waypoint>) = listOf(0.0) + waypoints.map { lateralMeters(it.center) }

    @Test
    fun `the path does not depend on how often it is advanced`() {
        val often = motion()
        repeat(20_000) { often.extendTo(it * 1_000_000L) }
        val once = motion()
        once.extendTo(20 * nanosPerSecond)
        for (ms in 0..19_000 step 137) {
            val t = ms * 1_000_000L
            assertEquals(once.positionAt(t), often.positionAt(t), "at $ms ms")
        }
    }

    @Test
    fun `a seed gives a path and another seed another one`() {
        fun path(seed: Long) = motion(seed = seed).also { it.extendTo(10 * nanosPerSecond) }.positionAt(7 * nanosPerSecond)
        assertEquals(path(1), path(1))
        assertTrue(path(1) != path(2))
    }

    @Test
    fun `legs alternate between moving for 0_25-0_45 s at the speed and standing for 0_3-0_7 s`() {
        val waypoints = motion().extendTo(60 * nanosPerSecond)
        val offsets = lateralOffsets(waypoints)
        val times = listOf(0L) + waypoints.map { it.timeNanos }
        var moving = 0
        var standing = 0
        for (i in 1 until times.size) {
            val seconds = (times[i] - times[i - 1]) / nanosPerSecond.toDouble()
            val metersMoved = abs(offsets[i] - offsets[i - 1])
            if (metersMoved < 1e-9) {
                standing++
                assertTrue(seconds in 0.3 - 1e-6..0.7 + 1e-6, "stop of $seconds s")
            } else {
                moving++
                assertTrue(seconds in 0.25 - 1e-6..0.45 + 1e-6, "move of $seconds s")
                assertEquals(2.2, metersMoved / seconds, 1e-6, "speed")
            }
        }
        assertTrue(moving > 50 && standing > 50, "moving $moving, standing $standing")
    }

    @Test
    fun `the target stays within two meters of the spawn`() {
        val offsets = lateralOffsets(motion().extendTo(300 * nanosPerSecond))
        assertTrue(offsets.all { abs(it) <= 2.0 + 1e-9 }, "max ${offsets.maxOf { abs(it) }}")
        assertTrue(offsets.maxOf { abs(it) } > 1.2, "it should use most of the range")
    }

    @Test
    fun `after a stop the direction reverses with the configured probability`() {
        val config = StrafeConfig(maxOffsetMeters = 50.0, reverseProbability = 0.6)
        val offsets = lateralOffsets(motion(config).extendTo(2_000 * nanosPerSecond))
        val directions = (1 until offsets.size).map { offsets[it] - offsets[it - 1] }.filter { abs(it) > 1e-9 }.map { it > 0 }
        val reversals = directions.zipWithNext().count { (a, b) -> a != b }
        assertEquals(0.6, reversals.toDouble() / (directions.size - 1), 0.05)
    }

    @Test
    fun `the target does not leave the yaw limit`() {
        val config = StrafeConfig(maxAbsYawDegrees = 25.0)
        val waypoints = motion(config).extendTo(300 * nanosPerSecond)
        val yaws = waypoints.map { Math.toDegrees(kotlin.math.atan2(it.center.x, -it.center.z)) }
        assertTrue(yaws.all { abs(it) <= 25.0 + 1e-6 }, "max ${yaws.maxOf { abs(it) }}")
        assertTrue(yaws.any { abs(it) > 20.0 })
    }

    @Test
    fun `the interpolated position matches what the metrics read from the logged waypoints`() {
        val m = motion()
        val events = mutableListOf<TargetEvent>(TargetEvent.Spawned(0L, Target(0, spawn, 0.2)))
        events += m.extendTo(5 * nanosPerSecond)
        val path = TargetPath(events)
        for (ms in 0..5_000 step 41) {
            val t = ms * 1_000_000L
            val a = m.positionAt(t)
            val b = checkNotNull(path.positionAt(0, t))
            assertTrue(a.angleToDegrees(b) < 1e-4 && abs(a.length - b.length) < 1e-9, "at $ms ms")
        }
    }

    @Test
    fun `movesDuring tells a stopped stretch from a moving one`() {
        val m = motion(StrafeConfig(), seed = 3)
        val events = mutableListOf<TargetEvent>(TargetEvent.Spawned(0L, Target(0, spawn, 0.2)))
        events += m.extendTo(10 * nanosPerSecond)
        val path = TargetPath(events)
        assertTrue(path.movesDuring(0, 0L, 10 * nanosPerSecond))
        val waypoints = events.filterIsInstance<TargetEvent.Waypoint>()
        val stop = waypoints.zipWithNext().first { (a, b) -> a.center == b.center }
        assertTrue(!path.movesDuring(0, stop.first.timeNanos, stop.second.timeNanos))
        assertTrue(!TargetPath(listOf(TargetEvent.Spawned(0L, Target(0, spawn, 0.2)))).movesDuring(0, 0L, nanosPerSecond))
    }
}
