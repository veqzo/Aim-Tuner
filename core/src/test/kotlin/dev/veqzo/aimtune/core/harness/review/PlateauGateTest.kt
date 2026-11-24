package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * T8: how honest is the equivalent stretch (the plateau of cm/360 whose posterior mean is within the negligible difference
 * of the best)? Same setup as section S of the gate: single session, optimum 50 cm/360, range 20-140, truth from the smooth
 * fit of the reference sweep of each model's player.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class PlateauGateTest {
    private class Run(
        val containsTruth: Boolean, val ratio: Double, val flat: Boolean, val negligible: Double,
        val lossLow: Double, val lossHigh: Double, val covered80: Boolean,
    )

    @Test
    fun plateau() {
        val seeds = 40
        val range = SearchRange(20.0, 140.0)
        val grid = geometricGrid(20.0, 160.0, 17)
        val out = StringBuilder()
        out.appendLine("-- T8 plateau: single session, opt 50 cm/360, range 20-140, $seeds seeds. Truth = smooth fit of the reference sweep.")
        out.appendLine("model      rounds truth  eqCover  ratioMed ratioP90  flat   negMed  lossLowMed lossHighMed  cover80")
        for (name in listOf("DEFAULT", "DECOUPLED", "RANGE")) {
            val model = gateModels.first { it.name == name }
            val base = SimulatedPlayer().withOptimum(50.0)
            val stateless = model.apply(base).copy(adaptation = null)
            val truth = SmoothCurve(grid, meanScoresFor(sweepInputs(stateless, grid, 24, 99), MetricWeights.DEFAULT))
            val best = truth.argmax(range.minCm360, range.maxCm360)
            for (rounds in listOf(8, 15, 30)) {
                val runs = parallelList(seeds) { s ->
                    val r = checkNotNull(playBlindTest(model.apply(base), range, rounds, 1000L + s).result)
                    val lo = r.equivalentLowCm360
                    val hi = r.equivalentHighCm360
                    Run(
                        best in lo * 0.999..hi * 1.001, hi / lo, r.isFlat, r.negligibleDifference,
                        truth.at(best) - truth.at(lo.coerceIn(range.minCm360, range.maxCm360)),
                        truth.at(best) - truth.at(hi.coerceIn(range.minCm360, range.maxCm360)),
                        best in r.intervalLowCm360 * 0.999..r.intervalHighCm360 * 1.001,
                    )
                }
                out.appendLine(
                    "%-10s %5d %5.1f  %5.0f%%   %7.3f  %7.3f  %4.0f%%  %6.2f   %8.2f    %8.2f    %5.0f%%".format(
                        name, rounds, best, 100.0 * runs.count { it.containsTruth } / seeds,
                        median(runs.map { it.ratio }), percentileOf(runs.map { it.ratio }, 0.9),
                        100.0 * runs.count { it.flat } / seeds, median(runs.map { it.negligible }),
                        median(runs.map { it.lossLow }), median(runs.map { it.lossHigh }),
                        100.0 * runs.count { it.covered80 } / seeds,
                    ),
                )
            }
        }
        write("gate-t8-plateau.txt", out.toString())
    }
}
