package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.optimizer.OptimizationResult
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

/**
 * Review experiment: how well calibrated are the reported confidence and interval? Many seeds, several
 * players, several round budgets. Ground truth is a smooth fit of a dense reference sweep per player.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ReviewCalibrationTest {
    private class Case(val label: String, val player: SimulatedPlayer, val range: SearchRange)

    private class Run(val result: OptimizationResult, val truthOptimum: Double, val trueLoss: Double)

    private val seeds = (System.getenv("AIMTUNE_SEEDS") ?: "40").toInt()

    private val cases = listOf(
        Case("opt 50, range 20-140", SimulatedPlayer().withOptimum(50.0), SearchRange(20.0, 140.0)),
        Case("opt 35, range 20-140", SimulatedPlayer().withOptimum(35.0), SearchRange(20.0, 140.0)),
        Case("opt 80, range 20-140", SimulatedPlayer().withOptimum(80.0), SearchRange(20.0, 140.0)),
        Case("opt 50, noise x1.6", SimulatedPlayer(noiseLevel = 1.6).withOptimum(50.0), SearchRange(20.0, 140.0)),
        Case("opt 50, narrow range 30-50 (mixed style)", SimulatedPlayer().withOptimum(50.0), SearchRange(30.0, 50.0)),
    )

    @Test
    fun calibration() {
        val out = StringBuilder()
        val budgets = listOf(8, 12, 20, 30)
        val all = mutableMapOf<Int, MutableList<Pair<Run, Boolean>>>()
        for (case in cases) {
            val grid = geometricGrid(20.0, 160.0, 17)
            val truth = SmoothCurve(grid, meanScoresFor(sweepInputs(case.player, grid, 24, 99), MetricWeights.DEFAULT))
            val optimum = truth.argmax(case.range.minCm360, case.range.maxCm360)
            out.appendLine("== ${case.label}: truth optimum in range %.1f cm/360, truth curve at 20/35/50/80/140 = %s".format(optimum, listOf(20.0, 35.0, 50.0, 80.0, 140.0).joinToString { "%.1f".format(truth.at(it)) }))
            for (rounds in budgets) {
                val runs = parallelList(seeds) { s ->
                    val result = checkNotNull(playBlindTest(case.player, case.range, rounds, 1000L + s).result)
                    val best = truth.argmax(case.range.minCm360, case.range.maxCm360)
                    val clamped = result.optimumCm360.coerceIn(case.range.minCm360, case.range.maxCm360)
                    Run(result, best, truth.at(best) - truth.at(clamped))
                }
                val within13 = runs.count { errorFactorOf(it.result.optimumCm360, it.truthOptimum) <= 1.3 }
                val covered = runs.count { it.truthOptimum in it.result.intervalLowCm360 * 0.999..it.result.intervalHighCm360 * 1.001 }
                val smallLoss = runs.count { it.trueLoss <= it.result.negligibleDifference }
                val conf = runs.map { it.result.confidencePercent }
                out.appendLine(
                    "  %2d rounds: within x1.3 %3.0f%% | 80%%-interval covers truth %3.0f%% | true loss <= negligible %3.0f%% | mean reported confidence %2.0f%% (median interval ratio hi/lo %.2f, mean true loss %.2f pts)".format(
                        rounds, 100.0 * within13 / seeds, 100.0 * covered / seeds, 100.0 * smallLoss / seeds, conf.average(),
                        runs.map { it.result.intervalHighCm360 / it.result.intervalLowCm360 }.sorted()[seeds / 2], runs.map { it.trueLoss }.average(),
                    ),
                )
                for (run in runs) all.getOrPut(rounds) { mutableListOf() } += run to (it(run))
            }
        }
        out.appendLine()
        out.appendLine("== Calibration of the reported confidence (all cases pooled) ==")
        out.appendLine("rounds  confidence bin   n   P(true loss<=negligible)   P(within x1.3)   P(interval covers truth)")
        for ((rounds, list) in all.toSortedMap()) {
            val bins = listOf(0..39, 40..59, 60..79, 80..100)
            for (bin in bins) {
                val sel = list.filter { it.first.result.confidencePercent in bin }.map { it.first }
                if (sel.isEmpty()) continue
                out.appendLine(
                    "%5d   %3d-%3d%%        %3d   %5.0f%%                    %5.0f%%           %5.0f%%".format(
                        rounds, bin.first, bin.last, sel.size,
                        100.0 * sel.count { it.trueLoss <= it.result.negligibleDifference } / sel.size,
                        100.0 * sel.count { errorFactorOf(it.result.optimumCm360, it.truthOptimum) <= 1.3 } / sel.size,
                        100.0 * sel.count { it.truthOptimum in it.result.intervalLowCm360 * 0.999..it.result.intervalHighCm360 * 1.001 } / sel.size,
                    ),
                )
            }
        }
        write("review-calibration.txt", out.toString())
    }

    private fun it(run: Run) = run.trueLoss <= run.result.negligibleDifference
}
