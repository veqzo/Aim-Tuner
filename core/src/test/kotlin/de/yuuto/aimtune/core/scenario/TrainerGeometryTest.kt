package de.yuuto.aimtune.core.scenario

import de.yuuto.aimtune.core.math.Vec3
import kotlin.math.atan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrainerGeometryTest {
    private val geometry = TrainerGeometry.DEFAULT
    private val second = 1_000_000_000L

    private fun angularDiameter(t: Target) = 2 * Math.toDegrees(kotlin.math.asin(t.radius / t.center.length))

    /** Plays a scenario by hitting every visible target in order and collects all spawned targets. */
    private fun spawned(kind: ScenarioKind, seed: Long, hits: Int): List<Target> {
        val s = kind.create(60 * second, seed, geometry)
        s.start(0L)
        val all = s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }.toMutableList()
        repeat(hits) { i ->
            val t = s.targets.minBy { it.id }
            s.fire(Vec3.ZERO, t.center.normalized(), (i + 1).toLong())
            all += s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }.filter { n -> all.none { it.id == n.id } }
        }
        return all
    }

    @Test
    fun `with measured distances every click target is a head whose size follows from its distance`() {
        val body = AgentBody(0.24, 1.96, 1.75, 1.35, 5.4, 2.97, 1.62)
        val curve = QuantileCurve(listOf(QuantilePoint(0.0, 4.0), QuantilePoint(0.5, 18.0), QuantilePoint(1.0, 60.0)))
        val heads = geometry.copy(body = body, clickTargetDistanceQuantiles = curve, arena = geometry.arena.copy(wallDistanceMeters = 64.0))
        assertEquals(PositiveRange(4.0, 60.0), heads.clickDistanceBounds)
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)) {
            val targets = (1L..10L).flatMap { seed ->
                val s = kind.create(60 * second, seed, heads)
                s.start(0L)
                repeat(40) { i -> s.targets.minByOrNull { it.id }?.let { t -> s.fire(Vec3.ZERO, t.center.normalized(), (i + 1) * second / 2) } }
                s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }
            }
            assertTrue(targets.size > 20, "$kind: ${targets.size} targets")
            assertTrue(targets.all { it.radius == 0.12 }, "$kind: every target is a 0.24 m head")
            assertTrue(targets.all { it.center.length in 4.0 * 0.999..60.0 * 1.001 }, "$kind: distance out of the curve")
            val far = targets.maxBy { it.center.length }
            assertEquals(2 * Math.toDegrees(atan(0.12 / far.center.length)), angularDiameter(far), 1e-4)
        }
        assertFailsWith<IllegalArgumentException> { geometry.copy(clickTargetDistanceQuantiles = curve) }
        assertFailsWith<IllegalArgumentException> { geometry.copy(body = body, clickTargetDistanceQuantiles = curve) }
    }

    @Test
    fun `click targets have the angular size of the geometry whatever their distance`() {
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING)) {
            val targets = (1L..20L).flatMap { spawned(kind, it, 30) }
            val range = geometry.clickTargetDiameterDegrees
            assertTrue(targets.all { angularDiameter(it) in range.min * 0.999..range.max * 1.001 }, "$kind: angular size out of range")
            assertTrue(targets.map { it.center.length }.let { d -> d.min() >= geometry.clickTargetDistanceMeters.min * 0.999 && d.max() <= geometry.clickTargetDistanceMeters.max * 1.001 })
            assertTrue(targets.map { it.radius }.distinct().size > 10, "$kind: sizes should vary with distance and angle")
        }
    }

    @Test
    fun `flick distances follow the angle mix and stay in the field`() {
        val targets = (1L..60L).map { spawned(ScenarioKind.FLICK, it, 40) }
        val steps = targets.flatMap { list -> list.zipWithNext { a, b -> a.center.angleToDegrees(b.center) } }
        val under10 = steps.count { it < 10.5 }.toDouble() / steps.size
        val over40 = steps.count { it > 42.0 }.toDouble() / steps.size
        // The mix says 60 % up to 10 degrees and 5 % above 40; placement near the field edge shifts it a little.
        assertEquals(0.6, under10, 0.1)
        assertTrue(over40 < 0.1, "large flicks: $over40")
        assertTrue(targets.flatten().all { Math.abs(kotlin.math.atan2(it.center.x, -it.center.z) * 180 / Math.PI) <= geometry.fieldYawDegrees + 1e-6 })
    }

    @Test
    fun `tracking has the size, distance and speeds of the geometry and stands still sometimes`() {
        val config = TrackingConfig.from(geometry, 45 * second)
        assertEquals(geometry.trackingDistanceMeters, config.distance)
        val diameter = 2 * Math.toDegrees(atan(config.targetRadius / config.distance))
        assertTrue(diameter in geometry.trackingDiameterDegrees.min..geometry.trackingDiameterDegrees.max)
        val speeds = geometry.trackingSpeedDegreesPerSecond
        assertEquals(Math.toDegrees(3.0 / 14.0), speeds.min, 1e-9)
        assertEquals(Math.toDegrees(6.0 / 14.0), speeds.max, 1e-9)
        val s = TrackingScenario(config, 4)
        s.start(0L)
        s.update(45 * second)
        val points = s.events.filter { it is TargetEvent.Spawned || it is TargetEvent.Waypoint }
        val centers = points.map { if (it is TargetEvent.Spawned) it.target.center else (it as TargetEvent.Waypoint).center }
        val stops = centers.zipWithNext().count { (a, b) -> a.angleToDegrees(b) < 1e-6 }
        assertTrue(stops in 1 until centers.size - 1, "stops: $stops of ${centers.size - 1}")
    }

    @Test
    fun `invalid geometry is refused`() {
        assertFailsWith<IllegalArgumentException> { PositiveRange(2.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { TrainerGeometry(flickAngles = listOf(AngleBucket(20.0, 1.0), AngleBucket(10.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { TrainerGeometry(flickAngles = listOf(AngleBucket(20.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { TrainerGeometry(stopProbability = 1.0) }
    }

    @Test
    fun `the geometry is deterministic for a seed`() {
        val a = spawned(ScenarioKind.FLICK, 7, 10)
        val b = spawned(ScenarioKind.FLICK, 7, 10)
        assertEquals(a, b)
        assertTrue(Random(1).nextInt() != 0)
    }
}
