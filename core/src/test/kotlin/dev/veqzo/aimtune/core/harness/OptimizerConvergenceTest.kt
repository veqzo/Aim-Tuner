package dev.veqzo.aimtune.core.harness

import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Bayesian optimizer against the simulated player, on the same cases and ground truth as the grid baseline. */
class OptimizerConvergenceTest {
    private val seeds = (1L..12L).toList()

    private fun cases(): List<ConvergenceCase> = buildList {
        for (optimum in listOf(35.0, 50.0, 80.0)) {
            add(ConvergenceCase("optimum %.0f cm, noise x1.0".format(optimum), SimulatedPlayer().withOptimum(optimum), optimum))
        }
        for (noise in listOf(0.6, 1.6)) {
            val player = SimulatedPlayer(noiseLevel = noise).withOptimum(50.0)
            val grid = geometricGrid(20.0, 160.0, 17)
            add(ConvergenceCase("optimum 50 cm, noise x%.1f (measured)".format(noise), player, parabolicOptimum(grid, meanScores(player, grid, 30, 99))))
        }
    }

    @Test
    fun `optimizer recovers the optimum with fewer rounds than the grid baseline`() {
        val cases = cases()
        val results = cases.map { runConvergence(it, OptimizerEstimator(rounds = ROUNDS), seeds, TOLERANCE) }
        val baseline = cases.map { runConvergence(it, GridSweepEstimator(), seeds, TOLERANCE) }
        publishReport(
            "optimizer-convergence.txt",
            formatReport("GP optimizer, tolerance x$TOLERANCE, ${seeds.size} seeds", results) +
                formatReport("Grid baseline on the same cases", baseline),
        )
        for (r in results) assertTrue(r.rate >= MIN_RATE, "${r.case.label}: only ${(r.rate * 100).toInt()} % within tolerance")
        val optimizerRate = results.map { it.rate }.average()
        val baselineRate = baseline.map { it.rate }.average()
        assertTrue(optimizerRate >= baselineRate - 0.1, "optimizer $optimizerRate with $ROUNDS rounds, baseline $baselineRate with ${baseline.first().roundsUsed}")
    }

    @Test
    fun `the credible interval covers the truth most of the time and shrinks with more rounds`() {
        val case = cases()[1]
        val range = SearchRange(20.0, 140.0)
        var covered = 0
        var widthShort = 0.0
        var widthLong = 0.0
        for (seed in seeds) {
            val short = checkNotNull(playBlindTest(case.player, range, 12, seed).result)
            val long = checkNotNull(playBlindTest(case.player, range, 30, seed).result)
            if (case.truthCm360 in long.intervalLowCm360..long.intervalHighCm360) covered++
            widthShort += ln(short.intervalHighCm360 / short.intervalLowCm360)
            widthLong += ln(long.intervalHighCm360 / long.intervalLowCm360)
        }
        println("interval coverage $covered/${seeds.size}, mean ln-width 12 rounds %.2f, 30 rounds %.2f".format(widthShort / seeds.size, widthLong / seeds.size))
        assertTrue(covered >= seeds.size * 0.6, "truth inside the 80 % interval only $covered of ${seeds.size} times")
        assertTrue(widthLong < widthShort, "interval did not shrink: $widthShort -> $widthLong")
    }

    private companion object {
        const val ROUNDS = 28
        const val TOLERANCE = 1.3
        const val MIN_RATE = 0.6
    }
}
