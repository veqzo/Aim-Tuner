package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.math.ViewAngles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val NANOS_PER_SECOND = 1_000_000_000L

private fun Target.yaw() = Math.toDegrees(kotlin.math.atan2(center.x, -center.z))

private fun Target.pitch() = Math.toDegrees(kotlin.math.asin(center.y / center.length))

private fun aimAt(target: Target) = target.center.normalized()

class MicroScenarioTest {
    private val config = MicroConfig(durationNanos = 30 * NANOS_PER_SECOND)

    @Test
    fun `each target is a few degrees from the previous one and inside the field`() {
        val s = MicroScenario(config, seed = 4)
        s.start(0)
        var previous: Vec3 = ViewAngles.directionOf(0.0, 0.0)
        repeat(300) {
            val target = s.targets.single()
            val step = target.center.angleToDegrees(previous)
            assertTrue(step in config.minStepDegrees - 1e-6..config.maxStepDegrees + 1e-6, "step $step")
            assertTrue(abs(target.yaw()) <= config.maxYawDegrees + 1e-6 && abs(target.pitch()) <= config.maxPitchDegrees + 1e-6)
            assertEquals(config.targetRadius, target.radius)
            previous = target.center
            s.fire(Vec3.ZERO, aimAt(target), it + 1L)
        }
    }

    @Test
    fun `same seed gives the same sequence and hits log events`() {
        fun run(seed: Long): List<TargetEvent> {
            val s = MicroScenario(config, seed)
            s.start(0)
            repeat(20) { s.fire(Vec3.ZERO, aimAt(s.targets.single()), it + 1L) }
            return s.events
        }
        assertEquals(run(9), run(9))
        assertNotEquals(run(9), run(10))
        assertEquals(20, run(9).count { it is TargetEvent.Hit })
    }
}

class SwitchingScenarioTest {
    private val config = SwitchingConfig(durationNanos = 30 * NANOS_PER_SECOND)

    @Test
    fun `several targets are visible and a hit replaces only the one hit`() {
        val s = SwitchingScenario(config, seed = 2)
        s.start(0)
        assertEquals(config.targetCount, s.targets.size)
        val before = s.targets
        val victim = before[1]
        assertEquals(ShotResult.HIT, s.fire(Vec3.ZERO, aimAt(victim), 10))
        val after = s.targets
        assertEquals(config.targetCount, after.size)
        assertTrue(after.none { it.id == victim.id })
        assertTrue(before.filter { it.id != victim.id }.all { it in after })
        assertTrue(s.events.last() is TargetEvent.Spawned)
    }

    @Test
    fun `visible targets keep their distance and misses are logged`() {
        val s = SwitchingScenario(config, seed = 6)
        s.start(0)
        repeat(100) {
            val targets = s.targets
            for (a in targets) for (b in targets) if (a.id < b.id) {
                assertTrue(a.center.angleToDegrees(b.center) >= config.minSeparationDegrees - 1e-6)
            }
            s.fire(Vec3.ZERO, aimAt(targets[it % targets.size]), it + 1L)
        }
        val away = Vec3(0.0, 0.0, 1.0)
        assertEquals(ShotResult.MISS, s.fire(Vec3.ZERO, away, 500))
        assertTrue(s.events.last() is TargetEvent.Miss)
    }

    @Test
    fun `time to hit counts from the previous hit when targets wait`() {
        val a = Target(0, Vec3(0.0, 0.0, -10.0), 0.3)
        val b = Target(1, Vec3(3.0, 0.0, -10.0), 0.3)
        val summary = summarize(
            listOf(
                TargetEvent.Spawned(0, a), TargetEvent.Spawned(0, b),
                TargetEvent.Hit(400, 0), TargetEvent.Spawned(400, Target(2, Vec3(-3.0, 0.0, -10.0), 0.3)),
                TargetEvent.Hit(700, 1),
            ),
        )
        assertEquals(2, summary.hits)
        // 400 ns for the first hit, 300 ns (not 700) for the second.
        assertEquals(350L, summary.meanTimeToHitNanos)
    }
}

class TrackingScenarioTest {
    private val config = TrackingConfig(durationNanos = 20 * NANOS_PER_SECOND)

    private fun playedEvents(seed: Long, stepNanos: Long): List<TargetEvent> {
        val s = TrackingScenario(config, seed)
        val start = 5 * NANOS_PER_SECOND
        s.start(start)
        var now = start
        while (now < start + config.durationNanos) {
            now += stepNanos
            s.update(now)
        }
        return s.events
    }

    @Test
    fun `the target stays in the field and moves at the configured speeds`() {
        val events = playedEvents(seed = 11, stepNanos = 10_000_000L)
        val spawn = events.first() as TargetEvent.Spawned
        val waypoints = events.filterIsInstance<TargetEvent.Waypoint>()
        assertTrue(waypoints.size > 5)
        val points = listOf(spawn.timeNanos to spawn.target.center) + waypoints.map { it.timeNanos to it.center }
        points.forEach { (_, c) ->
            val t = Target(0, c, 1.0)
            assertTrue(abs(t.yaw()) <= config.maxYawDegrees + 1e-6 && abs(t.pitch()) <= config.maxPitchDegrees + 1e-6)
            assertEquals(config.distance, c.length, 1e-9)
        }
        points.zipWithNext().forEach { (a, b) ->
            val speed = a.second.angleToDegrees(b.second) / ((b.first - a.first) / 1e9)
            assertTrue(speed <= config.maxSpeedDegreesPerSecond + 1e-6, "speed $speed")
        }
    }

    @Test
    fun `the log does not depend on how often the scenario is updated`() {
        val coarse = playedEvents(seed = 5, stepNanos = 33_000_000L)
        val fine = playedEvents(seed = 5, stepNanos = 1_000_000L)
        val end = 5 * NANOS_PER_SECOND + config.durationNanos
        assertEquals(fine.filter { it.timeNanos <= end }, coarse.filter { it.timeNanos <= end })
        // Both also cover the segment still running when the round ends.
        assertTrue(fine.any { it.timeNanos > end } && coarse.any { it.timeNanos > end })
    }

    @Test
    fun `the visible target follows the logged path and shots do nothing`() {
        val s = TrackingScenario(config, seed = 3)
        val start = 1_000L
        s.start(start)
        var now = start
        repeat(1000) {
            now += 10_000_000L
            s.update(now)
            val path = TargetPath(s.events)
            val expected = path.positionAt(0, now)!!
            val actual = s.targets.single().center
            assertTrue((expected - actual).length < 1e-9, "at $now")
        }
        assertEquals(ShotResult.IGNORED, s.fire(Vec3.ZERO, aimAt(s.targets.single()), now))
        assertTrue(s.events.none { it is TargetEvent.Hit || it is TargetEvent.Miss })
    }

    @Test
    fun `same seed gives the same motion`() {
        assertEquals(playedEvents(8, 10_000_000L), playedEvents(8, 10_000_000L))
        assertNotEquals(playedEvents(8, 10_000_000L), playedEvents(9, 10_000_000L))
    }
}

class TargetPathTest {
    @Test
    fun `positions interpolate between waypoints and hold before and after`() {
        val a = Vec3(0.0, 0.0, -10.0)
        val b = Vec3(10.0, 0.0, 0.0)
        val path = TargetPath(listOf(TargetEvent.Spawned(100, Target(0, a, 0.3)), TargetEvent.Waypoint(300, 0, b)))
        assertEquals(null, path.positionAt(0, 99))
        assertEquals(null, path.positionAt(7, 150))
        assertTrue((path.positionAt(0, 100)!! - a).length < 1e-9)
        assertTrue((path.positionAt(0, 300)!! - b).length < 1e-9)
        assertTrue((path.positionAt(0, 999)!! - b).length < 1e-9)
        val middle = path.positionAt(0, 200)!!
        assertEquals(10.0, middle.length, 1e-9)
        assertEquals(45.0, middle.angleToDegrees(a), 1e-9)
    }
}

class ScenarioKindTest {
    @Test
    fun `every kind creates a scenario that reports it`() {
        for (kind in ScenarioKind.entries) {
            val scenario = kind.create(10 * NANOS_PER_SECOND, 1)
            assertEquals(kind, scenario.kind)
            assertEquals(kind.id, scenario.id)
            assertEquals(kind, ScenarioKind.fromId(kind.id))
        }
        assertEquals(null, ScenarioKind.fromId("nope"))
    }
}
