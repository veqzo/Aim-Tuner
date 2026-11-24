package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AbVerdict
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.DEFAULT_ALPHA_PER_LOOK
import dev.veqzo.aimtune.core.accel.boundedRecommendation
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelFit
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.optimizer.OptimizerConfig
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.NoiseModel
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Reusable gate harness: the same four sections (sensitivity, curve detection, flat false positives, strength search) over
 * several player models, with pluggable methods, so every later change can rerun it and print a before/after table.
 */

// ------------------------------------------------------------------ shared helpers

val CLICK_KINDS = listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO)
const val CALIBRATION_BASE_CM360 = 55.0

/** The gain a simulated player with preference [change] expects at hand speed [v] (in/s): 1 + c (1 - exp(-v/6)). */
fun trueGain(change: Double, v: Double): Double = 1.0 + change * (1.0 - exp(-v / 6.0))

fun gainPlayer(change: Double) = SimulatedPlayer(gainPreference = { v -> trueGain(change, v) })

/** Best natural curve for the true preference shape (the oracle a perfect fit could reach). */
fun oracleCurve(change: Double): NaturalCurve {
    val speeds = (1..40).map { it.toDouble() }
    return listOf(0.01, 0.02, 0.04, 0.08, 0.15, 0.3).map { NaturalCurve(it, 1.0 + change) }.minBy { c ->
        speeds.sumOf { v -> val d = c.multiplier(v) - trueGain(change, v); d * d }
    }
}

fun median(values: List<Double>): Double {
    val v = values.filter { !it.isNaN() }
    if (v.isEmpty()) return Double.NaN
    val s = v.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
}

fun percentileOf(values: List<Double>, p: Double): Double {
    val v = values.filter { !it.isNaN() }
    if (v.isEmpty()) return Double.NaN
    return v.sorted()[((v.size - 1) * p).toInt()]
}

fun meanOf(values: List<Double>): Double = values.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

/** Plain calibration rounds (click scenarios in turn, as the app does). */
fun playPlainRounds(
    player: SimulatedPlayer,
    seed: Long,
    rounds: Int,
    kinds: List<ScenarioKind> = CLICK_KINDS,
    cm360: Double = CALIBRATION_BASE_CM360,
): List<RoundMetrics> =
    (0 until rounds).map { computeRoundMetrics(HeadlessRound.of(kinds[it % kinds.size], player, TEST_DPI, cm360, seed * 1_000 + it, ROUND_SECONDS)) }

/** Mean paired score difference (curve minus plain) over [pairs] pairs of the real A/B plan, and its standard error. */
fun pairedDifference(player: SimulatedPlayer, base: Double, curve: AccelCurve, factor: Double, pairs: Int, seed: Long): Pair<Double, Double> {
    val plan = AccelAbPlan(CLICK_KINDS, seed, pairs)
    val inputs = mutableListOf<Map<ScoreMetric, Double>>()
    val conditions = mutableListOf<AbCondition>()
    val ids = mutableListOf<String>()
    for (index in 0 until 2 * pairs) {
        val round = plan.round(index)
        val accelerated = round.condition == AbCondition.ACCEL
        val rec = HeadlessRound.of(
            round.scenario, player, TEST_DPI, if (accelerated) base / factor else base, round.seed, ROUND_SECONDS,
            sessionOffsetNanos = index * ROUND_SECONDS * NANOS_PER_SECOND, accel = if (accelerated) curve else null,
        )
        inputs += computeRoundMetrics(rec).scoreInputs()
        conditions += round.condition
        ids += round.scenario.id
    }
    val scores = ScoreCalculator(MetricWeights.DEFAULT).scoreRounds(inputs, ids)
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

/** The blind A/B test of the app on a curve, looked at after every pair, over at most [pairs] pairs. */
fun abVerdict(
    player: SimulatedPlayer, base: Double, curve: AccelCurve, factor: Double, seed: Long, pairs: Int = 12,
    alpha: Double = DEFAULT_ALPHA_PER_LOOK, grouped: Boolean = true,
): AbVerdict {
    val plan = AccelAbPlan(CLICK_KINDS, seed, pairs)
    val outcomes = mutableListOf<AbOutcome>()
    var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT, alphaPerLook = alpha, groupByScenario = grouped)
    for (index in 0 until 2 * plan.maxPairs) {
        val round = plan.round(index)
        val accelerated = round.condition == AbCondition.ACCEL
        val rec = HeadlessRound.of(
            round.scenario, player, TEST_DPI, if (accelerated) base / factor else base, round.seed, ROUND_SECONDS,
            sessionOffsetNanos = index * ROUND_SECONDS * NANOS_PER_SECOND, accel = if (accelerated) curve else null,
        )
        outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs(), round.scenario.id)
        if (index % 2 == 1) {
            verdict = evaluateAb(outcomes, MetricWeights.DEFAULT, alphaPerLook = alpha, groupByScenario = grouped)
            if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
        }
    }
    return verdict
}

/** All rounds of an A/B test (curve at [base]/[factor] against plain at [base]; a null curve plays identical arms with different seeds per member). */
fun playAbRounds(player: SimulatedPlayer, base: Double, curve: AccelCurve?, factor: Double, seed: Long, pairs: Int = 12): List<AbOutcome> {
    val plan = AccelAbPlan(CLICK_KINDS, seed, pairs)
    return (0 until 2 * pairs).map { index ->
        val round = plan.round(index)
        val accelerated = round.condition == AbCondition.ACCEL
        val roundSeed = round.seed + if (curve == null && accelerated) 5_000 else 0
        val rec = HeadlessRound.of(
            round.scenario, player, TEST_DPI, if (accelerated) base / factor else base, roundSeed, ROUND_SECONDS,
            sessionOffsetNanos = index * ROUND_SECONDS * NANOS_PER_SECOND, accel = if (accelerated) curve else null,
        )
        AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs(), round.scenario.id)
    }
}

/** The sequential verdict (a look after every pair) of already played rounds. */
fun judgeAb(outcomes: List<AbOutcome>, alpha: Double, grouped: Boolean): AbStatus {
    val pairs = outcomes.size / 2
    for (p in 1..pairs) {
        val v = evaluateAb(outcomes.take(2 * p), MetricWeights.DEFAULT, alphaPerLook = alpha, groupByScenario = grouped)
        if (v.status != AbStatus.NEED_MORE_ROUNDS) return v.status
    }
    return AbStatus.NO_DIFFERENCE
}

class AbVariant(val label: String, val alpha: Double, val grouped: Boolean)

val abVariants = listOf(
    AbVariant("grp .010", 0.010, true), AbVariant("grp .008", 0.008, true), AbVariant("grp .006", 0.006, true), AbVariant("pooled .010", 0.010, false),
)

// ------------------------------------------------------------------ player models

/** A named transform applied on top of the case's player. Add a model by adding one entry to [gateModels]. */
class GateModel(val name: String, val transform: (SimulatedPlayer) -> SimulatedPlayer) {
    fun apply(player: SimulatedPlayer): SimulatedPlayer = transform(player)
}

val gateModels: List<GateModel> = listOf(
    GateModel("DEFAULT") { it },
    GateModel("DECOUPLED") { it.copy(noiseModel = NoiseModel.DECOUPLED) },
    GateModel("RANGE") { it.copy(rangeBias = 0.05) },
    // HandAdaptation is mutable: apply() builds a fresh instance, so call it once per simulated person (per seed), never per cell.
    GateModel("ADAPTIVE") { it.copy(adaptation = HandAdaptation.adaptedTo(it.gainPreference)) },
)

/** The model's player without adaptation state, for reference sweeps that run rounds in parallel (flat players have nothing to adapt to). */
private fun GateModel.stateless(player: SimulatedPlayer): SimulatedPlayer = apply(player).copy(adaptation = null)

// ------------------------------------------------------------------ pluggable methods

/** The sensitivity optimizer under test: a name and its configuration. */
class SensMethod(val name: String, val config: OptimizerConfig = OptimizerConfig())

/**
 * The acceleration analysis under test. [playCalibration] decides how the calibration rounds are played, [recommend] what is
 * offered from them, [bicGain] the best-family BIC gain without thresholds (NaN when the method has none).
 */
fun interface CurveMethod {
    fun recommend(rounds: List<RoundMetrics>, dpi: Double): AccelFit?

    fun playCalibration(player: SimulatedPlayer, seed: Long, rounds: Int): List<RoundMetrics> = playPlainRounds(player, seed, rounds)

    fun bicGain(rounds: List<RoundMetrics>, dpi: Double): Double = Double.NaN
}

/** Same detection as legacy; the curve is bounded to the 5th-95th percentile of the used speeds. */
object BoundedCurveMethod : CurveMethod {
    override fun recommend(rounds: List<RoundMetrics>, dpi: Double): AccelFit? {
        val analysis = AccelAnalysis(movementSamples(rounds, dpi))
        return boundedRecommendation(fitAccel(analysis), analysis)
    }

    override fun bicGain(rounds: List<RoundMetrics>, dpi: Double): Double = LegacyCurveMethod.bicGain(rounds, dpi)
}

object LegacyCurveMethod : CurveMethod {
    override fun recommend(rounds: List<RoundMetrics>, dpi: Double): AccelFit? =
        fitAccel(AccelAnalysis(movementSamples(rounds, dpi))).recommended

    override fun bicGain(rounds: List<RoundMetrics>, dpi: Double): Double =
        fitAccel(AccelAnalysis(movementSamples(rounds, dpi)), minBicGain = -1e9, minGainRange = 0.0).fits.maxOfOrNull { it.bicGain } ?: Double.NaN
}

/** Everything a gate run needs. */
class GateConfig(
    val alpha: Double = DEFAULT_ALPHA_PER_LOOK,
    val grouped: Boolean = true,
    val tag: String = "before",
    val sections: Set<Char> = setOf('S', 'C', 'F', 'A', 'N'),
    val seedScale: Double = 1.0,
    val models: List<GateModel> = gateModels,
    val sens: SensMethod = SensMethod("v1 OptimizerConfig()"),
    val curve: CurveMethod = LegacyCurveMethod,
    val curveName: String = "legacy fitAccel",
    val range: SearchRange = SearchRange(20.0, 140.0),
) {
    fun n(base: Int): Int = (base * seedScale).toInt().coerceAtLeast(2)
}

fun gitShortHash(): String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    text.ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}

fun runGate(cfg: GateConfig): String {
    val out = StringBuilder()
    out.appendLine("== GATE '${cfg.tag}' @ git ${gitShortHash()} | models: ${cfg.models.joinToString { it.name }} | sections: ${cfg.sections.sorted().joinToString(",")} | sens: ${cfg.sens.name} | curve: ${cfg.curveName} | A/B alpha ${cfg.alpha} ${if (cfg.grouped) "grouped" else "pooled"} ==")
    out.appendLine("seeds: S ${cfg.n(40)} per row | C ${cfg.n(30)} per cell (oracle ${cfg.n(30)}) | F ${cfg.n(200)} at 15 rounds, ${cfg.n(60)} at 8 and 30 | A ${cfg.n(16)} per row")
    val started = System.currentTimeMillis()
    if ('S' in cfg.sections) { gateSensitivity(cfg, out); out.appendLine("[S done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)); out.appendLine() }
    if ('C' in cfg.sections) { gateCurves(cfg, out); out.appendLine("[C done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)); out.appendLine() }
    if ('F' in cfg.sections) { gateFlat(cfg, out); out.appendLine("[F done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)); out.appendLine() }
    if ('A' in cfg.sections) { gateStrength(cfg, out); out.appendLine("[A done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)); out.appendLine() }
    if ('V' in cfg.sections) { gateVariants(cfg, out); out.appendLine("[V done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)) }
    if ('N' in cfg.sections) { gateNull(cfg, out); out.appendLine("[N done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000)) }
    return out.toString()
}

// ------------------------------------------------------------------ S: sensitivity

private class SensRun(val optimum: Double, val truth: Double, val lo: Double, val hi: Double, val confidence: Int, val trueLoss: Double, val negligible: Double) {
    val factor: Double get() = errorFactorOf(optimum, truth)
    val ok: Boolean get() = trueLoss <= negligible
}

fun gateSensitivity(cfg: GateConfig, out: StringBuilder) {
    val seeds = cfg.n(40)
    val grid = geometricGrid(20.0, 160.0, 17)
    out.appendLine("-- S sensitivity: single session, opt 50 cm/360, range ${cfg.range.minCm360.toInt()}-${cfg.range.maxCm360.toInt()}, $seeds seeds. Truth = smooth fit of a 17-point reference sweep (24 rounds/point) of the model's player.")
    out.appendLine("model      rounds truth  medErr  <=1.15  <=1.30  cover80  conf  | conf<60: n ok% | 60-79: n ok% | >=80: n ok%")
    for (model in cfg.models) {
        val base = SimulatedPlayer().withOptimum(50.0)
        val truth = SmoothCurve(grid, meanScoresFor(sweepInputs(model.stateless(base), grid, 24, 99), MetricWeights.DEFAULT))
        val best = truth.argmax(cfg.range.minCm360, cfg.range.maxCm360)
        for (rounds in listOf(8, 15, 30)) {
            val runs = parallelList(seeds) { s ->
                val r = checkNotNull(playBlindTest(model.apply(base), cfg.range, rounds, 1000L + s, config = cfg.sens.config).result)
                val clamped = r.optimumCm360.coerceIn(cfg.range.minCm360, cfg.range.maxCm360)
                SensRun(r.optimumCm360, best, r.intervalLowCm360, r.intervalHighCm360, r.confidencePercent, truth.at(best) - truth.at(clamped), r.negligibleDifference)
            }
            fun share(sel: List<SensRun>, pred: (SensRun) -> Boolean) = if (sel.isEmpty()) "   - " else "%3.0f%%".format(100.0 * sel.count(pred) / sel.size)
            val bins = listOf(runs.filter { it.confidence < 60 }, runs.filter { it.confidence in 60..79 }, runs.filter { it.confidence >= 80 })
            out.appendLine(
                "%-10s %5d %5.1f  x%.3f  %5.0f%%  %5.0f%%  %5.0f%%  %4.0f  | %3d  %s | %3d  %s | %3d  %s".format(
                    model.name, rounds, best, median(runs.map { it.factor }),
                    100.0 * runs.count { it.factor <= 1.15 } / seeds, 100.0 * runs.count { it.factor <= 1.3 } / seeds,
                    100.0 * runs.count { best in it.lo * 0.999..it.hi * 1.001 } / seeds, runs.map { it.confidence.toDouble() }.average(),
                    bins[0].size, share(bins[0]) { it.ok }, bins[1].size, share(bins[1]) { it.ok }, bins[2].size, share(bins[2]) { it.ok },
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ C: curve detection

private class CurveRun(
    val offered: Boolean, val right: Boolean, val verdict: AbStatus?,
    val errUsed: Double, val errExtra: Double, val noneUsed: Double, val noneExtra: Double,
)

/** RMS of ln(applied / true wanted gain) with both centred on their median over [speeds]: over [speeds] and over the extrapolated 20, 30, 40 in/s. */
private fun gainErrors(logApplied: (Double) -> Double, change: Double, speeds: List<Double>): Pair<Double, Double> {
    if (speeds.isEmpty()) return Double.NaN to Double.NaN
    val gc = median(speeds.map(logApplied))
    val tc = median(speeds.map { ln(trueGain(change, it)) })
    fun d(v: Double) = (logApplied(v) - gc) - (ln(trueGain(change, v)) - tc)
    fun rms(vs: List<Double>) = sqrt(vs.sumOf { d(it) * d(it) } / vs.size)
    return rms(speeds) to rms(listOf(20.0, 30.0, 40.0))
}

fun gateCurves(cfg: GateConfig, out: StringBuilder) {
    val seeds = cfg.n(30)
    out.appendLine("-- C curve detection: preference c -> g(v)=1+c(1-exp(-v/6)); $seeds seeds per cell; A/B = blind test of offered curves (max 12 pairs); err(used) = movement-weighted RMS of ln(applied/true gain) over the run's used speeds, err(20-40) = RMS at 20, 30, 40 in/s; 'none' = the same for no curve (log gain 0)")
    out.appendLine("model      pref  rounds offered right | A/B better worse nodiff | err(used) rec  none | err(20-40) rec  none | oracle A/B pts (15 rounds)")
    for (model in cfg.models) for (change in listOf(-0.30, -0.15, 0.15, 0.30)) {
        for (rounds in listOf(8, 15, 30)) {
            val runs = parallelList(seeds) { s ->
                val player = model.apply(gainPlayer(change))
                val calib = cfg.curve.playCalibration(player, 60_000L + s, rounds)
                val fit = cfg.curve.recommend(calib, TEST_DPI)
                val speeds = AccelAnalysis(movementSamples(calib, TEST_DPI)).used.map { it.speed }
                val applied: (Double) -> Double = if (fit == null) { _ -> 0.0 } else { v -> ln(fit.totalGain(v)) }
                val (a, b) = gainErrors(applied, change, speeds)
                val (na, nb) = gainErrors({ 0.0 }, change, speeds)
                val verdict = fit?.let { abVerdict(player, CALIBRATION_BASE_CM360, it.curve, it.sensitivityFactor, 800L + s, alpha = cfg.alpha, grouped = cfg.grouped).status }
                val right = fit != null && (fit.curve.multiplier(30.0) > fit.curve.multiplier(3.0)) == (change > 0)
                CurveRun(fit != null, right, verdict, a, b, na, nb)
            }
            val oracle = if (rounds == 15) {
                val o = oracleCurve(change)
                "%+.2f".format(meanOf(parallelList(seeds) { s -> pairedDifference(model.apply(gainPlayer(change)), CALIBRATION_BASE_CM360, o, 1.0, 12, 700L + s).first }))
            } else "-"
            val v = runs.mapNotNull { it.verdict }
            out.appendLine(
                "%-10s %+4.0f%% %5d  %3d/%-3d %3d  |     %3d   %3d   %3d  |    %.3f %.3f |    %.3f %.3f | %s".format(
                    model.name, change * 100, rounds, runs.count { it.offered }, seeds, runs.count { it.right },
                    v.count { it == AbStatus.ACCEL_BETTER }, v.count { it == AbStatus.ACCEL_WORSE },
                    v.count { it == AbStatus.NO_DIFFERENCE || it == AbStatus.NEED_MORE_ROUNDS },
                    meanOf(runs.map { it.errUsed }), meanOf(runs.map { it.noneUsed }),
                    meanOf(runs.map { it.errExtra }), meanOf(runs.map { it.noneExtra }), oracle,
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ F: flat false positives

fun gateFlat(cfg: GateConfig, out: StringBuilder) {
    out.appendLine("-- F flat players (no gain preference): curves offered and best-family BIC gain without thresholds")
    out.appendLine("model      rounds    n offered | BIC gain p50   p99   max")
    for (model in cfg.models) {
        for ((rounds, base) in listOf(15 to 200, 8 to 60, 30 to 60)) {
            val n = cfg.n(base)
            val runs = parallelList(n) { s ->
                val calib = cfg.curve.playCalibration(model.apply(SimulatedPlayer()), 120_000L + s, rounds)
                (cfg.curve.recommend(calib, TEST_DPI) != null) to cfg.curve.bicGain(calib, TEST_DPI)
            }
            val bic = runs.map { it.second }
            out.appendLine(
                "%-10s %5d %4d %7d | %12.1f %5.1f %5.1f".format(
                    model.name, rounds, n, runs.count { it.first }, percentileOf(bic, 0.5), percentileOf(bic, 0.99), bic.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ A: blind strength search

/** The strength search as AssessmentTest emulates it: the optimizer over a pseudo cm/360 (100 = no curve) that stands for the gain ratio fast/slow. */
private fun strengthCurve(pseudo: Double): AccelCurve? {
    val r = pseudo / 100.0
    return if (abs(r - 1.0) < 0.02) null else NaturalCurve(0.05, r)
}

fun gateStrength(cfg: GateConfig, out: StringBuilder) {
    val n = cfg.n(16)
    val rounds = 24
    val range = SearchRange(70.0, 145.0)
    out.appendLine("-- A strength search: $rounds blind rounds over strength 0.70-1.45, $n seeds per row; excl. = interval excludes 'no curve' (strength 1.00)")
    out.appendLine("model      pref   found median (p25-p75)  excl. no curve  conf mean")
    for (model in cfg.models) for (change in listOf(0.0, -0.15, 0.15)) {
        val rows = parallelList(n) { s ->
            val result = checkNotNull(
                playBlindTest(model.apply(gainPlayer(change)), range, rounds, 600L + s, config = cfg.sens.config, scenarioFor = { CLICK_KINDS[it % 3] }, accelOf = ::strengthCurve, playedCm360 = CALIBRATION_BASE_CM360).result,
            )
            Triple(result.optimumCm360 / 100.0, result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0, result.confidencePercent.toDouble())
        }
        val found = rows.map { it.first }
        out.appendLine(
            "%-10s %+4.0f%%   x%.2f (x%.2f-x%.2f)          %3d/%-3d     %4.0f".format(
                model.name, change * 100, median(found), percentileOf(found, 0.25), percentileOf(found, 0.75),
                rows.count { it.second }, n, rows.map { it.third }.average(),
            ),
        )
    }
}
