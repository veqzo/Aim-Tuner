package dev.veqzo.aimtune.core.harness

import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.FlashImportance
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import java.util.stream.IntStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The flash score must not simply reward the highest sensitivity: turning away is easier the faster the
 * mouse, so the dodge rate alone peaks at the fast end of the range, and the return hit (accuracy and time to
 * hit) is what pulls the combined optimum back. This guards that balance with the simulated player
 * (docs/ALGORITHMS.md "Flash turn-away").
 */
class FlashScoreTest {
    private val player = SimulatedPlayer.forHeadSizedTargets(50.0)
    private val grid = geometricGrid(20.0, 100.0, 8)
    private val roundsEach = 40

    private val inputs: List<List<Map<ScoreMetric, Double>>> by lazy {
        grid.mapIndexed { gi, cm ->
            val rows = arrayOfNulls<Map<ScoreMetric, Double>>(roundsEach)
            IntStream.range(0, roundsEach).parallel().forEach { r ->
                val seed = 31_000L + gi * 1_000 + r
                val recording = HeadlessRound.of(ScenarioKind.FLASH, player, TEST_DPI, cm, seed, 45, r * 45_000_000_000L, geometry = TrainerGeometry.DEFAULT)
                rows[r] = computeRoundMetrics(recording).scoreInputs()
            }
            rows.map { checkNotNull(it) }
        }
    }

    private fun optimum(weights: Map<ScoreMetric, Double>): Pair<Double, List<Double>> {
        val scores = ScoreCalculator(MetricWeights(weights)).scoreRounds(inputs.flatten())
        var i = 0
        val means = inputs.map { group -> group.indices.map { scores[i++] }.filterNotNull().average() }
        return parabolicOptimum(grid, means) to means
    }

    private fun withDodge(importance: FlashImportance) = MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to importance.weight)

    @Test
    fun `the dodge rate alone prefers the fastest sensitivity but the combined score has an interior optimum`() {
        val dodgeOnly = optimum(mapOf(ScoreMetric.FLASH_DODGE to 1.0)).first
        assertTrue(dodgeOnly <= grid[1], "dodge rate alone peaks at the fast end, was $dodgeOnly")
        for (importance in listOf(FlashImportance.SOMETIMES, FlashImportance.OFTEN)) {
            val (best, means) = optimum(withDodge(importance))
            assertTrue(best > grid.first() * 1.2 && best < grid.last() * 0.9, "$importance: optimum $best cm/360 must lie inside ${grid.first()}-${grid.last()}, means $means")
            // With head-level targets (M11) vertical overshoot costs less than in the fully 3D field, so the fast end is penalised less (1.5 points instead of 3+).
            assertTrue(means.max() - means.first() > 1.0, "$importance: the fastest setting must score below the best, means $means")
        }
    }

    @Test
    fun `the importance moves the optimum toward faster sensitivities, but only a little`() {
        val returnOnly = optimum(MetricWeights.DEFAULT.values.filterKeys { it != ScoreMetric.TRACKING }).first
        val sometimes = optimum(withDodge(FlashImportance.SOMETIMES)).first
        val often = optimum(withDodge(FlashImportance.OFTEN)).first
        assertTrue(often <= sometimes * 1.1 && sometimes <= returnOnly * 1.1, "return only $returnOnly, sometimes $sometimes, often $often")
        assertTrue(often >= returnOnly * 0.55, "even 'often' stays within 45 % of the return-only optimum: $returnOnly vs $often")
    }
}
