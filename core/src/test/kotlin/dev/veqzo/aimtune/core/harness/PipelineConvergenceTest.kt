package dev.veqzo.aimtune.core.harness

import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end without a human: simulated rounds -> segmentation -> metrics -> score -> estimator,
 * compared with the ground truth the player was configured with. The reference sweep test checks
 * that the configured optimum is really where the pipeline's score peaks.
 */
class PipelineConvergenceTest {
    private val seeds = (1L..8L).toList()

    @Test
    fun `reference sweep peaks at the configured optimum`() {
        val grid = geometricGrid(20.0, 160.0, 17)
        for (optimum in listOf(35.0, SimulatedPlayer.BASE_OPTIMUM_CM360, 80.0)) {
            val player = SimulatedPlayer().withOptimum(optimum)
            val peak = parabolicOptimum(grid, meanScores(player, grid, 30, seed = 99))
            assertTrue(errorFactor(peak, optimum) < 1.2, "optimum $optimum: sweep peak $peak")
        }
    }

    @Test
    fun `baseline estimator recovers the optimum across optima and noise levels`() {
        val estimator = GridSweepEstimator()
        val cases = buildList {
            // At noise 1.0 the configured optimum is the truth (checked by the reference sweep test).
            for (optimum in listOf(35.0, 50.0, 80.0)) {
                add(ConvergenceCase("optimum %.0f cm, noise x1.0".format(optimum), SimulatedPlayer().withOptimum(optimum), optimum))
            }
            // Noise moves the true optimum (precision matters less or more), so the truth is measured by a large reference sweep.
            for (noise in listOf(0.6, 1.6)) {
                val player = SimulatedPlayer(noiseLevel = noise).withOptimum(50.0)
                add(ConvergenceCase("optimum 50 cm, noise x%.1f (measured)".format(noise), player, referenceOptimum(player)))
            }
        }
        val results = cases.map { runConvergence(it, estimator, seeds, TOLERANCE) }
        publishReport("baseline-convergence.txt", formatReport("Grid sweep baseline, tolerance x$TOLERANCE, ${seeds.size} seeds", results))
        for (r in results) assertTrue(r.rate >= MIN_RATE, "${r.case.label}: only ${(r.rate * 100).toInt()} % within tolerance")
        assertEquals(36, results.first().roundsUsed)
    }

    @Test
    fun `fatigue makes the same player slower`() {
        val fresh = SimulatedPlayer()
        val tired = SimulatedPlayer(fatiguePerMinute = 0.3)
        val freshHits = totalHits(fresh)
        val tiredHits = totalHits(tired)
        assertTrue(tiredHits < freshHits, "fresh $freshHits hits, tired $tiredHits hits")
    }

    private fun totalHits(player: SimulatedPlayer): Int =
        (1L..6L).sumOf { seed ->
            dev.veqzo.aimtune.core.sim.HeadlessRound.flick(player, TEST_DPI, 50.0, seed, 45, sessionOffsetNanos = 10 * 60_000_000_000L)
                .targetEvents.count { it is dev.veqzo.aimtune.core.scenario.TargetEvent.Hit }
        }

    /** Peak of a dense sweep (17 points x 30 rounds); the ground truth where the model has no closed form. */
    private fun referenceOptimum(player: SimulatedPlayer): Double {
        val grid = geometricGrid(20.0, 160.0, 17)
        return parabolicOptimum(grid, meanScores(player, grid, 30, seed = 99))
    }

    private companion object {
        const val TOLERANCE = 1.3
        const val MIN_RATE = 0.75
    }
}
