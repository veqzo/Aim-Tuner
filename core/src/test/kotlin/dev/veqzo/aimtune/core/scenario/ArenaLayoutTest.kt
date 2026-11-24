package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaLayoutTest {
    private val second = 1_000_000_000L

    /** A feature every 5 degrees, so every drawn yaw has one within the snap angle. */
    private val denseFeatures = (-18..18).map { ArenaFeature(ArenaFeatureKind.CORNER, it * 5.0, 12.0) }

    private fun spawned(kind: ScenarioKind, geometry: TrainerGeometry, seeds: LongRange, hits: Int): List<Target> = seeds.flatMap { seed ->
        val s = kind.create(60 * second, seed, geometry)
        s.start(0L)
        val seen = mutableListOf<Target>()
        fun collect() = s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }.filter { n -> seen.none { it.id == n.id } }.also { seen += it }
        collect()
        repeat(hits) { i ->
            s.fire(Vec3.ZERO, s.targets.minBy { it.id }.center.normalized(), (i + 1).toLong())
            collect()
        }
        seen
    }

    @Test
    fun `a feature is only found within the snap angle`() {
        val layout = ArenaLayout(features = listOf(ArenaFeature(ArenaFeatureKind.CORNER, 10.0, 12.0)), snapDegrees = 2.0)
        assertNotNull(layout.featureNear(11.5))
        assertNull(layout.featureNear(13.0))
        assertNull(ArenaLayout.NONE.copy(features = emptyList()).featureNear(0.0))
    }

    @Test
    fun `about the configured share of flick and switching targets stands at a feature`() {
        val geometry = TrainerGeometry(arena = ArenaLayout(features = denseFeatures, featureShare = 0.5, snapDegrees = 10.0))
        val features = geometry.arena.features
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING)) {
            val targets = spawned(kind, geometry, 1L..30L, 40)
            val atFeature = targets.count { t ->
                val (yaw, _) = anglesOf(t.center)
                features.any { abs(it.yawDegrees - yaw) < 1e-6 && abs(it.distanceMeters - t.center.length) < 1e-6 }
            }.toDouble() / targets.size
            assertEquals(0.5, atFeature, 0.1, "$kind")
        }
    }

    @Test
    fun `snapping keeps the angular size and the flick angles close to the profile`() {
        val geometry = TrainerGeometry.DEFAULT
        val targets = (1L..40L).map { spawned(ScenarioKind.FLICK, geometry, it..it, 40) }
        val diameters = targets.flatten().map { 2 * Math.toDegrees(kotlin.math.asin(it.radius / it.center.length)) }
        assertTrue(diameters.all { it in geometry.clickTargetDiameterDegrees.min * 0.999..geometry.clickTargetDiameterDegrees.max * 1.001 })
        val steps = targets.flatMap { list -> list.zipWithNext { a, b -> a.center.angleToDegrees(b.center) } }
        // The distance change of a snap moves the target a little in angle too, so allow a wider tolerance than without arena features.
        assertEquals(0.6, steps.count { it < 10.5 + geometry.arena.snapDegrees }.toDouble() / steps.size, 0.12)
    }

    @Test
    fun `no features and the old uniform field leave the placement alone`() {
        val plain = TrainerGeometry(arena = ArenaLayout.NONE)
        assertEquals(spawned(ScenarioKind.FLICK, plain, 3L..3L, 20), spawned(ScenarioKind.FLICK, plain.copy(arena = ArenaLayout(featureShare = 0.0)), 3L..3L, 20))
        val uniform = TrainerGeometry(elevation = ElevationMix.UNIFORM, arena = ArenaLayout(features = denseFeatures, featureShare = 1.0, snapDegrees = 10.0))
        val targets = spawned(ScenarioKind.FLICK, uniform, 1L..10L, 20)
        assertTrue(targets.none { t -> uniform.arena.features.any { abs(it.distanceMeters - t.center.length) < 1e-9 } })
    }

    @Test
    fun `invalid layouts are refused`() {
        assertFailsWith<IllegalArgumentException> { ArenaLayout(wallDistanceMeters = 5.0) }
        assertFailsWith<IllegalArgumentException> { ArenaLayout(featureShare = 2.0) }
        assertFailsWith<IllegalArgumentException> { ArenaFeature(ArenaFeatureKind.CORNER, 200.0, 5.0) }
    }
}

class ArenaVisibilityTest {
    private val second = 1_000_000_000L

    /** True when a pillar or door post nearer than the target covers it from the origin. */
    private fun hidden(g: TrainerGeometry, t: Target): Boolean {
        val (yaw, _) = anglesOf(t.center)
        val halfSize = Math.toDegrees(kotlin.math.asin(t.radius / t.center.length))
        return g.arena.occluderFor(yaw, t.center.length, halfSize) != null
    }

    @Test
    fun `no click target stands behind a pillar or a door post`() {
        val g = TrainerGeometry.DEFAULT
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)) {
            var total = 0
            var covered = 0
            for (seed in 1L..60L) {
                val s = kind.create(60 * second, seed, g)
                s.start(0L)
                val seen = mutableListOf<Target>()
                repeat(40) { i ->
                    seen += s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }.filter { n -> seen.none { it.id == n.id } }
                    s.fire(Vec3.ZERO, s.targets.minBy { it.id }.center.normalized(), (i + 1).toLong())
                }
                total += seen.size
                covered += seen.count { hidden(g, it) }
            }
            assertEquals(0, covered, "$kind: $covered of $total targets were hidden by the map")
        }
    }

    @Test
    fun `the occluders follow the sizes of the scene`() {
        val layout = ArenaLayout(features = listOf(ArenaFeature(ArenaFeatureKind.CORNER, 10.0, 10.0), ArenaFeature(ArenaFeatureKind.DOORWAY, -20.0, 20.0)))
        val o = layout.occluders()
        assertEquals(3, o.size, "one pillar and two door posts")
        val pillar = o.first()
        assertTrue(pillar.yawLow > 10.0 && pillar.yawHigh > pillar.yawLow, "the first pillar is to the right of its enemy")
        assertNull(layout.occluderFor(10.0, 10.0, 0.0), "a target at the corner itself is not hidden by it")
        assertNotNull(layout.occluderFor((pillar.yawLow + pillar.yawHigh) / 2, 25.0, 0.0), "a target far behind the pillar is hidden")
        assertNull(layout.occluderFor((pillar.yawLow + pillar.yawHigh) / 2, 5.0, 0.0), "a target in front of it is not")
    }
}
