package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RangeScenarioTest {
    private val second = 1_000_000_000L
    private val round = 45 * second

    private fun scenario(seed: Long = 1L, options: RangeOptions = RangeOptions.DEFAULT) =
        RangeScenario(RangeConfig(round, options = options), seed).also { it.start(0L) }

    private fun headShot(s: RangeScenario, at: Long): ShotResult {
        s.update(at)
        val head = s.targets.single().center
        return s.fire(Vec3.ZERO, head.normalized(), at)
    }

    /** A shot at the middle of the body, a metre below the head. */
    private fun bodyShot(s: RangeScenario, at: Long): ShotResult {
        s.update(at)
        val head = s.targets.single().center
        return s.fire(Vec3.ZERO, Vec3(head.x, head.y - 0.9, head.z).normalized(), at)
    }

    @Test
    fun `one bot is alive at a time and the first is there at the start`() {
        val s = scenario()
        assertEquals(1, s.targets.size)
        assertEquals(ScenarioKind.RANGE, s.kind)
        assertEquals("range", s.id)
        assertEquals(ShotResult.HIT, headShot(s, 500_000_000L))
        assertEquals(0, s.targets.size)
    }

    @Test
    fun `the next bot comes 0_2 to 0_8 seconds after a kill and not before`() {
        repeat(20) { seed ->
            val s = scenario(seed.toLong())
            val killedAt = second
            headShot(s, killedAt)
            s.update(killedAt + 190_000_000L)
            assertTrue(s.targets.isEmpty(), "no bot within 0.2 s")
            s.update(killedAt + 810_000_000L)
            assertEquals(1, s.targets.size)
            val spawns = s.events.filterIsInstance<TargetEvent.Spawned>()
            val delay = spawns.last().timeNanos - killedAt
            assertTrue(delay in 200_000_000L..800_000_000L, "delay $delay")
        }
    }

    @Test
    fun `shots while no bot is there are ignored`() {
        val s = scenario()
        headShot(s, second)
        assertEquals(ShotResult.IGNORED, s.fire(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), second + 1_000L))
    }

    @Test
    fun `bots appear within the bearing and distance of the profile, on the floor`() {
        val geometry = TrainerGeometry.DEFAULT
        var now = 0L
        val s = scenario(7L)
        repeat(60) {
            val c = s.targets.single().center
            val bearing = Math.toDegrees(atan2(c.x, -c.z))
            val ground = hypot(c.x, c.z)
            val spawn = s.events.filterIsInstance<TargetEvent.Spawned>().last().target.center
            val spawnBearing = Math.toDegrees(atan2(spawn.x, -spawn.z))
            assertTrue(spawnBearing in -50.0..80.0, "bearing $spawnBearing")
            assertTrue(hypot(spawn.x, spawn.z) in 5.0..20.0, "distance")
            assertEquals(-geometry.eyeHeightMeters + 1.8 - 0.125, spawn.y, 1e-9)
            assertTrue(bearing.isFinite() && ground > 0.0)
            now += 3 * second
            if (s.targets.isEmpty()) s.update(now)
            headShot(s, now)
            s.update(now + second)
        }
    }

    @Test
    fun `a head hit kills and is a hit event, the head radius is the profile head`() {
        val s = scenario()
        assertEquals(0.125, s.targets.single().radius, 1e-9)
        assertEquals(ShotResult.HIT, headShot(s, second))
        val hit = s.events.filterIsInstance<TargetEvent.Hit>().single()
        assertEquals(0, hit.targetId)
        assertEquals(second, hit.timeNanos)
    }

    @Test
    fun `with armor it takes four body hits, without armor three, and body hits are logged`() {
        for ((armor, needed) in listOf(true to 4, false to 3)) {
            val s = scenario(options = RangeOptions(botsStrafe = false, armor = armor))
            repeat(needed - 1) { n ->
                assertEquals(ShotResult.MISS, bodyShot(s, second + n * 100_000_000L), "armor $armor shot $n")
                assertEquals(1, s.targets.size)
            }
            assertEquals(needed - 1, s.events.count { it is TargetEvent.BodyHit })
            assertEquals(ShotResult.HIT, bodyShot(s, 2 * second))
            assertTrue(s.targets.isEmpty())
            assertEquals(1, s.events.count { it is TargetEvent.Hit })
        }
    }

    @Test
    fun `a shot beside the bot is a miss and the legs count as body`() {
        val s = scenario(options = RangeOptions(botsStrafe = false))
        val head = s.targets.single().center
        assertEquals(ShotResult.MISS, s.fire(Vec3.ZERO, Vec3(head.x + 3.0, head.y, head.z).normalized(), second))
        assertEquals(1, s.events.count { it is TargetEvent.Miss })
        // Near the feet: the soles are 1.675 m below the head centre.
        assertEquals(ShotResult.MISS, s.fire(Vec3.ZERO, Vec3(head.x, head.y - 1.5, head.z).normalized(), 2 * second))
        assertEquals(1, s.events.count { it is TargetEvent.BodyHit })
        // Above the head: nothing.
        s.fire(Vec3.ZERO, Vec3(head.x, head.y + 0.6, head.z).normalized(), 3 * second)
        assertEquals(2, s.events.count { it is TargetEvent.Miss })
    }

    @Test
    fun `an unshot bot stays without a speed preset and leaves after its lifetime with one`() {
        val none = scenario()
        none.update(40 * second)
        assertEquals(1, none.targets.size)
        assertNotNull(none.targets.firstOrNull())
        assertEquals(null, none.deadlineNanos(none.targets.single()))

        val medium = scenario(options = RangeOptions(speed = RangeSpeed.MEDIUM))
        val first = medium.targets.single()
        assertEquals(2 * second, medium.deadlineNanos(first))
        medium.update(2 * second - 1)
        assertEquals(1, medium.targets.size)
        medium.update(2 * second + 100_000_000L)
        val despawn = medium.events.filterIsInstance<TargetEvent.Despawned>().single()
        assertEquals(2 * second, despawn.timeNanos)
        assertEquals(0, despawn.targetId)
        assertTrue(medium.targets.isEmpty() || medium.targets.single().id == 1)
        medium.update(3 * second)
        val next = medium.events.filterIsInstance<TargetEvent.Spawned>().last()
        assertEquals(1, next.target.id)
        assertTrue(next.timeNanos - despawn.timeNanos in 200_000_000L..800_000_000L)
        assertEquals(next.timeNanos + 2 * second, medium.deadlineNanos(medium.targets.single()))
    }

    @Test
    fun `a despawned bot counts as a missed first shot in the summary`() {
        val s = scenario(options = RangeOptions(speed = RangeSpeed.EASY, botsStrafe = false))
        s.update(3 * second + 1) // bot 0 leaves unshot
        s.update(5 * second)
        headShot(s, 5 * second + 1)
        val summary = summarize(s.events)
        assertEquals(1, summary.hits)
        assertEquals(2, summary.firstShots)
        assertEquals(1, summary.firstShotHits)
    }

    @Test
    fun `bots strafe with the profile strafe, reset bots stand still`() {
        val strafing = scenario(3L)
        strafing.update(5 * second)
        assertTrue(strafing.events.any { it is TargetEvent.Waypoint })
        val moved = strafing.targets.single().center
        val spawn = strafing.events.filterIsInstance<TargetEvent.Spawned>().first().target.center
        assertTrue(moved != spawn)
        // The bot keeps its distance and stays within +-2 m of its spawn.
        assertEquals(spawn.length, moved.length, 1e-6)
        assertTrue((moved - spawn).length <= 4.0 + 1e-6)

        val standing = scenario(3L, RangeOptions(botsStrafe = false))
        standing.update(5 * second)
        assertTrue(standing.events.none { it is TargetEvent.Waypoint })
        assertEquals(spawn, standing.targets.single().center)
    }

    @Test
    fun `a moving bot is hit where it is at the time of the shot`() {
        val s = scenario(11L)
        // Find a moment when the bot has moved off its spawn, then aim at the old place.
        val spawn = s.targets.single().center
        var t = 0L
        while ((s.targets.single().center - spawn).length < 0.6 && t < 5 * second) {
            t += 10_000_000L
            s.update(t)
        }
        assertTrue((s.targets.single().center - spawn).length >= 0.6)
        assertEquals(ShotResult.MISS, s.fire(Vec3.ZERO, spawn.normalized(), t))
        assertEquals(ShotResult.HIT, s.fire(Vec3.ZERO, s.targets.single().center.normalized(), t + 1))
    }

    @Test
    fun `the same seed and the same shots give the same events however often it is updated`() {
        fun play(step: Long): List<TargetEvent> {
            val s = scenario(5L, RangeOptions(speed = RangeSpeed.HARD))
            var t = 0L
            while (t < 30 * second) {
                t += step
                s.update(t)
                if (t % (700_000_000L) == 0L && s.targets.isNotEmpty()) s.fire(Vec3.ZERO, s.targets.single().center.normalized(), t)
            }
            return s.events.filter { it !is TargetEvent.Waypoint || it.timeNanos <= 30 * second }
        }
        val a = play(10_000_000L)
        val b = play(10_000_000L)
        assertEquals(a, b)
        // Different update rates see the same bots at the same times.
        val c = play(50_000_000L).filterIsInstance<TargetEvent.Spawned>().map { it.target.id to it.timeNanos }
        val d = play(10_000_000L).filterIsInstance<TargetEvent.Spawned>().map { it.target.id to it.timeNanos }
        assertEquals(d.take(c.size.coerceAtMost(d.size)).map { it.first }, c.take(d.size).map { it.first })
        assertTrue(abs(c.size - d.size) <= 3)
    }

    @Test
    fun `no bot spawns after the round is over`() {
        val s = scenario(options = RangeOptions(speed = RangeSpeed.HARD, botsStrafe = false))
        s.update(round + 10 * second)
        assertTrue(s.events.filterIsInstance<TargetEvent.Spawned>().all { it.timeNanos < round })
        assertEquals(ShotResult.IGNORED, s.fire(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), round + second))
    }
}
