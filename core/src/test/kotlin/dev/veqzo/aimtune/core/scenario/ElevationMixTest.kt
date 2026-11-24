package dev.veqzo.aimtune.core.scenario

import dev.veqzo.aimtune.core.math.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElevationMixTest {
    private val second = 1_000_000_000L

    /** Hits every target of [kind] in order and returns the pitch of all targets that appeared. */
    private fun pitches(kind: ScenarioKind, geometry: TrainerGeometry, seeds: LongRange, hits: Int): List<Double> = seeds.flatMap { seed ->
        val s = kind.create(60 * second, seed, geometry)
        s.start(0L)
        val seen = mutableListOf<Target>()
        fun collect() = s.events.filterIsInstance<TargetEvent.Spawned>().map { it.target }.filter { n -> seen.none { it.id == n.id } }.also { seen += it }
        collect()
        repeat(hits) { i ->
            val t = s.targets.minBy { it.id }
            s.fire(Vec3.ZERO, t.center.normalized(), (i + 1).toLong())
            collect()
        }
        seen.map { anglesOf(it.center).second }
    }

    @Test
    fun `most targets sit near head level and a small share is above or below`() {
        val geometry = TrainerGeometry.DEFAULT
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING)) {
            val p = pitches(kind, geometry, 1L..40L, 40)
            val nearLevel = p.count { abs(it) <= 3.0 }.toDouble() / p.size
            val offLevel = p.count { abs(it) > 3.5 }.toDouble() / p.size
            assertTrue(nearLevel > 0.8, "$kind near level $nearLevel")
            assertTrue(offLevel in 0.04..0.2, "$kind off level $offLevel")
            assertTrue(p.all { abs(it) <= geometry.fieldPitchDegrees + 1e-6 })
            assertTrue(p.count { it > 3.5 } > p.count { it < -3.5 }, "$kind: more targets above than below")
        }
    }

    @Test
    fun `the old uniform field still reaches the whole pitch range`() {
        val geometry = TrainerGeometry(elevation = ElevationMix.UNIFORM)
        val p = pitches(ScenarioKind.FLICK, geometry, 1L..40L, 40)
        assertTrue(p.count { abs(it) > 10.0 } > p.size / 4, "uniform field should have many targets above 10 degrees")
    }

    @Test
    fun `flick steps are mostly horizontal and keep their size`() {
        val s = ScenarioKind.FLICK.create(60 * second, 3, TrainerGeometry.DEFAULT)
        s.start(0L)
        val centers = mutableListOf(s.targets.single().center)
        repeat(300) { i ->
            s.fire(Vec3.ZERO, s.targets.single().center.normalized(), (i + 1).toLong())
            centers += s.targets.single().center
        }
        val steps = centers.zipWithNext()
        val vertical = steps.map { (a, b) -> abs(anglesOf(b).second - anglesOf(a).second) }
        val total = steps.map { (a, b) -> a.angleToDegrees(b) }
        assertTrue(vertical.average() < 0.35 * total.average(), "vertical ${vertical.average()} of ${total.average()}")
        assertTrue(total.all { it >= 0.9 }, "no step shorter than the minimal flick")
    }

    @Test
    fun `the level pitch follows the height difference and distance`() {
        val g = TrainerGeometry(eyeHeightMeters = 1.6, headHeightMeters = 1.7)
        assertEquals(Math.toDegrees(Math.atan(0.1 / 10.0)), g.levelPitchDegrees(10.0), 1e-9)
        assertTrue(g.levelPitchDegrees(5.0) > g.levelPitchDegrees(20.0))
        assertEquals(0.0, TrainerGeometry(eyeHeightMeters = 1.6, headHeightMeters = 1.6).levelPitchDegrees(8.0), 1e-12)
    }

    @Test
    fun `drawing is deterministic and clamped to the field`() {
        val mix = ElevationMix(offLevelShare = 1.0, offLevelDegrees = PositiveRange(30.0, 40.0))
        val a = List(50) { mix.draw(Random(it), 0.0, 20.0) }
        assertEquals(a, List(50) { mix.draw(Random(it), 0.0, 20.0) })
        assertTrue(a.all { abs(it) <= 20.0 })
    }

    @Test
    fun `invalid mixes are refused`() {
        assertFailsWith<IllegalArgumentException> { ElevationMix(offLevelShare = 1.5) }
        assertFailsWith<IllegalArgumentException> { ElevationMix(offLevelDegrees = PositiveRange(5.0, 60.0)) }
        assertFailsWith<IllegalArgumentException> { TrainerGeometry(eyeHeightMeters = 0.1) }
    }
}
