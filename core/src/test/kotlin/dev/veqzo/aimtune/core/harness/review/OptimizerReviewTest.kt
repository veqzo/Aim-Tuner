package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelFit
import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.accel.MovementSample
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.optimizer.Observation
import dev.veqzo.aimtune.core.optimizer.OptimizerConfig
import dev.veqzo.aimtune.core.optimizer.SensitivityOptimizer
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Review experiments E1-E5 on the acceleration analysis, a closed-loop prototype, the A/B power and session drift.
 * Raw numbers only. Gated like the other review experiments: set AIMTUNE_REVIEW.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class OptimizerReviewTest {
    private val kinds = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
    private val base = 55.0
    private val calibrationRounds = 15

    // ------------------------------------------------------------------ helpers

    private fun dist(values: List<Double>): String =
        "n=%d mean %.4f median %.4f p90 %.4f max %.4f".format(values.count { !it.isNaN() }, meanOf(values), median(values.filter { !it.isNaN() }), percentileOf(values, 0.9), values.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN)

    private fun slope(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2) return Double.NaN
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return Double.NaN
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double {
        if (x.size < 3) return Double.NaN
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val syy = y.sumOf { (it - my) * (it - my) }
        if (sxx == 0.0 || syy == 0.0) return Double.NaN
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sqrt(sxx * syy)
    }

    /** One first movement with everything the binnings need. */
    private data class Mv(val speed: Double, val err: Double, val dist: Double) {
        val y: Double get() = ln(1.0 + err)
    }

    private fun mvs(metrics: List<RoundMetrics>): List<Mv> = metrics.flatMap { it.movements }.mapNotNull { m ->
        val peak = m.firstPeakSpeedCountsPerMs ?: return@mapNotNull null
        val e = m.firstErrorRatio ?: return@mapNotNull null
        Mv(AccelProcessor.speedInPerSecond(peak, TEST_DPI), e, m.initialDistanceDegrees)
    }

    private fun usable(all: List<Mv>): List<Mv> =
        all.filter { it.speed >= AccelAnalysis.MIN_SPEED && (1.0 + it.err) in AccelAnalysis.MIN_RATIO..AccelAnalysis.MAX_RATIO }

    private class Bin(val members: List<Mv>, val logGain: Double, val se: Double) {
        val speedMedian = median(members.map { it.speed })
        val distMedian = median(members.map { it.dist })
    }

    /** Same equal-count median binning as AccelAnalysis, with any sort key. */
    private fun binBy(used: List<Mv>, minPerBin: Int, maxBins: Int, minBins: Int, key: (Mv) -> Double): List<Bin> {
        val n = used.size
        val bins = (n / minPerBin).coerceAtMost(maxBins).let { if (it < minBins) 0 else it }
        if (bins < 2) return emptyList()
        val center = median(used.map { it.y })
        val sorted = used.sortedBy(key)
        return (0 until bins).map { b ->
            val slice = sorted.subList(b * n / bins, (b + 1) * n / bins)
            val ys = slice.map { it.y }
            val mean = ys.average()
            val sd = if (ys.size > 1) sqrt(ys.sumOf { (it - mean) * (it - mean) } / (ys.size - 1)) else 0.0
            Bin(slice, center - median(ys), sqrt(PI / 2.0) * sd / sqrt(ys.size.toDouble()))
        }
    }

    /** Plain calibration rounds (click scenarios in turn, as the app does). */
    private fun plainRounds(player: SimulatedPlayer, seed: Long, rounds: Int = calibrationRounds): List<RoundMetrics> =
        playPlainRounds(player, seed, rounds, kinds, base)

    /** The best-BIC fit of a surrogate analysis whose bins are the given bins at their median peak speed. */
    private fun surrogateFit(bins: List<Bin>): Pair<AccelFit?, Boolean> {
        if (bins.size < 4) return null to false
        val m = bins.minOf { it.members.size }
        val samples = bins.flatMap { b -> b.members.take(m).map { MovementSample(b.speedMedian, it.err) } }
        val analysis = AccelAnalysis(samples, minPerBin = m, maxBins = bins.size)
        val fit = fitAccel(analysis)
        return fit.fits.maxByOrNull { it.bicGain } to (fit.recommended != null)
    }

    private fun decileCorr(items: List<Mv>, key: (Mv) -> Double, x: (Mv) -> Double, y: (Mv) -> Double): Double {
        if (items.size < 50) return Double.NaN
        val sorted = items.sortedBy(key)
        val cs = (0 until 10).map { d ->
            val s = sorted.subList(d * sorted.size / 10, (d + 1) * sorted.size / 10)
            pearson(s.map(x), s.map(y))
        }.filter { !it.isNaN() }
        return if (cs.isEmpty()) Double.NaN else cs.average()
    }

    /** RMS over speeds 5..40 of fitted/true wanted gain - 1, both normalized to a median log gain of 0 over the reference speeds. */
    private fun curveError(logG: (Double) -> Double, change: Double, ref: List<Double>): Double {
        val gc = median(ref.map { logG(it) })
        val tc = median(ref.map { ln(trueGain(change, it)) })
        val errs = SPEEDS.map { v -> exp((logG(v) - gc) - (ln(trueGain(change, v)) - tc)) - 1.0 }
        return sqrt(errs.sumOf { it * it } / errs.size)
    }

    private fun normalizedLog(logG: (Double) -> Double, ref: List<Double>): (Double) -> Double {
        val gc = median(ref.map { logG(it) })
        return { v -> logG(v) - gc }
    }

    private fun trueNormalized(change: Double, ref: List<Double>, v: Double): Double =
        exp(ln(trueGain(change, v)) - median(ref.map { ln(trueGain(change, it)) }))

    // ------------------------------------------------------------------ E1

    private class E1Row(
        val usedCount: Int,
        val slopeSpeed: Double, val bicA: Double, val rangeA: Double, val offeredA: Boolean,
        val slopeDist: Double, val slopeDistVsSpeed: Double, val bicB: Double, val rangeB: Double, val offeredB: Boolean,
        val binDiff: Double, val corrWithin: Double, val corrAll: Double, val corrSym: Double, val corrDistSpeed: Double,
        val used: List<Mv>,
    )

    @Test
    fun e1Endogeneity() {
        val seeds = 40
        val player = SimulatedPlayer()
        val rows = parallelList(seeds) { s ->
            val metrics = plainRounds(player, 11_000L + s)
            val samples = movementSamples(metrics, TEST_DPI)
            val analysis = AccelAnalysis(samples)
            val fitA = fitAccel(analysis)
            val bestA = fitA.fits.maxByOrNull { it.bicGain }
            val pts = analysis.points
            val slopeSpeed = slope(pts.map { ln(it.speed) }, pts.map { it.logGain })
            val used = usable(mvs(metrics))
            val speedBins = binBy(used, AccelAnalysis.MIN_PER_BIN, AccelAnalysis.MAX_BINS, AccelAnalysis.MIN_BINS) { it.speed }
            val binDiff = if (speedBins.size == pts.size) speedBins.indices.maxOf { abs(speedBins[it].logGain - pts[it].logGain) } else Double.NaN
            val distBins = binBy(used, AccelAnalysis.MIN_PER_BIN, AccelAnalysis.MAX_BINS, AccelAnalysis.MIN_BINS) { it.dist }
            val slopeDist = slope(distBins.map { ln(it.distMedian) }, distBins.map { it.logGain })
            val slopeDistVsSpeed = slope(distBins.map { ln(it.speedMedian) }, distBins.map { it.logGain })
            val (bestB, offeredB) = surrogateFit(distBins)
            E1Row(
                used.size, slopeSpeed, bestA?.bicGain ?: Double.NaN, bestA?.gainRange ?: Double.NaN, fitA.recommended != null,
                slopeDist, slopeDistVsSpeed, bestB?.bicGain ?: Double.NaN, bestB?.gainRange ?: Double.NaN, offeredB,
                binDiff,
                decileCorr(used, { it.dist }, { it.y }, { ln(it.speed) }),
                pearson(used.map { it.y }, used.map { ln(it.speed) }),
                decileCorr(used, { it.speed }, { it.y }, { ln(it.dist) }),
                pearson(used.map { ln(it.dist) }, used.map { ln(it.speed) }),
                used,
            )
        }
        val pooled = rows.flatMap { it.used }
        val out = StringBuilder()
        out.appendLine("== E1 endogeneity: $seeds flat players (no gain preference), $calibrationRounds plain calibration rounds each (FLICK/SWITCHING/MICRO in turn, ${base} cm/360, $TEST_DPI dpi, 45 s) ==")
        out.appendLine("used movements per player (speed >= 3 in/s, ratio 0.2..3): " + dist(rows.map { it.usedCount.toDouble() }))
        out.appendLine("peak speed in/s of used movements, pooled (n=${pooled.size}): p10 %.1f p50 %.1f p90 %.1f | distance deg: p10 %.1f p50 %.1f p90 %.1f".format(
            percentileOf(pooled.map { it.speed }, 0.1), percentileOf(pooled.map { it.speed }, 0.5), percentileOf(pooled.map { it.speed }, 0.9),
            percentileOf(pooled.map { it.dist }, 0.1), percentileOf(pooled.map { it.dist }, 0.5), percentileOf(pooled.map { it.dist }, 0.9)))
        out.appendLine("check: my speed binning vs AccelAnalysis points, max abs logGain difference over players: " + dist(rows.map { it.binDiff }))
        out.appendLine()
        out.appendLine("-- (a) current pipeline: AccelAnalysis bins by peak speed, fitAccel (MIN_BIC_GAIN=8, MIN_GAIN_RANGE=1.02)")
        out.appendLine("slope of logGain vs ln(speed), signed: " + dist(rows.map { it.slopeSpeed }))
        out.appendLine("slope abs:                          " + dist(rows.map { abs(it.slopeSpeed) }))
        out.appendLine("best-family BIC gain:               " + dist(rows.map { it.bicA }))
        out.appendLine("gain range of best family:          " + dist(rows.map { it.rangeA }))
        out.appendLine("curve offered (recommended != null): ${rows.count { it.offeredA }} of $seeds")
        out.appendLine()
        out.appendLine("-- (b) bins by target distance (initialDistanceDegrees), same 10 equal-count bins, median/SE logic")
        out.appendLine("slope of logGain vs ln(median distance), signed: " + dist(rows.map { it.slopeDist }))
        out.appendLine("slope abs:                                       " + dist(rows.map { abs(it.slopeDist) }))
        out.appendLine("slope of the same distance bins vs ln(bin median peak speed), signed: " + dist(rows.map { it.slopeDistVsSpeed }))
        out.appendLine("fitAccel on distance bins placed at their median peak speed (surrogate AccelAnalysis, equal bin sizes):")
        out.appendLine("best-family BIC gain:               " + dist(rows.map { it.bicB }))
        out.appendLine("gain range of best family:          " + dist(rows.map { it.rangeB }))
        out.appendLine("curve offered:                      ${rows.count { it.offeredB }} of $seeds")
        out.appendLine()
        out.appendLine("-- Pearson correlation of ln(1+error) with ln(peak speed), flat players")
        out.appendLine("unconditional, per player, averaged:                              mean %.3f (n=%d)".format(meanOf(rows.map { it.corrAll }), rows.size))
        out.appendLine("within distance deciles, per player then averaged over players:   mean %.3f median %.3f p90 %.3f".format(meanOf(rows.map { it.corrWithin }), median(rows.map { it.corrWithin }.filter { !it.isNaN() }), percentileOf(rows.map { it.corrWithin }, 0.9)))
        out.appendLine("within distance deciles of the pooled ${pooled.size} movements, averaged over deciles: %.3f".format(decileCorr(pooled, { it.dist }, { it.y }, { ln(it.speed) })))
        out.appendLine("pooled unconditional:                                              %.3f".format(pearson(pooled.map { it.y }, pooled.map { ln(it.speed) })))
        out.appendLine("for reference: corr(ln(1+error), ln(distance)) pooled %.3f; within pooled speed deciles %.3f; corr(ln distance, ln speed) pooled %.3f".format(
            pearson(pooled.map { it.y }, pooled.map { ln(it.dist) }),
            decileCorr(pooled, { it.speed }, { it.y }, { ln(it.dist) }),
            pearson(pooled.map { ln(it.dist) }, pooled.map { ln(it.speed) })))
        out.appendLine()
        out.appendLine("-- how the sim generates endpoint noise (core/sim/SimulatedAimer.kt planReachBy, lines 326-338):")
        out.appendLine("   durationMs = max(MIN_REACH_MS, fitts + handCm / maxHandSpeedCmPerS * 1000) * fatigue   // fitts = intercept + slope * log2(1 + amplitude/width)")
        out.appendLine("   val sigma = player.noiseLevel * fatigue * sqrt(sq(player.signalNoise * amplitude) + sq(player.handNoiseCm * FULL_TURN / effectiveCm360))")
        out.appendLine("   moveX = error.x * (1 - undershootBias) + sigma * gaussian()")
        out.appendLine("   gains(): peak speed = PEAK_TO_MEAN_SPEED * (amplitude / (degPerCount * expected) / dpi) / duration")
        out.appendLine("   => sigma has a term proportional to the reach amplitude and a constant term; the peak speed grows with amplitude too (duration grows only with log2); both are functions of the same amplitude.")
        out.appendLine()
        out.appendLine("-- per player: slope(speed bins), BIC(a), offered(a) | slope(distance bins vs ln dist), BIC(b), offered(b)")
        rows.forEachIndexed { i, r ->
            out.appendLine("  seed %2d: %+.4f %6.1f %-5s | %+.4f %6.1f %-5s".format(i, r.slopeSpeed, r.bicA, r.offeredA, r.slopeDist, r.bicB, r.offeredB))
        }
        write("review-optimizer-e1.txt", out.toString())
    }

    // ------------------------------------------------------------------ E2

    private val SPEEDS = listOf(5.0, 10.0, 20.0, 40.0)

    private class PipelineResult(
        val ref: List<Double>,
        val bestSpeed: AccelFit?, val offeredSpeed: Boolean,
        val bestDist: AccelFit?, val offeredDist: Boolean,
    )

    private fun pipeline(change: Double, seed: Long): PipelineResult {
        val metrics = plainRounds(gainPlayer(change), seed)
        val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
        val fit = fitAccel(analysis)
        val used = usable(mvs(metrics))
        val distBins = binBy(used, AccelAnalysis.MIN_PER_BIN, AccelAnalysis.MAX_BINS, AccelAnalysis.MIN_BINS) { it.dist }
        val (bestB, offB) = surrogateFit(distBins)
        return PipelineResult(analysis.used.map { it.speed }, fit.fits.maxByOrNull { it.bicGain }, fit.recommended != null, bestB, offB)
    }

    private fun fitLog(fit: AccelFit?): (Double) -> Double = if (fit == null) { _ -> 0.0 } else { v -> ln(fit.totalGain(v)) }

    @Test
    fun e2PeakVsProfile() {
        val seeds = 20
        val out = StringBuilder("== E2 peak vs profile: $seeds players per preference, $calibrationRounds plain calibration rounds; gains normalized to median log gain 0 over the player's own used speeds ==\n")
        for (change in listOf(0.15, 0.30, -0.15, -0.30)) {
            val res = parallelList(seeds) { s -> pipeline(change, 21_000L + s) }
            out.appendLine("-- true preference %+.0f%%: g(v) = 1 %+.2f * (1 - exp(-v/6))".format(change * 100, change))
            out.appendLine("   offered: current pipeline %d of %d | distance-binned fit %d of %d".format(res.count { it.offeredSpeed }, seeds, res.count { it.offeredDist }, seeds))
            out.appendLine("   speed in/s:                         " + SPEEDS.joinToString("  ") { "%7.0f".format(it) })
            out.appendLine("   true wanted gain (raw g):           " + SPEEDS.joinToString("  ") { "%7.3f".format(trueGain(change, it)) })
            out.appendLine("   true wanted gain (normalized):      " + SPEEDS.joinToString("  ") { v -> "%7.3f".format(meanOf(res.map { trueNormalized(change, it.ref, v) })) })
            fun row(label: String, fn: (PipelineResult) -> (Double) -> Double) {
                val means = SPEEDS.map { v -> meanOf(res.map { r -> normalizedLog(fn(r), r.ref)(v) }.map { exp(it) }) }
                val sds = SPEEDS.map { v -> res.map { r -> exp(normalizedLog(fn(r), r.ref)(v)) }.let { l -> val m = l.average(); sqrt(l.sumOf { (it - m) * (it - m) } / (l.size - 1)) } }
                val errs = res.map { r -> curveError(fn(r), change, r.ref) }
                out.appendLine("   %-35s %s | SD across players %s | curve RMS error: %s".format(label, means.joinToString("  ") { "%7.3f".format(it) }, sds.joinToString(" ") { "%.3f".format(it) }, dist(errs)))
            }
            row("fitted totalGain, speed bins, best BIC") { fitLog(it.bestSpeed) }
            row("fitted, speed bins, recommended or plain") { r -> if (r.offeredSpeed) fitLog(r.bestSpeed) else fitLog(null) }
            row("fitted, distance bins at median speed") { fitLog(it.bestDist) }
            row("fitted, distance, recommended or plain") { r -> if (r.offeredDist) fitLog(r.bestDist) else fitLog(null) }
            out.appendLine("   best-family BIC gain: speed bins %s | distance bins %s".format(dist(res.map { it.bestSpeed?.bicGain ?: Double.NaN }), dist(res.map { it.bestDist?.bicGain ?: Double.NaN })))
            out.appendLine("   best-family gain range: speed bins %s | distance bins %s".format(dist(res.map { it.bestSpeed?.gainRange ?: Double.NaN }), dist(res.map { it.bestDist?.gainRange ?: Double.NaN })))
            out.appendLine("   speed range of used movements (pooled p10/p50/p90): %.1f / %.1f / %.1f".format(
                percentileOf(res.flatMap { it.ref }, 0.1), percentileOf(res.flatMap { it.ref }, 0.5), percentileOf(res.flatMap { it.ref }, 0.9)))
        }
        write("review-optimizer-e2.txt", out.toString())
    }

    // ------------------------------------------------------------------ E3

    private enum class Key { SPEED, DISTANCE }

    private val knotsLn = (0 until 8).map { ln(3.0) + (ln(60.0) - ln(3.0)) * it / 7.0 }

    private fun interp(x: Double, xs: List<Double>, ys: List<Double>): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val i = xs.indices.last { xs[it] <= x }.coerceAtMost(xs.size - 2)
        val span = xs[i + 1] - xs[i]
        if (span <= 0.0) return (ys[i] + ys[i + 1]) / 2.0
        val t = (x - xs[i]) / span
        return ys[i] * (1 - t) + ys[i + 1] * t
    }

    private class Built(val curve: AccelCurve?, val factor: Double, val log: (Double) -> Double)

    private val decays = listOf(0.01, 0.02, 0.04, 0.08, 0.15, 0.3, 0.6)
    private val limits = (40..240 step 2).filter { it !in 98..102 }.map { it / 100.0 }
    private val fitGrid = (0 until 40).map { ln(3.0) + (ln(80.0) - ln(3.0)) * it / 39.0 }

    /** Natural curve + sensitivity factor that best reproduces the log-gain table [t] at [knotsLn]; the median total gain over [ref] is 1. */
    private fun build(t: DoubleArray, ref: List<Double>): Built {
        if (t.all { abs(it) < 1e-4 } || ref.isEmpty()) return Built(null, 1.0) { 0.0 }
        val target = fitGrid.map { interp(it, knotsLn, t.toList()) }
        var bestSse = Double.MAX_VALUE
        var best: NaturalCurve? = null
        for (d in decays) for (l in limits) {
            val c = NaturalCurve(d, l)
            val r = fitGrid.indices.map { target[it] - ln(c.multiplier(exp(fitGrid[it]))) }
            val k = r.average()
            val sse = r.sumOf { (it - k) * (it - k) }
            if (sse < bestSse) { bestSse = sse; best = c }
        }
        val curve = checkNotNull(best)
        val factor = exp(-median(ref.map { ln(curve.multiplier(it)) }))
        return Built(curve, factor) { v -> ln(factor * curve.multiplier(v)) }
    }

    private class ClRow(
        val curveErr: List<Double>, val residual: List<Double>, val finalMaxDev: Double, val finalMaxDevSpeed3to60: Double,
    )

    private fun closedLoop(change: Double, seed: Long, key: Key, step: Double): ClRow {
        val player = gainPlayer(change)
        val t = DoubleArray(knotsLn.size)
        var built = build(t, emptyList())
        var ref: List<Double> = emptyList()
        val curveErr = mutableListOf<Double>()
        val residual = mutableListOf<Double>()
        for (batch in 0 until 4) {
            val b = built
            val metrics = (0 until 6).map { i ->
                val idx = batch * 6 + i
                computeRoundMetrics(HeadlessRound.of(kinds[idx % kinds.size], player, TEST_DPI, base / b.factor, seed * 1_000 + idx, 45, accel = b.curve))
            }
            val used = usable(mvs(metrics))
            if (batch == 0) {
                ref = used.map { it.speed }
                curveErr += curveError(b.log, change, ref)
            }
            val bins = binBy(used, 15, 8, 3) { if (key == Key.SPEED) it.speed else it.dist }
            if (bins.isEmpty()) {
                residual += Double.NaN
                curveErr += curveErr.last()
                continue
            }
            residual += sqrt(bins.sumOf { it.logGain * it.logGain } / bins.size)
            val sortedBins = bins.sortedBy { it.speedMedian }
            val xs = sortedBins.map { ln(it.speedMedian) }
            val ys = sortedBins.map { it.logGain }
            for (k in knotsLn.indices) t[k] += step * interp(knotsLn[k], xs, ys)
            built = build(t, ref)
            curveErr += curveError(built.log, change, ref)
        }
        val speeds = geometricGrid(3.0, 60.0, 30)
        val nl = normalizedLog(built.log, ref)
        val maxDev = speeds.maxOf { abs(exp(nl(it)) - 1.0) }
        return ClRow(curveErr, residual, maxDev, maxDev)
    }

    @Test
    fun e3ClosedLoop() {
        val seeds = 20
        val out = StringBuilder(
            "== E3 closed-loop prototype: $seeds players per row; 4 batches of 6 rounds (24 rounds): batch 0 plain, batches 1-3 played with the curve built from the previous batches; step 50 % of the estimated correction per update ==\n" +
                "Curve representation: test-local AccelCurve is impossible (AccelCurve is sealed), so the 8-knot log-gain table (knots at 3..60 in/s, ln spacing, constant outside, piecewise-linear in ln speed) is rendered as the best-fitting NaturalCurve + sensitivity factor (grid over decay/limit, played at base/factor cm/360); median total gain over batch-0 speeds = 1.\n" +
                "Estimation per update: the 6 newest rounds only, equal-count bins (15 per bin, 3..8 bins), bin wanted-minus-applied log gain = median(all) - median(bin) of ln(1+error); bins keyed by speed or by distance (placed at bin median speed).\n" +
                "Curve error = RMS over speeds 5,10,20,40 in/s of applied/true wanted gain - 1 (both normalized to median log 0 over batch-0 speeds). G_k = curve after k updates (G_0 = plain). Residual k = RMS of the bin residuals measured in batch k (played with G_k); noise floor of a perfect curve is not zero.\n\n",
        )
        for (change in listOf(0.0, 0.15, 0.30, -0.15, -0.30)) {
            out.appendLine("---- true preference %+.0f%%".format(change * 100))
            val pipe = parallelList(seeds) { s ->
                val p = pipeline(change, 31_000L + s)
                val fit = if (p.offeredSpeed) p.bestSpeed else null
                val ref = p.ref
                val maxDev = fit?.let { f -> geometricGrid(3.0, 60.0, 30).let { sp -> val nl = normalizedLog(fitLog(f), ref); sp.maxOf { abs(exp(nl(it)) - 1.0) } } } ?: 0.0
                Triple(curveError(fitLog(fit), change, ref), p.offeredSpeed, maxDev)
            }
            for (key in Key.entries) {
                val rows = parallelList(seeds) { s -> closedLoop(change, 31_000L + s, key, 0.5) }
                out.appendLine("  bins by $key:")
                for (k in 0..4) {
                    val e = rows.map { it.curveErr[k] }
                    out.appendLine("    curve error G_%d: mean %.4f median %.4f p90 %.4f max %.4f%s".format(k, meanOf(e), median(e), percentileOf(e, 0.9), e.max(), if (k == 0) " (plain)" else if (k == 4) " (final, all 24 rounds)" else ""))
                }
                for (k in 0..3) {
                    val r = rows.map { it.residual[k] }
                    out.appendLine("    residual bin RMS batch %d: mean %.4f median %.4f p90 %.4f".format(k, meanOf(r), median(r), percentileOf(r, 0.9)))
                }
                out.appendLine("    final curve max |gain - 1| over 3..60 in/s (normalized): " + dist(rows.map { it.finalMaxDev }) + if (change == 0.0) "   <- false-positive size (flat player)" else "")
            }
            out.appendLine("  current pipeline (15 plain rounds, recommended curve or plain): curve error " + dist(pipe.map { it.first }) +
                " | offered ${pipe.count { it.second }} of $seeds | max |gain-1| over 3..60 in/s: " + dist(pipe.map { it.third }))
        }
        write("review-optimizer-e3.txt", out.toString())
    }

    // ------------------------------------------------------------------ E4

    private class AbRun(val diffs: List<Double>, val status: AbStatus, val pairsUsed: Int, val finalMean: Double)

    private fun abRun(player: SimulatedPlayer, curve: AccelCurve?, seed: Long, pairs: Int = 12): AbRun {
        val plan = AccelAbPlan(kinds, seed, pairs)
        val outcomes = mutableListOf<AbOutcome>()
        for (index in 0 until 2 * pairs) {
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            val rec = HeadlessRound.of(
                round.scenario, player, TEST_DPI, base, round.seed, 45,
                sessionOffsetNanos = index * 45L * 1_000_000_000L, accel = if (accelerated) curve else null,
            )
            outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs())
        }
        val scores = ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(outcomes.map { it.inputs })
        val diffs = (0 until pairs).mapNotNull { p ->
            val idx = outcomes.indices.filter { outcomes[it].pair == p }
            val a = idx.firstOrNull { outcomes[it].condition == AbCondition.ACCEL }?.let { scores[it] }
            val b = idx.firstOrNull { outcomes[it].condition == AbCondition.PLAIN }?.let { scores[it] }
            if (a != null && b != null) a - b else null
        }
        var status = AbStatus.NEED_MORE_ROUNDS
        var used = pairs
        for (p in 1..pairs) {
            val v = evaluateAb(outcomes.take(2 * p), MetricWeights.DEFAULT)
            if (v.status != AbStatus.NEED_MORE_ROUNDS) { status = v.status; used = p; break }
        }
        return AbRun(diffs, status, used, diffs.average())
    }

    private fun quote(file: String, maxLines: Int, filter: ((String) -> Boolean)? = null): String {
        val p = Path.of("build", "reports", "harness", file)
        if (!Files.exists(p)) return "($file not found)"
        return Files.readAllLines(p).let { l -> if (filter != null) l.filter(filter) else l }.take(maxLines).joinToString("\n")
    }

    @Test
    fun e4AbPower() {
        val seeds = 20
        val out = StringBuilder("== E4 A/B power ==\n")
        out.appendLine("-- existing reports, copied verbatim (core/build/reports/harness)")
        out.appendLine("[accel-ab-power.txt]\n" + quote("accel-ab-power.txt", 6))
        out.appendLine("[review-accel.txt]\n" + quote("review-accel.txt", 12))
        out.appendLine("[review-accel-power.txt]\n" + quote("review-accel-power.txt", 20))
        out.appendLine("[assess-strength.txt]\n" + quote("assess-strength.txt", 10))
        out.appendLine("[assess-strength-range.txt] (first 30 lines)\n" + quote("assess-strength-range.txt", 30))
        out.appendLine("[assess-precision.txt] cost of a sensitivity mis-set (score points lost vs the optimum at x0.70 x0.85 x1.15 x1.30 x1.50 x2.00 of the optimum cm/360) and round SD:")
        out.appendLine(quote("assess-precision.txt", 12) { it.contains("flatness") || it.startsWith("== noise") })
        out.appendLine()
        out.appendLine("-- measured now: players with true preference, TRUE optimal natural curve (best-fitting natural curve to the true shape, factor 1) vs plain, 12 pairs of the real AccelAbPlan, $seeds seeds, base $base cm/360")
        for (change in listOf(0.15, 0.30, -0.15, -0.30)) {
            val player = gainPlayer(change)
            val oracle = oracleCurve(change)
            val runs = parallelList(seeds) { s -> abRun(player, oracle, 41_000L + s) }
            val allDiffs = runs.flatMap { it.diffs }
            val pooledMean = allDiffs.average()
            val pooledSd = sqrt(allDiffs.sumOf { (it - pooledMean) * (it - pooledMean) } / (allDiffs.size - 1))
            val perSeedSd = runs.map { r -> val m = r.diffs.average(); sqrt(r.diffs.sumOf { (it - m) * (it - m) } / (r.diffs.size - 1)) }
            out.appendLine(
                "  true %+.0f%% (oracle natural limit %.2f decay %.2f): per-pair score difference mean %+.2f pts, pooled SD %.2f (per-seed SD mean %.2f); per-seed mean of 12 pairs: mean %+.2f SD %.2f min %+.2f max %+.2f | evaluateAb sequential: better %d, worse %d, no difference %d (of %d); pairs used median %.0f, p90 %.0f".format(
                    change * 100, oracle.limit, oracle.decayRate, pooledMean, pooledSd, perSeedSd.average(),
                    runs.map { it.finalMean }.average(), sqrt(runs.sumOf { (it.finalMean - runs.map { r -> r.finalMean }.average()).pow(2) } / (runs.size - 1)),
                    runs.minOf { it.finalMean }, runs.maxOf { it.finalMean },
                    runs.count { it.status == AbStatus.ACCEL_BETTER }, runs.count { it.status == AbStatus.ACCEL_WORSE }, runs.count { it.status == AbStatus.NEED_MORE_ROUNDS }.let { n -> runs.count { it.status == AbStatus.NO_DIFFERENCE } + n }, seeds,
                    median(runs.map { it.pairsUsed.toDouble() }), percentileOf(runs.map { it.pairsUsed.toDouble() }, 0.9),
                ),
            )
        }
        write("review-optimizer-e4.txt", out.toString())
    }

    // ------------------------------------------------------------------ E5

    @Test
    fun e5SessionDrift() {
        val seeds = 20
        val sessions = 4
        val perSession = 10
        val range = SearchRange(20.0, 140.0)
        val out = StringBuilder(
            "== E5 session drift: $sessions sessions x $perSession rounds (FLICK, default geometry), $seeds seeds; range 20-140 cm/360 ==\n" +
                "Drift mechanism: SimulatedPlayer().withOptimum(50 * (1+d)^session) i.e. optimumScale changes (all effects depend on cm360/optimumScale); truth measured by a 17-point 20-160 cm/360 sweep (20 rounds per point, smooth degree-4 fit argmax) of each session's player.\n\n",
        )
        val grid = geometricGrid(20.0, 160.0, 17)
        fun player(d: Double, session: Int) = SimulatedPlayer().withOptimum(50.0 * (1.0 + d).pow(session))
        val weightsets = listOf("d1 duplicate 4/3/2/1 (newest..oldest)" to listOf(4, 3, 2, 1), "d2 duplicate 4/2/1/1" to listOf(4, 2, 1, 1))
        for (d in listOf(0.0, 0.05, 0.10)) {
            val truth = (0 until sessions).map { s ->
                if (d == 0.0 && s > 0) null
                else {
                    val inputs = sweepInputs(player(d, s), grid, 20, 77L + s)
                    SmoothCurve(grid, meanScoresFor(inputs, MetricWeights.DEFAULT)).argmax(20.0, 160.0)
                }
            }
            val current = truth[sessions - 1] ?: truth[0]!!
            out.appendLine("-- drift %.0f%% per session: nominal optimum by session %s | measured by sweep %s".format(
                d * 100, (0 until sessions).joinToString(" ") { "%.1f".format(50.0 * (1.0 + d).pow(it)) },
                (0 until sessions).joinToString(" ") { s -> (truth[s] ?: truth[0]!!).let { "%.1f".format(it) } + if (truth[s] == null) "*" else "" }))
            out.appendLine("   (* = same player as session 0). CURRENT (session 4) true optimum used for the error: %.1f cm/360".format(current))
            val results = parallelList(seeds) { s ->
                val seed = 51_000L + s
                val test = playBlindTest(player(d, 0), range, sessions * perSession, seed, sessionOf = { (it / perSession).toLong() }, playerFor = { player(d, it / perSession) })
                val history = test.history
                val bySession = history.groupBy { it.sessionId }
                fun est(obs: List<Observation>): Double = SensitivityOptimizer(range, seed, OptimizerConfig()).analyze(obs)?.optimumCm360 ?: Double.NaN
                val newest = (sessions - 1).toLong()
                val estimates = mutableMapOf<String, Double>()
                estimates["a pool all 4 sessions (as the app)"] = checkNotNull(test.result).optimumCm360
                estimates["b newest session only"] = est(bySession.getValue(newest))
                estimates["c newest 2 sessions"] = est(history.filter { it.sessionId >= newest - 1 })
                for ((label, w) in weightsets) {
                    val dup = history.flatMap { o -> List(w[(newest - o.sessionId).toInt()]) { o } }
                    estimates[label] = est(dup)
                }
                estimates
            }
            val labels = results.first().keys.toList()
            for (label in labels) {
                val errs = results.map { 100.0 * abs(it.getValue(label) - current) / current }
                val signed = results.map { 100.0 * (it.getValue(label) - current) / current }
                out.appendLine("   %-40s abs %% error: median %5.1f  p90 %5.1f  mean %5.1f | signed median %+5.1f%% | n=%d valid".format(
                    label, median(errs.filter { !it.isNaN() }), percentileOf(errs, 0.9), meanOf(errs), median(signed.filter { !it.isNaN() }), errs.count { !it.isNaN() }))
            }
            out.appendLine("   estimate vs the SESSION-1 optimum (%.1f), pooled-all estimator: median abs %% error %.1f".format(
                truth[0]!!, median(results.map { 100.0 * abs(it.getValue(labels[0]) - truth[0]!!) / truth[0]!! })))
        }
        write("review-optimizer-e5.txt", out.toString())
    }
}
