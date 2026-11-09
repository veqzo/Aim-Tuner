package de.yuuto.aimtune.core.harness

import de.yuuto.aimtune.core.accel.AbCondition
import de.yuuto.aimtune.core.accel.AbOutcome
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AbVerdict
import de.yuuto.aimtune.core.accel.AccelAbPlan
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelModel
import de.yuuto.aimtune.core.accel.DEFAULT_ALPHA_PER_LOOK
import de.yuuto.aimtune.core.accel.DEFAULT_MIN_EFFECT_POINTS
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.evaluateAb
import de.yuuto.aimtune.core.accel.fitAccel
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.duelEngagements
import de.yuuto.aimtune.core.score.scoreInputs
import de.yuuto.aimtune.core.sim.HeadlessRound
import de.yuuto.aimtune.core.sim.SimulatedPlayer
import de.yuuto.aimtune.core.units.SearchRange
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M23h: the sim gates of the duel score (M23g4). The A/B levels and the optimizer were calibrated on the metric score, whose
 * round-to-round spread is much smaller; these gates check that both still hold when the click rounds score duel wins.
 */
class DuelGateTest {
    // The Valorant profile's rules (games/valorant.json trainer.duel): 308 ms miss follow-up, 3 s duel limit.
    private val duel = DuelScorer(DuelScorer.VANDAL_FOLLOW_UP_NANOS, limitNanos = 3_000_000_000L)
    private val clickKinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
    private val wantsFallingGain = SimulatedPlayer(gainPreference = { v -> 1.0 - 0.3 * (1.0 - exp(-v / 6.0)) })

    /** All 12 pairs of one A/B test, plain at [baseCm360] against [accel] at [baseCm360] / [factor]. */
    private fun abOutcomes(player: SimulatedPlayer, seed: Long, accel: AccelModel?, factor: Double, baseCm360: Double = 55.0, sharedSeed: Boolean = true): List<AbOutcome> {
        val plan = AccelAbPlan(clickKinds, seed)
        return (0 until 2 * plan.maxPairs).map { index ->
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val roundSeed = round.seed + if (accelerated && !sharedSeed) 5_000 else 0
            val recording = HeadlessRound.of(
                round.scenario, player, TEST_DPI, if (accelerated) baseCm360 / factor else baseCm360, roundSeed, 45,
                sessionOffsetNanos = index * 45 * 1_000_000_000L, accel = if (accelerated) accel else null,
            )
            AbOutcome(round.pair, round.condition, computeRoundMetrics(recording).scoreInputs(), round.scenario.id, duelEngagements(round.scenario, recording.targetEvents, recording.endNanos))
        }
    }

    /** The test as the app runs it: looked at after every pair, stopped at the first decision. */
    private fun sequential(outcomes: List<AbOutcome>, minEffect: Double, alpha: Double = DEFAULT_ALPHA_PER_LOOK, scorer: DuelScorer? = duel): AbVerdict {
        var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT)
        for (pairs in 1..outcomes.size / 2) {
            verdict = evaluateAb(outcomes.take(2 * pairs), MetricWeights.DEFAULT, alphaPerLook = alpha, minEffectPoints = minEffect, duel = scorer)
            if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
        }
        return verdict
    }

    private fun counts(verdicts: List<AbVerdict>) =
        "better %2d, worse %2d, undecided %2d".format(
            verdicts.count { it.status == AbStatus.ACCEL_BETTER }, verdicts.count { it.status == AbStatus.ACCEL_WORSE }, verdicts.count { it.status == AbStatus.NO_DIFFERENCE },
        )

    @Test
    fun `the blind test keeps its error rates on the duel score`() {
        val nothing = (1L..100L).map { abOutcomes(SimulatedPlayer(), it, accel = null, factor = 1.0, sharedSeed = false) }
        val harmful = (1L..10L).map { abOutcomes(SimulatedPlayer(), it, accel = NaturalCurve(0.25, 2.2), factor = 1.0) }
        val real = (1L..20L).mapNotNull { seed ->
            val metrics = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], wantsFallingGain, TEST_DPI, 55.0, seed * 1_000 + it, 45)) }
            val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI))).recommended ?: return@mapNotNull null
            abOutcomes(wantsFallingGain, seed, fit.curve, fit.sensitivityFactor)
        }

        val report = buildString {
            appendLine("Blind A/B on the duel score, alpha per look $DEFAULT_ALPHA_PER_LOOK, 6-12 pairs")
            for (minEffect in listOf(DEFAULT_MIN_EFFECT_POINTS, 4.0, 6.0, 8.0)) {
                appendLine(
                    "  min effect %4.1f: no difference (100) %s | harmful (10) %s | falling-gain player, offered %d: %s".format(
                        minEffect, counts(nothing.map { sequential(it, minEffect) }), counts(harmful.map { sequential(it, minEffect) }),
                        real.size, counts(real.map { sequential(it, minEffect) }),
                    ),
                )
            }
            val metric = { runs: List<List<AbOutcome>> -> counts(runs.map { sequential(it, DEFAULT_MIN_EFFECT_POINTS, scorer = null) }) }
            appendLine("  metric score (old): no difference ${metric(nothing)} | harmful ${metric(harmful)} | falling gain ${metric(real)}")
            appendLine("  mean paired difference after 12 pairs (duel): no difference " + nothing.map { evaluateAb(it, MetricWeights.DEFAULT, duel = duel).meanDifference }.summary() +
                ", harmful " + harmful.map { evaluateAb(it, MetricWeights.DEFAULT, duel = duel).meanDifference }.summary() +
                ", falling gain " + real.map { evaluateAb(it, MetricWeights.DEFAULT, duel = duel).meanDifference }.summary())
            fun pairSd(scorer: DuelScorer?) = nothing.map { runs ->
                val v = evaluateAb(runs, MetricWeights.DEFAULT, duel = scorer)
                (v.upperBound - v.lowerBound) / 2 / tQuantile99(v.pairs - 1) * kotlin.math.sqrt(v.pairs.toDouble())
            }.average()
            // The stage plan's SCORE_MARGIN (2 points) and the A/B effect limit assume the metric score's noise; this compares them.
            appendLine("  SD of one pair's difference with no real difference: duel %.1f points, metric %.1f points".format(pairSd(duel), pairSd(null)))
        }
        publishReport("duel-ab-gate.txt", report)
        // The app's levels on the duel score: chance alone decides in under 5 % of the null runs per direction, a harmful
        // curve is judged worse, a real improvement is confirmed as often as on the metric score (AccelPipelineTest: 60 %).
        val none = nothing.map { sequential(it, DEFAULT_MIN_EFFECT_POINTS) }
        assertTrue(none.count { it.status == AbStatus.ACCEL_BETTER } < 5 && none.count { it.status == AbStatus.ACCEL_WORSE } < 5, report)
        val bad = harmful.map { sequential(it, DEFAULT_MIN_EFFECT_POINTS) }
        assertTrue(bad.count { it.status == AbStatus.ACCEL_WORSE } >= 7 && bad.none { it.status == AbStatus.ACCEL_BETTER }, report)
        val good = real.map { sequential(it, DEFAULT_MIN_EFFECT_POINTS) }
        assertTrue(real.size >= 16, report)
        assertTrue(good.count { it.status == AbStatus.ACCEL_BETTER } >= real.size * 0.6 && good.none { it.status == AbStatus.ACCEL_WORSE }, report)
    }

    @Test
    fun `the optimizer finds the duel optimum of the simulated player`() {
        val range = SearchRange(20.0, 140.0)
        val grid = geometricGrid(20.0, 140.0, 13)
        val rates = mutableListOf<Int>()
        val report = buildString {
            appendLine("Sensitivity finder on the duel score, flick, 28 rounds, 12 seeds")
            for (optimum in listOf(35.0, 50.0, 80.0)) {
                val player = SimulatedPlayer().withOptimum(optimum)
                val metricTruth = parabolicOptimum(grid, meanScores(player, grid, 16, 77))
                val duelTruth = parabolicOptimum(grid, meanScores(player, grid, 16, 77, duel = duel))
                val estimates = (1L..12L).map { checkNotNull(playBlindTest(player, range, 28, it, duel = duel).result).optimumCm360 }
                val errors = estimates.map { errorFactor(it, duelTruth) }
                rates += errors.count { it <= 1.3 }
                appendLine(
                    "  player optimum %4.0f: metric truth %5.1f, duel truth %5.1f, within x1.3 of the duel truth %2d of 12, median error x%.2f, estimates %s".format(
                        optimum, metricTruth, duelTruth, errors.count { it <= 1.3 }, errors.sorted()[6], estimates.map { "%.0f".format(it) },
                    ),
                )
            }
        }
        publishReport("duel-optimizer-gate.txt", report)
        // The same bar as the metric score (OptimizerConvergenceTest): 60 % of the runs within x1.3.
        assertTrue(rates.all { it >= 8 }, report)
    }

    private fun List<Double>.summary() = if (isEmpty()) "-" else "median %+.1f, range %+.1f to %+.1f".format(sorted()[size / 2], min(), max())

    /** Student's t quantile at the A/B per-look level, as evaluateAb uses it (tQuantile is internal to core main, visible here). */
    private fun tQuantile99(df: Int) = de.yuuto.aimtune.core.accel.tQuantile(df, DEFAULT_ALPHA_PER_LOOK)
}
