package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TargetEvent
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.atan
import kotlin.test.Test

/** Review: angular sizes and flick distances the scenarios really produce, with the crosshair assumed on the previous target. */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ReviewGeometryTest {
    private fun pct(v: List<Double>, p: Double) = v.sorted()[((v.size - 1) * p).toInt()]

    @Test
    fun geometry() {
        val out = StringBuilder("== Angular geometry of the scenarios with the profile defaults (TrainerGeometry.DEFAULT) ==\n")
        for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.MICRO, ScenarioKind.SWITCHING)) {
            val diameters = mutableListOf<Double>()
            val distances = mutableListOf<Double>()
            for (seed in 1L..300L) {
                val s = kind.create(45_000_000_000L, seed, dev.veqzo.aimtune.core.scenario.TrainerGeometry.DEFAULT)
                s.start(0L)
                var previous: Vec3? = null
                // Walk through targets in spawn order by hitting the visible one with a perfect aim.
                repeat(30) {
                    val t = s.targets.minBy { it.center.length }
                    diameters += 2 * Math.toDegrees(atan(t.radius / t.center.length))
                    previous?.let { distances += it.angleToDegrees(t.center) }
                    previous = t.center
                    s.fire(Vec3.ZERO, t.center.normalized(), 1L)
                }
            }
            out.appendLine(
                "%-10s target angular diameter deg p5/p50/p95 = %.2f/%.2f/%.2f; distance between consecutive targets deg p10/p50/p90 = %.0f/%.0f/%.0f; share < 10 deg %.0f%%, < 20 deg %.0f%%".format(
                    kind.id, pct(diameters, .05), pct(diameters, .5), pct(diameters, .95), pct(distances, .1), pct(distances, .5), pct(distances, .9),
                    100.0 * distances.count { it < 10 } / distances.size, 100.0 * distances.count { it < 20 } / distances.size,
                ),
            )
        }
        write("review-geometry.txt", out.toString())
    }
}
