package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelFit
import dev.veqzo.aimtune.core.accel.DEFAULT_ALPHA_PER_LOOK
import dev.veqzo.aimtune.core.accel.LookupCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * T5 of M14: the prototype gate for the closed loop (the removed docs/OPTIMIZER-REVIEW.md (git history), section 6). The loop plays batches of
 * rounds with the current curve, reads the remaining wanted gain per speed from the errors of the measured rounds (the
 * first round of a batch is for getting used to the curve and is not measured), moves the curve by [DAMPING] of it and
 * plays again. It is compared with the one-shot fit (legacy and bounded) on what reaches the player: a curve is exported
 * only if the blind A/B test calls it better, and its benefit is then measured in a second, independent A/B run, so a
 * lucky test does not count twice. Every model of the gate, the adaptive hand included.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class ClosedLoopGateTest {
    private val seeds = System.getenv("AIMTUNE_SEEDS")?.toIntOrNull()?.let { maxOf(4, it / 2) } ?: 16
    private val flatSeeds = seeds * 2
    private val base = CALIBRATION_BASE_CM360
    private val changes = listOf(0.0, -0.30, -0.15, 0.15, 0.30)

    private class Outcome(val exported: Boolean, val benefit: Double, val abMean: Double, val errorUsed: Double, val error2040: Double, val offered: Boolean)

    @Test
    fun closedLoopGate() {
        val out = StringBuilder()
        out.appendLine("== T5 closed-loop gate @ git ${gitShortHash()} | batches $BATCHES x $PER_BATCH rounds (1 adaptation round each), damping $DAMPING | seeds $seeds (flat $flatSeeds) ==")
        out.appendLine("exported = blind A/B (grouped, alpha $DEFAULT_ALPHA_PER_LOOK) says better; benefit = mean paired score difference of the exported curve in a second, independent 12-pair A/B (0 when nothing is exported)")
        out.appendLine("errors: RMS of ln(applied / true wanted gain), both normalized to median 0, over the used speeds of the run / over 20, 30, 40 in/s")
        out.appendLine()
        out.appendLine(String.format(Locale.ROOT, "%-9s %5s %-8s | %8s %8s | %7s %7s | %7s %7s | %7s", "model", "pref", "method", "offered", "exported", "benefit", "abMean", "errUsed", "err2040", "oracle"))
        for (model in gateModels) for (change in changes) {
            val n = if (change == 0.0) flatSeeds else seeds
            val oracle = if (change == 0.0) 0.0 else (0 until n).toList().parallelStream().map { s ->
                pairedDifference(model.apply(gainPlayer(change)), base, oracleCurve(change), 1.0, 12, 90_000L + s).first
            }.toList().average()
            for (method in listOf("legacy", "bounded", "loop")) {
                val runs = (0 until n).toList().parallelStream().map { s -> run(model, change, method, 70_000L + s) }.toList()
                out.appendLine(String.format(
                    Locale.ROOT, "%-9s %+5.0f%% %-8s | %4d/%-3d %4d/%-3d | %+7.2f %+7.2f | %7.3f %7.3f | %+7.2f",
                    model.name, change * 100, method, runs.count { it.offered }, n, runs.count { it.exported }, n,
                    runs.map { it.benefit }.average(), meanOf(runs.map { it.abMean }), meanOf(runs.map { it.errorUsed }), meanOf(runs.map { it.error2040 }), oracle,
                ))
            }
        }
        write("gate-t5-closed-loop.txt", out.toString())
    }

    private fun run(model: GateModel, change: Double, method: String, seed: Long): Outcome {
        val player = model.apply(gainPlayer(change))
        val (fit, speeds) = when (method) {
            "loop" -> closedLoop(player, seed)
            else -> {
                val rounds = playPlainRounds(player, seed, CALIBRATION_ROUNDS)
                val samples = movementSamples(rounds, TEST_DPI)
                val curveMethod = if (method == "legacy") LegacyCurveMethod else BoundedCurveMethod
                curveMethod.recommend(rounds, TEST_DPI) to AccelAnalysis(samples).used.map { it.speed }
            }
        }
        val errors = errorsOf(fit, change, speeds)
        if (fit == null) return Outcome(false, 0.0, Double.NaN, errors.first, errors.second, false)
        val outcomes = playAbRounds(player, base, fit.curve, fit.sensitivityFactor, seed + 500)
        val verdict = judgeAb(outcomes, DEFAULT_ALPHA_PER_LOOK, grouped = true)
        val abMean = evaluateAb(outcomes, MetricWeights.DEFAULT).meanDifference
        val exported = verdict == AbStatus.ACCEL_BETTER
        val benefit = if (exported) pairedDifference(player, base, fit.curve, fit.sensitivityFactor, 12, seed + 900).first else 0.0
        return Outcome(exported, benefit, abMean, errors.first, errors.second, true)
    }

    /**
     * The closed loop: [BATCHES] batches of [PER_BATCH] click rounds. The curve is a lookup table over the 5th-95th
     * percentile of the speeds of the first batch, flat outside, with factor 1 (the loop changes only the shape). After each
     * batch the wanted gain relative to what was delivered, per speed bin of the measured rounds, is added to the curve's
     * log gain times [DAMPING], and the result is anchored at gain 1 at its slowest speed.
     */
    private fun closedLoop(player: SimulatedPlayer, seed: Long): Pair<AccelFit?, List<Double>> {
        var knots: List<Double>? = null
        var logGain = DoubleArray(KNOTS)
        var curve: AccelCurve? = null
        val allSpeeds = mutableListOf<Double>()
        var played = 0
        for (batch in 0 until BATCHES) {
            val metrics = (0 until PER_BATCH).map { r ->
                val kind = CLICK_KINDS[r % CLICK_KINDS.size]
                val rec = HeadlessRound.of(kind, player, TEST_DPI, base, seed * 1_000 + played, ROUND_SECONDS, sessionOffsetNanos = played * ROUND_SECONDS * NANOS_PER_SECOND, accel = curve)
                played++
                computeRoundMetrics(rec)
            }.drop(1)
            val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
            allSpeeds += analysis.used.map { it.speed }
            if (knots == null) {
                val low = analysis.speedQuantile(0.05)
                val high = analysis.speedQuantile(0.95)
                if (low <= 0.0 || high <= low) return null to allSpeeds
                knots = (0 until KNOTS).map { low * exp(ln(high / low) * it / (KNOTS - 1)) }
            }
            val points = analysis.points
            if (points.size < 2) continue
            val residual = knots.map { v -> interpolate(points.map { ln(it.speed) }, points.map { it.logGain }, ln(v)) }
            logGain = DoubleArray(KNOTS) { logGain[it] + DAMPING * residual[it] }
            // The errors give the shape only (their level is the sensitivity's business), so the table is anchored at
            // gain 1 at its slowest speed, as Raw Accel curves and the fitted families start at 1. A first version centred
            // it on the median reach instead, which shifted the whole gain level and lost the A/B test by 25-40 points.
            val anchor = logGain[0]
            logGain = DoubleArray(KNOTS) { logGain[it] - anchor }
            curve = LookupCurve(knots, logGain.map { exp(it) })
        }
        val table = curve as? LookupCurve ?: return null to allSpeeds
        val range = table.multipliers.max() / table.multipliers.min()
        // The loop always ends with some curve; one that changes the gain by under 2 % is not offered, as for the fit.
        if (range < MIN_RANGE) return null to allSpeeds
        return AccelFit("closed loop (table)", table, 1.0, 0.0, 0.0, 0.0, range) to allSpeeds
    }

    /** RMS of the normalized log gain error of [fit] (plain when null) against the true preference. */
    private fun errorsOf(fit: AccelFit?, change: Double, speeds: List<Double>): Pair<Double, Double> {
        if (speeds.isEmpty()) return Double.NaN to Double.NaN
        val applied = { v: Double -> ln(fit?.totalGain(v) ?: 1.0) }
        val truth = { v: Double -> ln(trueGain(change, v)) }
        val offset = median(speeds.map { applied(it) - truth(it) })
        val used = sqrt(speeds.sumOf { (applied(it) - truth(it) - offset).let { d -> d * d } } / speeds.size)
        val fast = listOf(20.0, 30.0, 40.0)
        val extrapolated = sqrt(fast.sumOf { (applied(it) - truth(it) - offset).let { d -> d * d } } / fast.size)
        return used to extrapolated
    }

    private fun interpolate(xs: List<Double>, ys: List<Double>, x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val i = xs.indexOfFirst { it > x } - 1
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return ys[i] + (ys[i + 1] - ys[i]) * t
    }

    private companion object {
        const val BATCHES = 4
        const val PER_BATCH = 6
        const val DAMPING = 0.5
        const val KNOTS = 8
        const val MIN_RANGE = 1.02
        const val CALIBRATION_ROUNDS = 15
    }
}
