package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AbVerdict
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.MovementSample
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.accel.trialAccel
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.parabolicOptimum
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.harness.runAccelPipeline
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Independent assessment experiments (docs/history/ASSESSMENT.md). Gated like the other review experiments:
 * set AIMTUNE_REVIEW; AIMTUNE_SEEDS / AIMTUNE_RUNS scale the number of simulated players per row.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class AssessmentTest {
    private val seeds = (System.getenv("AIMTUNE_SEEDS") ?: "40").toInt()
    private val runs = (System.getenv("AIMTUNE_RUNS") ?: "30").toInt()
    private val clickKinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)

    private fun gainPlayer(change: Double) = SimulatedPlayer(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    // ---------------------------------------------------------------- A1-A3

    /** Precision, flatness and calibration of the sensitivity finder at 8/15/30 rounds and three noise levels. */
    @Test
    fun sensitivityPrecision() {
        val out = StringBuilder()
        val range = SearchRange(20.0, 140.0)
        val pooled = mutableMapOf<Int, MutableList<Triple<Int, Boolean, Boolean>>>() // rounds -> (confidence, lossOk, within15)
        for (noise in listOf(0.7, 1.0, 1.6)) {
            val player = SimulatedPlayer(noiseLevel = noise).withOptimum(50.0)
            val grid = geometricGrid(20.0, 160.0, 17)
            val inputs = sweepInputs(player, grid, 24, 77)
            val truth = SmoothCurve(grid, meanScoresFor(inputs, MetricWeights.DEFAULT))
            val optimum = truth.argmax(range.minCm360, range.maxCm360)
            val scores = ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(inputs.flatten())
            var cursor = 0
            val sdPerGrid = inputs.map { group ->
                val s = group.indices.map { scores[cursor++] }.filterNotNull()
                val mean = s.average()
                sqrt(s.sumOf { (it - mean) * (it - mean) } / (s.size - 1))
            }
            val nearest = grid.indices.minBy { abs(ln(grid[it] / optimum)) }
            val roundSd = sdPerGrid[nearest]
            out.appendLine("== noise x$noise: truth optimum %.1f cm/360 (score %.1f), SD of one round's score at the optimum %.1f points ==".format(optimum, truth.at(optimum), roundSd))
            out.appendLine("   flatness (score points lost vs the optimum): " + listOf(0.7, 0.85, 1.15, 1.3, 1.5, 2.0).joinToString("  ") { f ->
                val x = (optimum * f).coerceIn(20.0, 160.0)
                "x%.2f: %.1f".format(f, truth.at(optimum) - truth.at(x))
            })
            for (rounds in listOf(8, 15, 30)) {
                val res = parallelList(seeds) { s ->
                    val result = checkNotNull(playBlindTest(player, range, rounds, 5000L + s + (noise * 100).toLong() * 1000).result)
                    val clamped = result.optimumCm360.coerceIn(range.minCm360, range.maxCm360)
                    result to (truth.at(optimum) - truth.at(clamped))
                }
                val errPct = res.map { 100.0 * abs(exp(abs(ln(it.first.optimumCm360 / optimum))) - 1.0) }
                val signed = res.map { 100.0 * (it.first.optimumCm360 / optimum - 1.0) }
                val loss = res.map { it.second }
                val withinOk = res.count { errorFactorOf(it.first.optimumCm360, optimum) <= 1.15 }
                val covered = res.count { optimum in it.first.intervalLowCm360 * 0.999..it.first.intervalHighCm360 * 1.001 }
                out.appendLine(
                    "   %2d rounds, n=%d: error %% median %.0f / p75 %.0f / p90 %.0f (signed median %+.0f%%) | within +-15%% %.0f%% | true loss mean %.2f, p90 %.2f pts (%.2f round SDs) | 80%% interval covers %.0f%% | confidence mean %.0f%%, interval ratio median %.2f".format(
                        rounds, seeds, percentile(errPct, 0.5), percentile(errPct, 0.75), percentile(errPct, 0.9), percentile(signed, 0.5),
                        100.0 * withinOk / seeds, loss.average(), percentile(loss, 0.9), loss.average() / roundSd, 100.0 * covered / seeds,
                        res.map { it.first.confidencePercent }.average(), percentile(res.map { it.first.intervalHighCm360 / it.first.intervalLowCm360 }, 0.5),
                    ),
                )
                for (r in res) pooled.getOrPut(rounds) { mutableListOf() } += Triple(r.first.confidencePercent, r.second <= r.first.negligibleDifference, errorFactorOf(r.first.optimumCm360, optimum) <= 1.15)
            }
        }
        out.appendLine()
        out.appendLine("== Calibration of the reported confidence, pooled over the three noise levels ==")
        out.appendLine("rounds  confidence bin     n   P(true loss <= negligible)   P(within +-15 %)")
        for ((rounds, list) in pooled.toSortedMap()) for (bin in listOf(0..39, 40..59, 60..79, 80..100)) {
            val sel = list.filter { it.first in bin }
            if (sel.isEmpty()) continue
            out.appendLine("%5d   %3d-%3d%%      %4d   %5.0f%%                       %5.0f%%".format(rounds, bin.first, bin.last, sel.size, 100.0 * sel.count { it.second } / sel.size, 100.0 * sel.count { it.third } / sel.size))
        }
        write("assess-precision.txt", out.toString())
    }

    // ---------------------------------------------------------------- B1: asymmetry

    /** Does the analysis recover the wanted gain equally well for rising and falling preferences? */
    @Test
    fun asymmetry() {
        val out = StringBuilder("== Wanted gain recovered by the analysis, pooled over $seeds simulated players x 15 rounds ==\n")
        for (change in listOf(0.0, 0.15, -0.15, 0.25, -0.25, 0.5, -0.5)) {
            val player = gainPlayer(change)
            val perSeed = parallelList(seeds) { s ->
                val metrics = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 70_000L + s * 100 + it, 45)) }
                val samples = movementSamples(metrics, TEST_DPI)
                val fit = fitAccel(AccelAnalysis(samples))
                samples to fit
            }
            val all = perSeed.flatMap { it.first }
            val analysis = AccelAnalysis(all)
            val truthLog = { v: Double -> -ln(1.0 + change * (1.0 - exp(-v / 6.0))) }
            val centre = analysis.used.map { truthLog(it.speed) }.sorted().let { it[it.size / 2] }
            out.appendLine("-- true gain change %+.0f%% (samples %d, used %d, raw mean error ratio %+.3f) --".format(change * 100, all.size, analysis.used.size, all.map { it.errorRatio }.average()))
            out.appendLine("   speed in/s   measured wanted log-gain   truth (centred)   n")
            for (p in analysis.points) out.appendLine("   %8.1f      %+.3f (se %.3f)          %+.3f          %d".format(p.speed, p.logGain, p.standardError, truthLog(p.speed) - centre, p.count))
            val fits = perSeed.map { it.second }
            val best = fits.mapNotNull { f -> f.fits.maxByOrNull { it.bicGain } }
            out.appendLine(
                "   per player (single run of 15 rounds): median BIC gain of best family %.1f (threshold 10), p25/p75 %.1f/%.1f; median gain range x%.2f (threshold 1.08); offered %d of %d; reasons: %s".format(
                    percentile(best.map { it.bicGain }, 0.5), percentile(best.map { it.bicGain }, 0.25), percentile(best.map { it.bicGain }, 0.75),
                    percentile(best.map { it.gainRange }, 0.5), fits.count { it.recommended != null }, seeds, fits.groupingBy { it.reasonCode }.eachCount(),
                ),
            )
        }
        write("assess-asymmetry.txt", out.toString())
    }

    // ---------------------------------------------------------------- B2: why not confirmed

    private fun pairedDifference(player: SimulatedPlayer, base: Double, curve: AccelCurve, factor: Double, pairs: Int, seed: Long): Pair<Double, Double> {
        val plan = AccelAbPlan(clickKinds, seed, pairs)
        val inputs = mutableListOf<Map<dev.veqzo.aimtune.core.score.ScoreMetric, Double>>()
        val conditions = mutableListOf<AbCondition>()
        for (index in 0 until 2 * pairs) {
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val rec = HeadlessRound.of(
                round.scenario, player, TEST_DPI, if (accelerated) base / factor else base, round.seed, 45,
                sessionOffsetNanos = index * 45L * 1_000_000_000L, accel = if (accelerated) curve else null,
            )
            inputs += computeRoundMetrics(rec).scoreInputs()
            conditions += round.condition
        }
        val scores = ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(inputs)
        val diffs = (0 until pairs).mapNotNull { p ->
            val a = (2 * p..2 * p + 1).first { conditions[it] == AbCondition.ACCEL }
            val b = (2 * p..2 * p + 1).first { conditions[it] == AbCondition.PLAIN }
            val sa = scores[a]
            val sb = scores[b]
            if (sa != null && sb != null) sa - sb else null
        }
        val mean = diffs.average()
        val sd = sqrt(diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1))
        return mean to sd / sqrt(diffs.size.toDouble())
    }

    /** Best natural curve for the true preference shape (the oracle a perfect fit could reach). */
    private fun oracleCurve(change: Double): NaturalCurve {
        val speeds = (1..40).map { it.toDouble() }
        return listOf(0.01, 0.02, 0.04, 0.08, 0.15, 0.3).map { NaturalCurve(it, 1.0 + change) }.minBy { c ->
            speeds.sumOf { v -> val d = c.multiplier(v) - (1.0 + change * (1.0 - exp(-v / 6.0))); d * d }
        }
    }

    /** Score difference that the offered curve, the same curve without its sensitivity factor and an oracle curve really produce. */
    @Test
    fun whyNotConfirmed() {
        val out = StringBuilder("== True score difference (curve minus plain, points; mean +- se over 60 pairs per player, then median over players) ==\n")
        for (change in listOf(-0.15, -0.25, 0.15, 0.25, 0.5)) {
            val player = gainPlayer(change)
            val oracle = oracleCurve(change)
            val rows = parallelList(minOf(seeds, 20)) { s ->
                val metrics = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 90_000L + s * 100 + it, 45)) }
                val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI)))
                val best = fit.fits.maxByOrNull { it.bicGain }
                val oracleDiff = pairedDifference(player, 55.0, oracle, 1.0, 60, 800L + s)
                val fitted = best?.let { pairedDifference(player, 55.0, it.curve, it.sensitivityFactor, 60, 800L + s) }
                val fittedNoFactor = best?.let { pairedDifference(player, 55.0, it.curve, 1.0, 60, 800L + s) }
                listOf(oracleDiff.first, fitted?.first ?: Double.NaN, fittedNoFactor?.first ?: Double.NaN, best?.sensitivityFactor ?: Double.NaN, best?.gainRange ?: Double.NaN, oracleDiff.second)
            }
            fun med(i: Int) = percentile(rows.map { it[i] }.filter { !it.isNaN() }, 0.5)
            fun p(i: Int, q: Double) = percentile(rows.map { it[i] }.filter { !it.isNaN() }, q)
            out.appendLine(
                "gain change %+.0f%%: oracle natural curve (limit %.2f, decay %.2f): median %+.2f pts (p25 %+.2f, p75 %+.2f, se of one measurement %.2f) | fitted curve with its factor: median %+.2f (p25 %+.2f, p75 %+.2f) | fitted curve, factor forced to 1: median %+.2f | median factor %.3f, median fitted gain range x%.2f".format(
                    change * 100, oracle.limit, oracle.decayRate, med(0), p(0, 0.25), p(0, 0.75), med(5), med(1), p(1, 0.25), p(1, 0.75), med(2), med(3), med(4),
                ),
            )
        }
        write("assess-whynotconfirmed.txt", out.toString())
    }

    /** What the fitted curve looks like for a falling preference: family, parameters, factor and its gain at some speeds. */
    @Test
    fun fitDetail() {
        val out = StringBuilder()
        for (change in listOf(-0.15, -0.25, 0.25)) {
            val player = gainPlayer(change)
            out.appendLine("== true gain change %+.0f%% ==".format(change * 100))
            for (s in 0 until 8) {
                val metrics = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 90_000L + s * 100 + it, 45)) }
                val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI)))
                val best = fit.fits.maxByOrNull { it.bicGain } ?: continue
                out.appendLine(
                    "  seed %d: %s %s factor %.2f, BIC gain %.0f, total gain at 1/2/4/8/16/30 in/s = %s".format(
                        s, best.family, best.curve, best.sensitivityFactor, best.bicGain, listOf(1.0, 2.0, 4.0, 8.0, 16.0, 30.0).joinToString(" ") { "%.2f".format(best.totalGain(it)) },
                    ),
                )
            }
        }
        write("assess-fitdetail.txt", out.toString())
    }

    /** Distribution of the fit statistic for players without preference and with small ones, to place the thresholds. */
    @Test
    fun nullDistribution() {
        val n = (System.getenv("AIMTUNE_RUNS") ?: "30").toInt().coerceAtLeast(100)
        val out = StringBuilder("== Best-family BIC gain and gain range after 15 calibration rounds ($n players per row) ==\n")
        val rows = listOf(
            "flat noise x0.7" to SimulatedPlayer(noiseLevel = 0.7), "flat noise x1.0" to SimulatedPlayer(), "flat noise x1.6" to SimulatedPlayer(noiseLevel = 1.6),
            "flat, optimum 80 cm" to SimulatedPlayer().withOptimum(80.0),
            "-15 %" to gainPlayer(-0.15), "-25 %" to gainPlayer(-0.25), "+15 %" to gainPlayer(0.15), "+25 %" to gainPlayer(0.25), "+50 %" to gainPlayer(0.5),
        )
        for ((label, player) in rows) {
            val stats = parallelList(n) { s ->
                val metrics = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 120_000L + s * 100 + it, 45)) }
                val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI)), minBicGain = -1e9, minGainRange = 0.0)
                val best = fit.fits.maxByOrNull { it.bicGain }
                (best?.bicGain ?: Double.NaN) to (best?.gainRange ?: Double.NaN)
            }
            val bic = stats.map { it.first }.filter { !it.isNaN() }
            val range = stats.map { it.second }.filter { !it.isNaN() }
            out.appendLine(
                "  %-22s BIC gain p10/p50/p90/p99/max = %.1f / %.1f / %.1f / %.1f / %.1f | gain range p10/p50/p90 = %.3f / %.3f / %.3f".format(
                    label, percentile(bic, 0.1), percentile(bic, 0.5), percentile(bic, 0.9), percentile(bic, 0.99), bic.maxOrNull() ?: Double.NaN,
                    percentile(range, 0.1), percentile(range, 0.5), percentile(range, 0.9),
                ),
            )
        }
        write("assess-null.txt", out.toString())
    }

    // ---------------------------------------------------------------- C: hand model at head-sized targets

    private fun headSweep(player: SimulatedPlayer, grid: List<Double>, rounds: Int, seed: Long) =
        sweepInputs(player, grid, rounds, seed) {
            dev.veqzo.aimtune.core.scenario.FlickScenario(
                dev.veqzo.aimtune.core.scenario.FlickConfig(ROUND_SECONDS * NANOS_PER_SECOND, geometry = dev.veqzo.aimtune.core.scenario.TrainerGeometry.DEFAULT), it,
            )
        }

    /** Where the default simulated hand has its optimum with the profile's head-sized targets, and a hand that sits at about 50 cm/360 there. */
    @Test
    fun headTargets() {
        val out = StringBuilder()
        val wide = geometricGrid(40.0, 640.0, 21)
        val base = SimulatedPlayer()
        val baseMeans = meanScoresFor(headSweep(base, wide, 30, 41), MetricWeights.DEFAULT)
        val baseCurve = SmoothCurve(wide, baseMeans, degree = 5)
        val baseOptimum = baseCurve.argmax(40.0, 640.0)
        out.appendLine("default hand, head-sized targets: optimum %.1f cm/360 (smooth fit), parabolic %.1f; score at 40/80/160/320/640 = %s".format(
            baseOptimum, parabolicOptimum(wide, baseMeans), listOf(40.0, 80.0, 160.0, 320.0, 640.0).joinToString { "%.1f".format(baseCurve.at(it)) }))
        val scale = 50.0 / baseOptimum
        val human = base.copy(optimumScale = scale)
        out.appendLine("optimumScale for a head-target optimum of 50 cm/360: %.4f".format(scale))
        val grid = geometricGrid(20.0, 160.0, 17)
        val inputs = headSweep(human, grid, 30, 43)
        val means = meanScoresFor(inputs, MetricWeights.DEFAULT)
        val curve = SmoothCurve(grid, means)
        val optimum = curve.argmax(20.0, 160.0)
        out.appendLine("scaled hand: optimum %.1f cm/360; loss vs optimum at x0.7 x0.85 x1.15 x1.3 x1.5 x2 = %s".format(
            optimum, listOf(0.7, 0.85, 1.15, 1.3, 1.5, 2.0).joinToString { "%.2f".format(curve.at(optimum) - curve.at((optimum * it).coerceIn(20.0, 160.0))) }))
        val range = SearchRange(20.0, 140.0)
        for (rounds in listOf(8, 15, 30)) {
            val res = parallelList(seeds) { s -> checkNotNull(playBlindTest(human, range, rounds, 9000L + s, geometry = dev.veqzo.aimtune.core.scenario.TrainerGeometry.DEFAULT).result) }
            val err = res.map { 100.0 * (errorFactorOf(it.optimumCm360, optimum) - 1.0) }
            val loss = res.map { curve.at(optimum) - curve.at(it.optimumCm360.coerceIn(20.0, 140.0)) }
            out.appendLine(
                "   %2d rounds: error %% median %.0f / p90 %.0f | within +-15%% %.0f%% | true loss mean %.2f pts | confidence mean %.0f%% | equivalent range median %.0f-%.0f".format(
                    rounds, percentile(err, 0.5), percentile(err, 0.9), 100.0 * err.count { it <= 15.0 } / seeds, loss.average(), res.map { it.confidencePercent }.average(),
                    percentile(res.map { it.equivalentLowCm360 }, 0.5), percentile(res.map { it.equivalentHighCm360 }, 0.5),
                ),
            )
        }
        write("assess-headtargets.txt", out.toString())
    }

    /** Finer bins and a smaller trust floor: how much more is found, and how much worse is the null? */
    @Test
    fun finerBins() {
        val nNull = 300
        val nEffect = 60
        val rounds = (System.getenv("AIMTUNE_ROUNDS") ?: "15").toInt()
        data class Config(val label: String, val maxBins: Int, val minPerBin: Int, val floor: Double)
        val configs = listOf(
            Config("10 bins, floor 0.02 (now)", 10, 20, 0.02), Config("10 bins, floor 0.005", 10, 20, 0.005),
            Config("20 bins, floor 0.01", 20, 15, 0.01), Config("40 bins, floor 0.005", 40, 8, 0.005), Config("80 bins, floor 0.002", 80, 4, 0.002),
        )
        fun samplesOf(player: SimulatedPlayer, seed: Long): List<MovementSample> {
            val metrics = (0 until rounds).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, seed * 100 + it, 45)) }
            return movementSamples(metrics, TEST_DPI)
        }
        fun bicOf(c: Config, samples: List<MovementSample>): Double {
            val fit = fitAccel(AccelAnalysis(samples, c.minPerBin, maxBins = c.maxBins), minBicGain = -1e9, minGainRange = 0.0, floor = c.floor)
            return fit.fits.maxOfOrNull { it.bicGain } ?: Double.NEGATIVE_INFINITY
        }
        val nullPlayers = listOf(SimulatedPlayer(noiseLevel = 0.7), SimulatedPlayer(), SimulatedPlayer(noiseLevel = 1.6))
        val nullSamples = parallelList(nNull) { i -> samplesOf(nullPlayers[i % 3], 200_000L + i) }
        val out = StringBuilder("== Finer bins, $rounds calibration rounds. Null: $nNull flat players (noise x0.7, x1.0, x1.6); effects: $nEffect players each ==\n")
        val nullBic = configs.associateWith { c -> parallelList(nNull) { bicOf(c, nullSamples[it]) } }
        val effects = listOf(-0.5, -0.25, -0.15, 0.15, 0.25, 0.5)
        val effectSamples = effects.associateWith { change -> parallelList(nEffect) { i -> samplesOf(gainPlayer(change), 300_000L + i) } }
        for (c in configs) {
            val nb = nullBic.getValue(c)
            val threshold = maxOf(nb.max() + 2.0, 0.0)
            out.appendLine("-- ${c.label}: null BIC gain p50 %.1f / p99 %.1f / max %.1f -> threshold (max + 2) %.1f".format(percentile(nb, 0.5), percentile(nb, 0.99), nb.max(), threshold))
            for (change in effects) {
                val bics = parallelList(nEffect) { bicOf(c, effectSamples.getValue(change)[it]) }
                out.appendLine("     gain change %+4.0f%%: BIC gain median %.1f; above threshold %2d of %d; above 5 %2d".format(change * 100, percentile(bics, 0.5), bics.count { it > threshold }, nEffect, bics.count { it > 5.0 }))
            }
        }
        write("assess-finerbins.txt", out.toString())
    }

    /** One-shot fit against a second calibration played with the first curve on, refitting the total wanted gain. */
    @Test
    fun iterativeFit() {
        val n = 20
        val out = StringBuilder("== One-shot fit vs. refit after 15 more rounds with the first curve on ($n players per row; true score difference curve minus plain over 60 pairs, median) ==\n")
        for (change in listOf(0.15, 0.25, 0.5, -0.15, -0.25)) {
            val player = gainPlayer(change)
            val rows = parallelList(n) { s ->
                val plain = (0 until 15).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 400_000L + s * 100 + it, 45)) }
                val first = fitAccel(AccelAnalysis(movementSamples(plain, TEST_DPI)))
                val curve1 = first.recommended ?: return@parallelList null
                val withCurve = (0 until 15).map {
                    computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0 / curve1.sensitivityFactor, 410_000L + s * 100 + it, 45, accel = curve1.curve))
                }
                // The total gain the player wanted at each speed, as if the rounds had been played plain.
                val transformed = movementSamples(withCurve, TEST_DPI).map { sample ->
                    sample.copy(errorRatio = (1.0 + sample.errorRatio) / curve1.totalGain(sample.speed) - 1.0)
                }
                val second = fitAccel(AccelAnalysis(transformed), minBicGain = -1e9, minGainRange = 0.0)
                val curve2 = second.fits.maxByOrNull { it.bicGain } ?: return@parallelList null
                val d1 = pairedDifference(player, 55.0, curve1.curve, curve1.sensitivityFactor, 60, 700L + s).first
                val d2 = pairedDifference(player, 55.0, curve2.curve, curve2.sensitivityFactor, 60, 700L + s).first
                val v2 = abVerdict(player, 55.0, curve2.curve, curve2.sensitivityFactor, 800L + s)
                listOf(d1, d2, curve2.gainRange, if (v2.status == AbStatus.ACCEL_BETTER) 1.0 else 0.0)
            }
            val ok = rows.filterNotNull()
            val oracle = pairedDifference(player, 55.0, oracleCurve(change), 1.0, 60, 700L).first
            out.appendLine(
                "gain change %+4.0f%%: first fit offered %2d of %d | first curve +%.2f pts, refit curve +%.2f pts (oracle +%.2f) | refit gain range median x%.2f | blind test confirms the refit curve in %d of %d".format(
                    change * 100, ok.size, n, percentile(ok.map { it[0] }, 0.5), percentile(ok.map { it[1] }, 0.5), oracle, percentile(ok.map { it[2] }, 0.5), ok.count { it[3] > 0.5 }, ok.size,
                ),
            )
            write("assess-iterative.txt", out.toString())
        }
    }

    /** The curve strength found like the sensitivity: the optimizer over a pseudo cm/360 that stands for the gain ratio fast/slow (100 = no curve). */
    @Test
    fun strengthSearch() {
        val n = 16
        val rounds = (System.getenv("AIMTUNE_ROUNDS") ?: "24").toInt()
        val out = StringBuilder("== Blind search over the curve strength (gain at high speed relative to low speed, 0.7-1.45), $rounds rounds, $n players per row ==\n")
        val range = SearchRange(70.0, 145.0)
        val kinds = ScenarioSchedule(clickKinds.toSet(), MetricWeights.DEFAULT)
        fun curveOf(pseudo: Double): AccelCurve? {
            val r = pseudo / 100.0
            return if (abs(r - 1.0) < 0.02) null else NaturalCurve(0.05, r)
        }
        for (change in listOf(0.0, 0.15, 0.25, 0.5, -0.15, -0.25)) {
            val player = gainPlayer(change)
            val rows = parallelList(n) { s ->
                val test = playBlindTest(player, range, rounds, 600L + s, scenarioFor = { clickKinds[it % 3] }, accelOf = ::curveOf, playedCm360 = 55.0)
                val result = checkNotNull(test.result)
                val r = result.optimumCm360 / 100.0
                val diff = if (abs(r - 1.0) < 0.02) 0.0 else pairedDifference(player, 55.0, NaturalCurve(0.05, r), 1.0, 40, 900L + s).first
                listOf(r, diff, if (result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0) 1.0 else 0.0, result.confidencePercent.toDouble())
            }
            val oracle = if (change == 0.0) 0.0 else pairedDifference(player, 55.0, oracleCurve(change), 1.0, 40, 900L).first
            out.appendLine(
                "true gain change %+4.0f%%: found strength median x%.2f (p25 x%.2f, p75 x%.2f) | true gain over plain at that strength median %+.2f pts (oracle %+.2f) | interval excludes 'no curve' in %d of %d | confidence mean %.0f%%".format(
                    change * 100, percentile(rows.map { it[0] }, 0.5), percentile(rows.map { it[0] }, 0.25), percentile(rows.map { it[0] }, 0.75), percentile(rows.map { it[1] }, 0.5), oracle,
                    rows.count { it[2] > 0.5 }, n, rows.map { it[3] }.average(),
                ),
            )
            write("assess-strength.txt", out.toString())
        }
    }

    /**
     * The blind strength search with different search ranges for the strength (the app uses 0.70-1.45): where it ends up for
     * players whose true preference lies inside, at and outside the current range, how much it gains over plain sensitivity,
     * and how often a flat player is offered a curve.
     */
    @Test
    fun strengthRange() {
        val n = (System.getenv("AIMTUNE_PLAYERS") ?: "12").toInt()
        val rounds = (System.getenv("AIMTUNE_ROUNDS") ?: "24").toInt()
        val ranges = listOf("0.70-1.45" to (0.70 to 1.45), "0.60-1.70" to (0.60 to 1.70), "0.50-2.00" to (0.50 to 2.00))
        val out = StringBuilder("== Strength search by search range, $rounds rounds, $n players per row (benefit = true score gain of the found strength over plain, 40 pairs, median) ==\n")
        fun curveOf(pseudo: Double): AccelCurve? {
            val r = pseudo / 100.0
            return if (abs(r - 1.0) < 0.02) null else NaturalCurve(0.05, r)
        }
        for (change in listOf(-0.6, -0.5, -0.35, -0.25, -0.15, 0.0, 0.15, 0.25, 0.5, 0.75)) {
            val player = gainPlayer(change)
            val oracle = if (change == 0.0) 0.0 else pairedDifference(player, 55.0, oracleCurve(change), 1.0, 40, 900L).first
            out.appendLine("-- true gain change %+4.0f%% (oracle strength x%.2f, oracle benefit %+.2f pts)".format(change * 100, 1.0 + change, oracle))
            for ((label, bounds) in ranges) {
                val range = SearchRange(bounds.first * 100.0, bounds.second * 100.0)
                val rows = parallelList(n) { s ->
                    val test = playBlindTest(player, range, rounds, 600L + s, scenarioFor = { clickKinds[it % 3] }, accelOf = ::curveOf, playedCm360 = 55.0)
                    val result = checkNotNull(test.result)
                    val r = result.optimumCm360 / 100.0
                    val diff = if (abs(r - 1.0) < 0.02) 0.0 else pairedDifference(player, 55.0, NaturalCurve(0.05, r), 1.0, 40, 900L + s).first
                    listOf(r, diff, if (result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0) 1.0 else 0.0, if (result.atRangeEdge) 1.0 else 0.0, result.confidencePercent.toDouble())
                }
                out.appendLine(
                    "   range %s: found x%.2f (p25 x%.2f, p75 x%.2f) | benefit %+.2f pts | excludes 'no curve' %2d of %d | at the range edge %2d of %d | confidence %.0f%%".format(
                        label, percentile(rows.map { it[0] }, 0.5), percentile(rows.map { it[0] }, 0.25), percentile(rows.map { it[0] }, 0.75), percentile(rows.map { it[1] }, 0.5),
                        rows.count { it[2] > 0.5 }, n, rows.count { it[3] > 0.5 }, n, rows.map { it[4] }.average(),
                    ),
                )
            }
            write("assess-strength-range.txt", out.toString())
        }
    }

    // ---------------------------------------------------------------- B3: power

    /** What the app offers and confirms by gain change and calibration length. */
    @Test
    fun power() {
        val out = StringBuilder("== Offered / confirmed by the app, $runs simulated players per cell ==\n")
        for (rounds in listOf(8, 15, 30)) for (change in listOf(-0.5, -0.35, -0.25, -0.15, 0.0, 0.15, 0.25, 0.35, 0.5)) {
            val player = gainPlayer(change)
            val rs = parallelList(runs) { runAccelPipeline(player, 55.0, 50_000L + it, analysisRounds = rounds) }
            val offered = rs.filter { it.fit.recommended != null }
            val right = offered.count { f -> val c = f.fit.recommended!!; (c.curve.multiplier(30.0) > c.curve.multiplier(3.0)) == (change > 0) }
            out.appendLine(
                "  rounds=%2d gain change %+4.0f%%: offered %2d/%d (right direction %2d) | confirmed better %2d, worse %2d, undecided %2d".format(
                    rounds, change * 100, offered.size, runs, right, offered.count { it.verdict?.status == AbStatus.ACCEL_BETTER },
                    offered.count { it.verdict?.status == AbStatus.ACCEL_WORSE }, offered.count { it.verdict?.status == AbStatus.NO_DIFFERENCE },
                ),
            )
            write("assess-power.txt", out.toString())
        }
    }

    // ---------------------------------------------------------------- B4: trial curve

    private fun abVerdict(player: SimulatedPlayer, base: Double, curve: AccelCurve, factor: Double, seed: Long): AbVerdict {
        val plan = AccelAbPlan(clickKinds, seed)
        val outcomes = mutableListOf<AbOutcome>()
        var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT)
        for (index in 0 until 2 * plan.maxPairs) {
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val rec = HeadlessRound.of(
                round.scenario, player, TEST_DPI, if (accelerated) base / factor else base, round.seed, 45,
                sessionOffsetNanos = index * 45L * 1_000_000_000L, accel = if (accelerated) curve else null,
            )
            outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs())
            if (index % 2 == 1) {
                verdict = evaluateAb(outcomes, MetricWeights.DEFAULT)
                if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
            }
        }
        return verdict
    }

    /** What a player who clicks "try a mild curve anyway" gets, by true preference. */
    @Test
    fun trialCurve() {
        val out = StringBuilder("== Trial curve (15 % natural curve) after a calibration that found nothing, $runs players per row ==\n")
        for (rounds in listOf(8, 15)) for (change in listOf(0.0, 0.15, -0.15, 0.25, -0.25, 0.5, -0.35)) {
            val player = gainPlayer(change)
            val rs = parallelList(runs) { s ->
                val metrics = (0 until rounds).map { computeRoundMetrics(HeadlessRound.of(clickKinds[it % 3], player, TEST_DPI, 55.0, 60_000L + s * 100 + it, 45)) }
                val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI)))
                if (fit.recommended != null) return@parallelList null
                val rises = fit.points.size < 2 || fit.points.last().logGain >= fit.points.first().logGain
                val trial = trialAccel(rises)
                Triple(rises, abVerdict(player, 55.0, trial.curve, trial.sensitivityFactor, 400L + s), fit.points.size >= 2)
            }
            val trials = rs.filterNotNull()
            val rightDirection = trials.count { it.first == (change > 0) }
            out.appendLine(
                "  rounds=%2d true gain change %+4.0f%%: no curve found in %2d/%d | of those trial direction right %2d | trial verdict: better %2d, worse %2d, no difference %2d, undecided %2d".format(
                    rounds, change * 100, trials.size, runs, rightDirection, trials.count { it.second.status == AbStatus.ACCEL_BETTER },
                    trials.count { it.second.status == AbStatus.ACCEL_WORSE }, trials.count { it.second.status == AbStatus.NO_DIFFERENCE },
                    trials.count { it.second.status == AbStatus.NEED_MORE_ROUNDS },
                ),
            )
            write("assess-trial.txt", out.toString())
        }
    }
}
