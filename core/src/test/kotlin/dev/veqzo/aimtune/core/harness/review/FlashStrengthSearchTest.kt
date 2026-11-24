package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelScenarios
import dev.veqzo.aimtune.core.accel.CurveStrength
import dev.veqzo.aimtune.core.accel.DEFAULT_MIN_EFFECT_POINTS
import dev.veqzo.aimtune.core.accel.LookupCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.strengthAccel
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow

/**
 * M20: the strength search of the app plays flash turn-away rounds in half of its rounds when flash matters to the player
 * (`AccelRun(flash = true)`, strength range up to 1.75, the blind test with flash pairs too). M19 and the audit simulated click
 * rounds only (docs/ALGORITHMS.md, "Two-stage strength search"). This test runs the same chain (24-round strength search at the
 * game sensitivity, then the blind A/B test of the found curve) on the same five hands with and without flash rounds and asks:
 * does flash change what a search finds, and does it add harmful or falling exports?
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class FlashStrengthSearchTest {
    private val n = System.getenv("AIMTUNE_SEEDS")?.toIntOrNull()?.let { (24 * it / 40).coerceAtLeast(4) } ?: 24
    private val base = CALIBRATION_BASE_CM360
    private val out = StringBuilder()

    private fun rawMult(x: Double): Double {
        val off = 10.0
        val capX = 62.5
        val a = 0.6 / 2.0 / (capX - off)
        fun baseFn(v: Double) = a * (v - off).pow(2.0) / v
        val constant = (baseFn(capX) - 0.6) * capX
        return when {
            x <= off -> 1.0
            x < capX -> 1.0 + baseFn(x)
            else -> 1.0 + constant / x + 0.6
        }
    }

    private val userCurve: AccelCurve = run {
        val geo = (0 until 255).map { 0.5 * (150.0 / 0.5).pow(it / 254.0) }
        val speeds = (geo + listOf(10.0, 62.5)).sorted().fold(mutableListOf<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 1e-3) acc.add(v); acc }
        LookupCurve(speeds, speeds.map { rawMult(it) })
    }

    private fun gainPlayer(change: Double) = SimulatedPlayer(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })

    private class Model(val name: String, val dir: Int, val make: () -> SimulatedPlayer)

    private val models = listOf(
        Model("ADAPTED", 0) { SimulatedPlayer(adaptation = HandAdaptation.adaptedTo({ 1.0 }, delivered = { 0.75 * rawMult(it) })) },
        Model("ADAPTED_HIGH", 0) { SimulatedPlayer(adaptation = HandAdaptation.adaptedTo({ 1.0 }, delivered = { 0.53 * userCurve.multiplier(it) })) },
        Model("FLAT", 0) { SimulatedPlayer() },
        Model("RISING", 1) { gainPlayer(0.30) },
        Model("FALLING", -1) { gainPlayer(-0.30) },
    )

    private class Variant(val label: String, val flash: Boolean, val flashWeight: Double) {
        val kinds: List<ScenarioKind> = AccelScenarios.of(flash)
        val range: SearchRange = CurveStrength.searchRange(flash).let { SearchRange(it.minCm360, it.maxCm360) }
        val weights: MetricWeights =
            if (flash) MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to flashWeight)) else MetricWeights.DEFAULT
    }

    private class Run(val best: Double, val offered: Boolean, val exported: Boolean, val pd: Double)

    /** All rounds of an A/B test over [v]'s scenarios: the curve at [base] with the factor 1 against plain at [base]. */
    private fun abRounds(v: Variant, p: SimulatedPlayer, curve: AccelCurve, seed: Long, pairs: Int = 12): List<AbOutcome> {
        val plan = AccelAbPlan(v.kinds, seed, pairs)
        return (0 until 2 * pairs).map { index ->
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val rec = HeadlessRound.of(
                round.scenario, p, TEST_DPI, base, round.seed, ROUND_SECONDS,
                sessionOffsetNanos = index * ROUND_SECONDS * NANOS_PER_SECOND, accel = if (accelerated) curve else null,
            )
            AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs(), round.scenario.id)
        }
    }

    /** Mean paired score difference (curve minus plain) over 12 pairs of the variant's own plan and weights. */
    private fun paired(v: Variant, p: SimulatedPlayer, curve: AccelCurve, seed: Long): Double {
        val outcomes = abRounds(v, p, curve, seed)
        val scores = ScoreCalculator(v.weights).scoreRounds(outcomes.map { it.inputs }, outcomes.map { checkNotNull(it.scenarioId) })
        val diffs = (0 until outcomes.size / 2).mapNotNull { pair ->
            val a = (2 * pair..2 * pair + 1).first { outcomes[it].condition == AbCondition.ACCEL }
            val b = (2 * pair..2 * pair + 1).first { outcomes[it].condition == AbCondition.PLAIN }
            val sa = scores[a]
            val sb = scores[b]
            if (sa != null && sb != null) sa - sb else null
        }
        return diffs.average()
    }

    private fun run(v: Variant, model: Model, s: Int): Run {
        val p = model.make()
        val res = checkNotNull(
            playBlindTest(
                p, v.range, 24, 600L + s, scenarioFor = { v.kinds[it % v.kinds.size] }, weights = v.weights,
                accelOf = { CurveStrength.curve(it / 100.0) }, playedCm360 = base,
            ).result,
        )
        val best = res.optimumCm360 / 100.0
        val offered = CurveStrength.excludesPlain(best, res.intervalLowCm360 / 100.0, res.intervalHighCm360 / 100.0)
        if (!offered) return Run(best, false, false, Double.NaN)
        val curve = strengthAccel(best).curve
        val outcomes = abRounds(v, p, curve, 800L + s)
        var status = AbStatus.NO_DIFFERENCE
        for (pair in 1..outcomes.size / 2) {
            val verdict = evaluateAb(outcomes.take(2 * pair), v.weights, alphaPerLook = 0.01, minEffectPoints = DEFAULT_MIN_EFFECT_POINTS, groupByScenario = false)
            if (verdict.status != AbStatus.NEED_MORE_ROUNDS) { status = verdict.status; break }
        }
        return Run(best, true, status == AbStatus.ACCEL_BETTER, paired(v, p, curve, 700L + s))
    }

    private fun pct(c: Int, total: Int) = String.format(Locale.ROOT, "%3.0f%%", 100.0 * c / total)

    @Test
    fun flashInTheStrengthSearch() {
        out.appendLine("== M20 flash rounds in the strength search @ git ${gitShortHash()} | $n seeds per row | 24 rounds at base $base, A/B 12 pairs ==")
        out.appendLine("click only = the app without flash (3 click scenarios, strengths 0.70-1.45); flash = AccelScenarios.WITH_FLASH (half flash rounds, strengths 0.70-1.75, flash dodge weight 15 sometimes / 35 often)")
        out.appendLine("fall = best < 1; right = exported in the true direction; harm = offered with a negative paired benefit under the variant's own weights; pd = median paired benefit of the offered curves (score points)")
        out.appendLine(String.format(Locale.ROOT, "%-20s %-13s %3s | offer fall | export fall right | harm | best med | pd med", "variant", "model", "n"))
        val variants = listOf(Variant("click only", false, 0.0), Variant("flash sometimes", true, 15.0), Variant("flash often", true, 35.0))
        for (v in variants) {
            for (model in models) {
                val runs = parallelList(n) { run(v, model, it) }
                val exports = runs.filter { it.exported }
                val right = when (model.dir) {
                    1 -> pct(exports.count { it.best > 1.0 }, n)
                    -1 -> pct(exports.count { it.best < 1.0 }, n)
                    else -> "   -"
                }
                out.appendLine(
                    String.format(
                        Locale.ROOT, "%-20s %-13s %3d | %s %s | %s %s %s | %s | %8.2f | %6.1f", v.label, model.name, n,
                        pct(runs.count { it.offered }, n), pct(runs.count { it.offered && it.best < 1.0 }, n),
                        pct(exports.size, n), pct(exports.count { it.best < 1.0 }, n), right,
                        pct(runs.count { it.offered && it.pd < 0 }, n), median(runs.map { it.best }), median(runs.filter { it.offered }.map { it.pd }),
                    ),
                )
            }
            out.appendLine("")
            write("flash-strength-search.txt", out.toString())
        }
    }
}
