package dev.veqzo.aimtune.core.harness

import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The optimizer on a blind test that rotates scenarios. Every scenario has its own best
 * sensitivity for the simulated player, so the truth is the weighted blend of the scenarios'
 * score curves, computed from a dense reference sweep.
 */
class MixedScenarioConvergenceTest {
    private val range = SearchRange(20.0, 140.0)
    private val grid = geometricGrid(20.0, 140.0, 17)
    private val player = SimulatedPlayer()
    private val seeds = (1L..16L).toList()
    private val rounds = 30

    private fun singleOptimum(kind: ScenarioKind): Pair<Double, List<Double>> {
        val means = meanScores(player, grid, roundsEach = 24, seed = 500, kind = kind)
        return parabolicOptimum(grid, means) to means
    }

    @Test
    fun `mixed scenarios recover the weighted blend of the scenario optima`() {
        val weights = MetricWeights.DEFAULT
        val enabled = ScenarioKind.entries.toSet() - ScenarioKind.FLASH
        val schedule = ScenarioSchedule(enabled, weights)
        val references = (ScenarioKind.entries - ScenarioKind.FLASH).associateWith { singleOptimum(it) }
        val blend = grid.indices.map { gi -> references.entries.sumOf { (kind, ref) -> schedule.shares.getValue(kind) * ref.second[gi] } }
        val blendedTruth = parabolicOptimum(grid, blend)

        val mixed = seeds.map { seed ->
            playBlindTest(player, range, rounds, seed, scenarioFor = schedule::kindFor, weights = weights)
        }
        val flickOnly = seeds.take(FLICK_ONLY_SEEDS).map { seed -> playBlindTest(player, range, rounds, seed) }
        val flickTruth = references.getValue(ScenarioKind.FLICK).first

        val report = buildString {
            appendLine("Mixed scenarios, $rounds rounds, default weights, shares " + schedule.shares.entries.joinToString { "${it.key.id} %.2f".format(it.value) })
            references.forEach { (kind, ref) -> appendLine("  reference optimum ${kind.id}: %.1f cm/360".format(ref.first)) }
            appendLine("  blended truth: %.1f cm/360".format(blendedTruth))
            fun line(label: String, tests: List<SimulatedTest>, truth: Double, curve: List<Double>) {
                val errors = tests.map { errorFactor(checkNotNull(it.result).optimumCm360, truth) }
                val regrets = tests.map { curve.max() - curveAt(curve, checkNotNull(it.result).optimumCm360) }
                appendLine(
                    "  %-10s within x1.3 of %.1f: %3.0f%%  median error x%.2f  median regret %.1f pts  mean confidence %.0f%%".format(
                        label, truth, errors.count { it <= TOLERANCE } * 100.0 / errors.size, errors.sorted()[errors.size / 2],
                        regrets.sorted()[regrets.size / 2], tests.map { checkNotNull(it.result).confidencePercent }.average(),
                    ),
                )
                val confident = tests.indices.filter { checkNotNull(tests[it].result).confidencePercent >= CONFIDENT_PERCENT }
                appendLine(
                    "      runs with confidence >= $CONFIDENT_PERCENT%%: ${confident.size} of ${tests.size}, " +
                        "of those with regret <= $ACCEPTABLE_REGRET pts: ${confident.count { regrets[it] <= ACCEPTABLE_REGRET }}",
                )
                tests.indices.forEach { i ->
                    val r = checkNotNull(tests[i].result)
                    appendLine(
                        "      seed %2d: estimate %6.1f  confidence %3d%%  regret %4.1f pts  (ls %.2f, signal %.1f, noise %.2f, session %.1f, group %.1f)".format(
                            seeds[i], r.optimumCm360, r.confidencePercent, regrets[i],
                            r.hyper.lengthscale, r.hyper.signalVariance, r.hyper.noiseVariance, r.hyper.sessionVariance, r.hyper.groupVariance,
                        ),
                    )
                }
            }
            line("mixed", mixed, blendedTruth, blend)
            line("flick only", flickOnly, flickTruth, references.getValue(ScenarioKind.FLICK).second)
        }
        publishReport("mixed-scenarios.txt", report)

        val results = mixed.map { checkNotNull(it.result) }
        val errors = results.map { errorFactor(it.optimumCm360, blendedTruth) }
        val regrets = results.map { blend.max() - curveAt(blend, it.optimumCm360) }
        assertTrue(errors.count { it <= TOLERANCE } >= seeds.size * MIN_WITHIN_TOLERANCE, "runs within x$TOLERANCE of the blend: $errors")
        assertTrue(regrets.sorted()[regrets.size / 2] <= MAX_MEDIAN_REGRET, "median regret ${regrets.sorted()[regrets.size / 2]}")
        // A best value at the end of the searched range is only a slope, so it must never come with a high confidence.
        results.filter { it.atRangeEdge }.forEach { assertTrue(it.confidencePercent <= EDGE_CONFIDENCE_CAP, "edge result with ${it.confidencePercent}%") }
    }

    /** The mean score curve at [cm360], interpolated linearly in log space between grid points. */
    private fun curveAt(curve: List<Double>, cm360: Double): Double {
        val x = ln(cm360 / grid.first()) / ln(grid.last() / grid.first()) * grid.lastIndex
        val lower = x.toInt().coerceIn(0, grid.lastIndex - 1)
        val fraction = (x - lower).coerceIn(0.0, 1.0)
        return curve[lower] * (1 - fraction) + curve[lower + 1] * fraction
    }

    private companion object {
        const val TOLERANCE = 1.3
        const val FLICK_ONLY_SEEDS = 8
        const val MIN_WITHIN_TOLERANCE = 0.55
        const val MAX_MEDIAN_REGRET = 3.0
        const val EDGE_CONFIDENCE_CAP = 50
        const val CONFIDENT_PERCENT = 80
        const val ACCEPTABLE_REGRET = 3.0
    }
}
