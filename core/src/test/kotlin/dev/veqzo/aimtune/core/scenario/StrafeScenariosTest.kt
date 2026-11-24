package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StrafeScenariosTest {
    private val second = 1_000_000_000L

    private fun micro(seed: Long = 7L) = ScenarioKind.MICRO_STRAFE.create(30 * second, seed).also { it.start(0L) }

    private fun switching(seed: Long = 7L) = ScenarioKind.SWITCH_STRAFE.create(30 * second, seed).also { it.start(0L) }

    @Test
    fun `the strafing variants have their own ids and the static ones keep theirs`() {
        assertEquals("micro-strafe", micro().id)
        assertEquals("switch-strafe", switching().id)
        assertEquals(ScenarioKind.MICRO_STRAFE, ScenarioKind.fromId("micro-strafe"))
        assertEquals("micro", ScenarioKind.MICRO.create(second, 1L).id)
        assertTrue(ScenarioKind.entries.filter { it.strafes }.all { it.usesClicks })
    }

    @Test
    fun `static rounds log no waypoints`() {
        for (kind in listOf(ScenarioKind.MICRO, ScenarioKind.SWITCHING)) {
            val s = kind.create(30 * second, 3L).also { it.start(0L) }
            s.update(5 * second)
            assertTrue(s.events.none { it is TargetEvent.Waypoint }, kind.id)
        }
    }

    @Test
    fun `a micro target moves and the log reproduces where it is`() {
        val s = micro()
        val spawn = s.targets.single().center
        var moved = false
        for (ms in 0..4_000 step 37) {
            val t = ms * 1_000_000L
            s.update(t)
            val now = s.targets.single().center
            moved = moved || now.angleToDegrees(spawn) > 1.0
            val logged = checkNotNull(TargetPath(s.events).positionAt(0, t))
            assertTrue(now.angleToDegrees(logged) < 1e-4, "at $ms ms")
        }
        assertTrue(moved)
    }

    @Test
    fun `a shot hits the target where it is now and misses where it spawned`() {
        val s = micro()
        val spawn = s.targets.single().center
        val radiusDegrees = Math.toDegrees(atan(s.targets.single().radius / spawn.length))
        var t = 0L
        while (true) {
            t += 10_000_000L
            s.update(t)
            if (s.targets.single().center.angleToDegrees(spawn) > 3 * radiusDegrees) break
            check(t < 10 * second) { "the target never moved" }
        }
        assertEquals(ShotResult.MISS, s.fire(Vec3.ZERO, spawn.normalized(), t))
        assertEquals(ShotResult.HIT, s.fire(Vec3.ZERO, s.targets.single().center.normalized(), t))
        assertEquals(1, s.events.count { it is TargetEvent.Hit })
        assertEquals(2, s.events.filterIsInstance<TargetEvent.Spawned>().size)
    }

    @Test
    fun `a shot without an update first still goes at the target where it is at the time of the shot`() {
        val a = micro()
        val b = micro()
        val t = 900_000_000L
        b.update(t)
        val aim = b.targets.single().center.normalized()
        assertEquals(ShotResult.HIT, a.fire(Vec3.ZERO, aim, t))
        assertEquals(ShotResult.HIT, b.fire(Vec3.ZERO, aim, t))
    }

    @Test
    fun `the log does not depend on how often the scenario is updated`() {
        val often = micro()
        for (ms in 0..3_000) often.update(ms * 1_000_000L)
        val once = micro()
        once.update(3 * second)
        assertEquals(once.events, often.events)
    }

    @Test
    fun `every switching target strafes on its own and a hit removes only its motion`() {
        val s = switching()
        val before = s.targets.map { it.center }
        s.update(2 * second)
        val after = s.targets
        assertEquals(3, after.size)
        val moves = after.indices.map { after[it].center.angleToDegrees(before[it]) }
        assertTrue(moves.all { it > 0.0 } && moves.distinct().size > 1, "targets move independently: $moves")
        assertEquals(ShotResult.HIT, s.fire(Vec3.ZERO, after[1].center.normalized(), 2 * second))
        assertEquals(3, s.targets.size)
        val others = s.targets.filter { t -> after.any { it.id == t.id } }
        s.update(3 * second)
        assertEquals(others.map { it.id }, s.targets.filter { t -> others.any { it.id == t.id } }.map { it.id })
        assertNotEquals(others.map { it.center }, s.targets.filter { t -> others.any { it.id == t.id } }.map { it.center })
    }

    @Test
    fun `strafing keeps targets on the screen of a game profile`() {
        val s = ScenarioKind.SWITCH_STRAFE.create(60 * second, 11L, TrainerGeometry.DEFAULT).also { it.start(0L) }
        val limit = TrainerGeometry.DEFAULT.flash.halfFovDegrees
        for (ms in 0..30_000 step 100) {
            s.update(ms * 1_000_000L)
            s.targets.forEach { assertTrue(abs(Math.toDegrees(atan2(it.center.x, -it.center.z))) <= limit + 1e-6) }
        }
    }
}
