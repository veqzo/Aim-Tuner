package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlickScenarioTest {
    private val config = FlickConfig(durationNanos = 10_000_000_000L)
    private val origin = Vec3.ZERO

    private fun aimAt(target: Target) = target.center.normalized()

    @Test
    fun `same seed produces the same target sequence`() {
        fun sequence(seed: Long): List<Vec3> {
            val s = FlickScenario(config, seed)
            s.start(0)
            return List(20) {
                val t = s.targets.single()
                s.fire(origin, aimAt(t), it + 1L)
                t.center
            }
        }
        assertEquals(sequence(42), sequence(42))
        assertNotEquals(sequence(42), sequence(43))
    }

    @Test
    fun `targets stay inside configured ranges`() {
        val s = FlickScenario(config, seed = 7)
        s.start(0)
        repeat(200) {
            val t = s.targets.single()
            assertTrue(t.center.length in config.minDistance..config.maxDistance)
            val pitch = Math.toDegrees(asin(t.center.y / t.center.length))
            assertTrue(pitch in -config.maxPitchDegrees..config.maxPitchDegrees)
            s.fire(origin, aimAt(t), it + 1L)
        }
    }

    @Test
    fun `consecutive targets are separated`() {
        val s = FlickScenario(config, seed = 3)
        s.start(0)
        var previous = s.targets.single()
        repeat(100) {
            s.fire(origin, aimAt(previous), it + 1L)
            val next = s.targets.single()
            assertTrue(next.center.angleToDegrees(previous.center) >= config.minSeparationDegrees)
            previous = next
        }
    }

    @Test
    fun `hit spawns next target and logs events`() {
        val s = FlickScenario(config, seed = 1)
        s.start(0)
        val first = s.targets.single()
        assertEquals(ShotResult.HIT, s.fire(origin, aimAt(first), 5))
        assertNotEquals(first.id, s.targets.single().id)
        assertTrue(s.events.first() is TargetEvent.Spawned)
        assertTrue(s.events.any { it is TargetEvent.Hit && it.targetId == first.id })
    }

    @Test
    fun `miss keeps the target and logs a miss`() {
        val s = FlickScenario(config, seed = 1)
        s.start(0)
        val first = s.targets.single()
        val away = Vec3(-first.center.x, first.center.y, first.center.z).normalized()
        assertEquals(ShotResult.MISS, s.fire(origin, away, 5))
        assertEquals(first.id, s.targets.single().id)
        assertTrue(s.events.last() is TargetEvent.Miss)
    }

    @Test
    fun `shots outside the round are ignored`() {
        val s = FlickScenario(config, seed = 1)
        val dir = Vec3(0.0, 0.0, -1.0)
        assertEquals(ShotResult.IGNORED, s.fire(origin, dir, 0))
        s.start(0)
        assertFalse(s.isFinished(config.durationNanos - 1))
        assertTrue(s.isFinished(config.durationNanos))
        assertEquals(ShotResult.IGNORED, s.fire(origin, aimAt(s.targets.single()), config.durationNanos))
    }

    @Test
    fun `target behind the player is not hit`() {
        val t = Target(0, Vec3(0.0, 0.0, 10.0), 1.0)
        assertFalse(t.isHitBy(origin, Vec3(0.0, 0.0, -1.0)))
        assertTrue(t.isHitBy(origin, Vec3(0.0, 0.0, 1.0)))
    }
}
