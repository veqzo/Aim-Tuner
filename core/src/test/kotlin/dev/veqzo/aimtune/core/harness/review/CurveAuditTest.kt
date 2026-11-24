package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.AccelFit
import dev.veqzo.aimtune.core.accel.CurveStrength
import dev.veqzo.aimtune.core.accel.DEFAULT_MIN_EFFECT_POINTS
import dev.veqzo.aimtune.core.accel.LookupCurve
import dev.veqzo.aimtune.core.accel.MIN_BIC_GAIN
import dev.veqzo.aimtune.core.accel.MIN_GAIN_RANGE
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.accel.NoAccel
import dev.veqzo.aimtune.core.accel.boundedRecommendation
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.accel.strengthAccel
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import dev.veqzo.aimtune.core.units.SearchRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Audit of the curve finder against the user's real situation: a player who plays Valorant with a Raw Accel classic gain
 * curve (x0.75 output factor) and uses the app at plain sensitivity. Section 1 = current behaviour (error analysis, strength
 * search, level check, sensitivity finder), section 2 = candidate changes. Gated like the other review experiments.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class CurveAuditTest {
    private val n = System.getenv("AIMTUNE_SEEDS")?.toIntOrNull()?.let { (24 * it / 40).coerceAtLeast(4) } ?: 24
    private val base = CALIBRATION_BASE_CM360
    private val alpha = 0.01
    private val out = StringBuilder()
    private val started = System.currentTimeMillis()

    // ------------------------------------------------------------------ the user's curve

    private fun rawMult(x: Double): Double {
        val off = 10.0
        val capX = 62.5
        val a = 0.6 / 2.0 / (capX - off) // (y'/m)^(1/(m-1)) / (capx - off) with m = 2
        fun baseFn(v: Double) = a * (v - off).pow(2.0) / v
        val constant = (baseFn(capX) - 0.6) * capX
        return when {
            x <= off -> 1.0
            x < capX -> 1.0 + baseFn(x)
            else -> 1.0 + constant / x + 0.6
        }
    }

    // AccelCurve is a sealed interface, so the curve is a dense lookup table (257 points max); kinks at 10 and 62.5 are knots.
    private val USER_CURVE: AccelCurve = run {
        val geo = (0 until 255).map { 0.5 * (150.0 / 0.5).pow(it / 254.0) }
        val speeds = (geo + listOf(10.0, 62.5)).sorted().fold(mutableListOf<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 1e-3) acc.add(v); acc }
        LookupCurve(speeds, speeds.map { rawMult(it) })
    }

    private fun userDelivered(v: Double) = 0.75 * rawMult(v)

    // ------------------------------------------------------------------ models

    private class Model(val name: String, val dir: Int, val make: () -> SimulatedPlayer)

    private val adaptedBase = { SimulatedPlayer(adaptation = HandAdaptation.adaptedTo({ 1.0 }, ::userDelivered)) }
    private val models = listOf(
        Model("ADAPTED", 0, adaptedBase),
        Model("ADAPTED_RANGE", 0) { adaptedBase().copy(rangeBias = 0.05) },
        Model("FLAT", 0) { SimulatedPlayer() },
        Model("FLAT_RANGE", 0) { SimulatedPlayer(rangeBias = 0.05) },
        Model("RISING", 1) { gainPlayer(0.30) },
        Model("FALLING", -1) { gainPlayer(-0.30) },
    )

    // ------------------------------------------------------------------ shared pieces

    private class Run(val offered: Boolean, val falling: Boolean, val exported: Boolean, val worse: Boolean, val pd: Double)

    private fun judge(outcomes: List<AbOutcome>, minEffect: Double): AbStatus {
        for (p in 1..outcomes.size / 2) {
            val v = evaluateAb(outcomes.take(2 * p), MetricWeights.DEFAULT, alphaPerLook = alpha, minEffectPoints = minEffect, groupByScenario = false)
            if (v.status != AbStatus.NEED_MORE_ROUNDS) return v.status
        }
        return AbStatus.NO_DIFFERENCE
    }

    private fun isFalling(curve: AccelCurve) = curve.multiplier(30.0) < curve.multiplier(5.0)

    private fun pct(c: Int, total: Int) = String.format(Locale.ROOT, "%3.0f%%", 100.0 * c / total)

    private fun num(x: Double, fmt: String = "%+6.2f") = if (x.isNaN()) "     -" else String.format(Locale.ROOT, fmt, x)

    private fun line(text: String) { out.appendLine(text) }

    private fun stage(name: String) {
        println("[$name done, elapsed ${(System.currentTimeMillis() - started) / 1000} s]")
        write("audit-curve.txt", out.toString())
    }

    private fun summary(label: String, model: Model, runs: List<Run>): String {
        val total = runs.size
        val exports = runs.filter { it.exported }
        val offered = runs.filter { it.offered }
        val right = when (model.dir) {
            1 -> pct(exports.count { !it.falling }, total)
            -1 -> pct(exports.count { it.falling }, total)
            else -> "   -"
        }
        val harmfulOffers = offered.count { it.worse || it.pd < 0 }
        return String.format(
            Locale.ROOT, "%-22s %-14s %3d | %s %s | %s %s %s | %s %s | %s",
            label, model.name, total, pct(offered.size, total), pct(offered.count { it.falling }, total),
            pct(exports.size, total), pct(exports.count { it.falling }, total), right,
            num(median(exports.map { it.pd })), pct(exports.count { it.pd < 0 }, total), pct(harmfulOffers, total),
        )
    }

    private val summaryHeader = "variant                model           n | offer fallO | expor fallE right | medBen harmE | harmOffer"

    // ------------------------------------------------------------------ section 1a / 2 error analysis

    private class ErrVariant(val label: String, val bounded: Boolean, val risingOnly: Boolean, val fallStrict: Boolean)

    private val errVariants = listOf(
        ErrVariant("E legacy", false, false, false),
        ErrVariant("E bounded", true, false, false),
        ErrVariant("E legacy O1e rise-only", false, true, false),
        ErrVariant("E bounded O1e rise-only", true, true, false),
        ErrVariant("E legacy O2e fall 2xBIC", false, false, true),
        ErrVariant("E bounded O2e fall 2xBIC", true, false, true),
    )

    private fun recommend(calib: List<dev.veqzo.aimtune.core.metrics.RoundMetrics>, v: ErrVariant): AccelFit? {
        val analysis = AccelAnalysis(movementSamples(calib, TEST_DPI))
        val result = fitAccel(analysis)
        var chosen = result.recommended
        if (v.risingOnly) {
            chosen = result.fits.filter { it.curve.multiplier(30.0) >= it.curve.multiplier(5.0) }.maxByOrNull { it.bicGain }
                ?.takeIf { it.bicGain >= MIN_BIC_GAIN && it.gainRange >= MIN_GAIN_RANGE }
        }
        if (v.fallStrict && chosen != null && isFalling(chosen.curve) && chosen.bicGain < 2 * MIN_BIC_GAIN) chosen = null
        return if (v.bounded && chosen != null) boundedRecommendation(result.copy(recommended = chosen), analysis) else chosen
    }

    private fun wantedSlope(calib: List<dev.veqzo.aimtune.core.metrics.RoundMetrics>): Double {
        val pts = AccelAnalysis(movementSamples(calib, TEST_DPI)).points
        if (pts.size < 2) return Double.NaN
        val xs = pts.map { ln(it.speed) }
        val ys = pts.map { it.logGain }
        val mx = xs.average()
        val my = ys.average()
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
    }

    private class ErrSeed(val runs: List<Run>, val slope: Double)

    private fun errSeed(model: Model, s: Int): ErrSeed {
        val calib = playPlainRounds(model.make(), 60_000L + s, 15)
        val fits = errVariants.map { recommend(calib, it) }
        val cache = HashMap<AccelFit, Pair<AbStatus, Double>>()
        val runs = fits.map { fit ->
            if (fit == null) return@map Run(false, false, false, false, Double.NaN)
            val (status, pd) = cache.getOrPut(fit) {
                // Fresh person with the same calibration history, so every distinct curve meets the same hand.
                val p = model.make()
                playPlainRounds(p, 60_000L + s, 15)
                val outcomes = playAbRounds(p, base, fit.curve, fit.sensitivityFactor, 800L + s)
                judge(outcomes, DEFAULT_MIN_EFFECT_POINTS) to pairedDifference(p, base, fit.curve, fit.sensitivityFactor, 12, 700L + s).first
            }
            Run(true, isFalling(fit.curve), status == AbStatus.ACCEL_BETTER, status == AbStatus.ACCEL_WORSE, pd)
        }
        return ErrSeed(runs, wantedSlope(calib))
    }

    private fun errorSection(list: List<Model> = models, tag: String = "") {
        line("== 1a/2 ERROR ANALYSIS (15 plain calibration rounds, then fit; A/B = alpha $alpha pooled/ungrouped, max 12 pairs; benefit = independent 12-pair paired difference) ==")
        line("offer = curve offered; fallO = falling offered (of n); expor = offered and A/B ACCEL_BETTER; fallE = falling exports; right = exports in the right direction (RISING: rising, FALLING: falling);")
        line("medBen = median paired benefit of exports (score pts); harmE = exports with benefit < 0; harmOffer = offered curves with A/B worse or benefit < 0; slope = median d ln(wanted gain)/d ln(speed)")
        line(summaryHeader + " | slope")
        for (model in list) {
            val seeds = parallelList(n) { errSeed(model, it) }
            val slope = median(seeds.map { it.slope })
            errVariants.forEachIndexed { i, v -> line(summary(v.label, model, seeds.map { it.runs[i] }) + " | " + num(slope, "%+.3f")) }
            line("")
        }
        stage("error analysis")
    }

    // ------------------------------------------------------------------ section 1b / 2 strength search

    private class StrRaw(val best: Double, val lo: Double, val hi: Double, val status: AbStatus?, val strict: AbStatus?, val pd: Double, val rangeLo: Double)

    private fun strengthSeed(model: Model, s: Int, rangeLo: Double, rangeHi: Double, twoStage: Boolean): StrRaw {
        val p = model.make()
        var played = base
        if (twoStage) played = checkNotNull(playBlindTest(p, SearchRange(20.0, 140.0), 20, 5_000L + s).result).optimumCm360
        val res = checkNotNull(
            playBlindTest(
                p, SearchRange(rangeLo * 100, rangeHi * 100), 24, 600L + s, scenarioFor = { CLICK_KINDS[it % 3] },
                accelOf = { CurveStrength.curve(it / 100.0) }, playedCm360 = played,
            ).result,
        )
        val best = res.optimumCm360 / 100.0
        val lo = res.intervalLowCm360 / 100.0
        val hi = res.intervalHighCm360 / 100.0
        if (!CurveStrength.isCurve(best)) return StrRaw(best, lo, hi, null, null, Double.NaN, rangeLo)
        val fit = strengthAccel(best)
        val outcomes = playAbRounds(p, played, fit.curve, 1.0, 800L + s)
        return StrRaw(
            best, lo, hi, judge(outcomes, DEFAULT_MIN_EFFECT_POINTS), judge(outcomes, 4.0),
            pairedDifference(p, played, fit.curve, 1.0, 12, 700L + s).first, rangeLo,
        )
    }

    private fun excludesPlain(r: StrRaw) = r.lo > 1.0 || r.hi < 1.0

    private enum class Rule { LIVE, ASYM, STRICT_FALL }

    private fun toRun(r: StrRaw, rule: Rule): Run {
        val curve = CurveStrength.isCurve(r.best)
        val falling = r.best < 1.0
        val offered = curve && when (rule) {
            Rule.ASYM -> if (falling) r.hi < 0.90 else r.lo > 1.0
            else -> excludesPlain(r)
        }
        val status = if (rule == Rule.STRICT_FALL && falling) r.strict else r.status
        return Run(offered, falling, offered && status == AbStatus.ACCEL_BETTER, offered && status == AbStatus.ACCEL_WORSE, r.pd)
    }

    private fun strengthExtras(raws: List<StrRaw>): String {
        val best = raws.map { it.best }
        val lastOffer = raws.filter { CurveStrength.isCurve(it.best) }
        val total = raws.size
        return String.format(
            Locale.ROOT, "x%.2f (%.2f-%.2f) | low %s fall %s rise %s | last %s lastHarm %s lastBen %s",
            median(best), percentileOf(best, 0.25), percentileOf(best, 0.75),
            pct(raws.count { it.best <= it.rangeLo + 0.02 }, total), pct(raws.count { it.best < 0.98 }, total), pct(raws.count { it.best > 1.02 }, total),
            pct(lastOffer.size, total), pct(lastOffer.count { it.status == AbStatus.ACCEL_WORSE || it.pd < 0 }, total), num(median(lastOffer.map { it.pd })),
        )
    }

    private fun strengthSection(list: List<Model> = models) {
        line("== 1b/2 STRENGTH SEARCH (24 blind click rounds at base $base cm/360, pseudo cm/360 = 100 x strength; A/B of the found curve at factor 1) ==")
        line("live offer = interval excludes strength 1.00 and best is a curve; last = best is a curve (Last search screen, exported without A/B); lastHarm = last-offered with A/B worse or benefit < 0; lastBen = median benefit of last-offered;")
        line("O2 asym = falling offered only if interval upper end < 0.90; O3 = falling export needs A/B mean >= 4 pts; O4 = sensitivity search first (20 rounds, 20-140), strength search then played at the found cm/360; O5 = mandatory A/B: export% of every live/last row is the O5 rate (last% is the no-A/B rate)")
        line("low = best <= range low + 0.02")
        line(summaryHeader + " | best median (p25-p75) | edge/direction | last-offer stats")
        for (model in list) {
            val r175 = parallelList(n) { strengthSeed(model, it, 0.70, 1.75, false) }
            val r145 = parallelList(n) { strengthSeed(model, it, 0.70, 1.45, false) }
            val r100 = parallelList(n) { strengthSeed(model, it, 1.00, 1.75, false) }
            val two = parallelList(n) { strengthSeed(model, it, 0.70, 1.75, true) }
            fun row(label: String, raws: List<StrRaw>, rule: Rule) =
                line(summary(label, model, raws.map { toRun(it, rule) }) + " | " + strengthExtras(raws))
            row("S 0.70-1.75 live", r175, Rule.LIVE)
            row("S 0.70-1.45 live", r145, Rule.LIVE)
            row("S O1 1.00-1.75", r100, Rule.LIVE)
            row("S O2 asym interval", r175, Rule.ASYM)
            row("S O3 fall needs 4pts", r175, Rule.STRICT_FALL)
            row("S O4 two-stage", two, Rule.LIVE)
            line("")
        }
        stage("strength search")
    }

    // ------------------------------------------------------------------ section 1c level check

    private fun levelSection() {
        val seeds = maxOf(4, n / 3)
        line("== 1c LEVEL CHECK: paired benefit (curve arm minus plain arm at base $base, 12 pairs) over $seeds seeds; a fresh person per cell; 'const' = no curve, only the sensitivity factor (NoAccel curve) ==")
        line("benefit > 0 means the arm played at base/factor with the curve scores higher than plain. Share = seeds with benefit > 0.")
        val conds = listOf<Triple<String, AccelCurve, Double>>(
            Triple("Natural(0.05, 0.70) f1.00", NaturalCurve(0.05, 0.70), 1.0),
            Triple("const f0.85 (no curve)", NoAccel, 0.85),
            Triple("const f0.75 (no curve)", NoAccel, 0.75),
            Triple("USER_CURVE f0.75", USER_CURVE, 0.75),
            Triple("Natural(0.05, 1.30) f0.80", NaturalCurve(0.05, 1.30), 0.8),
        )
        line(String.format(Locale.ROOT, "%-28s %-14s %8s %8s %7s", "condition", "model", "mean", "median", ">0"))
        for (name in listOf("ADAPTED", "FLAT")) {
            val model = models.first { it.name == name }
            for ((label, curve, factor) in conds) {
                val d = parallelList(seeds) { s -> pairedDifference(model.make(), base, curve, factor, 12, 1_000L + s).first }
                line(String.format(Locale.ROOT, "%-28s %-14s %+8.2f %+8.2f %4d/%d", label, name, d.average(), median(d), d.count { it > 0 }, seeds))
            }
        }
        line("")
        line("-- aftereffect of the ADAPTED hand: HandAdaptation.factor(v) (factor on the expected gain) at the start and after 15 plain rounds at base, mean of $seeds seeds; USER_DELIVERED(v) shown for reference")
        val speeds = listOf(3.0, 10.0, 20.0, 30.0, 50.0)
        val pairs = parallelList(seeds) { s ->
            val player = adaptedBase()
            val adaptation = checkNotNull(player.adaptation)
            val start = speeds.map { adaptation.factor(it) }
            playPlainRounds(player, 60_000L + s, 15)
            start to speeds.map { adaptation.factor(it) }
        }
        line(String.format(Locale.ROOT, "%-14s %s", "v in/s", speeds.joinToString("  ") { String.format(Locale.ROOT, "%7.0f", it) }))
        line(String.format(Locale.ROOT, "%-14s %s", "delivered", speeds.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", userDelivered(it)) }))
        line(String.format(Locale.ROOT, "%-14s %s", "factor start", speeds.indices.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", pairs.map { p -> p.first[it] }.average()) }))
        line(String.format(Locale.ROOT, "%-14s %s", "factor after15", speeds.indices.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", pairs.map { p -> p.second[it] }.average()) }))
        line("")
        stage("level check")
    }

    // ------------------------------------------------------------------ section 1d sensitivity finder

    private fun sensitivitySection(list: List<Model> = models) {
        line("== 1d SENSITIVITY FINDER: 20 flick rounds, range 20-140 cm/360, $n seeds; recommended cm/360 relative to base $base ==")
        line(String.format(Locale.ROOT, "%-14s %9s %15s %9s", "model", "median", "p25-p75", ">1.10"))
        for (model in list) {
            val r = parallelList(n) { s -> checkNotNull(playBlindTest(model.make(), SearchRange(20.0, 140.0), 20, 3_000L + s).result).optimumCm360 / base }
            line(String.format(Locale.ROOT, "%-14s  x%.3f   x%.3f-x%.3f   %s", model.name, median(r), percentileOf(r, 0.25), percentileOf(r, 0.75), pct(r.count { it > 1.10 }, n)))
        }
        line("")
        stage("sensitivity")
    }

    // ------------------------------------------------------------------ section 3 real level mismatch

    private fun highDelivered(v: Double) = 0.530 * USER_CURVE.multiplier(v)
    private val highBase = { SimulatedPlayer(adaptation = HandAdaptation.adaptedTo({ 1.0 }, ::highDelivered)) }
    private val highModels = listOf(Model("ADAPTED_HIGH", 0, highBase), Model("ADAPTED_HIGH_RANGE", 0) { highBase().copy(rangeBias = 0.05) })

    private fun levelHigh() {
        val seeds = maxOf(4, n / 3)
        line("-- 3 level check: paired benefit (12 pairs, base $base) over $seeds seeds")
        val conds = listOf<Triple<String, AccelCurve, Double>>(
            Triple("Natural(0.05, 0.70) f1.00", NaturalCurve(0.05, 0.70), 1.0),
            Triple("const f0.85 (no curve)", NoAccel, 0.85),
            Triple("const f0.70 (no curve)", NoAccel, 0.70),
            Triple("const f0.55 (no curve)", NoAccel, 0.55),
            Triple("USER_CURVE f0.530", USER_CURVE, 0.530),
            Triple("Natural(0.05, 1.30) f0.60", NaturalCurve(0.05, 1.30), 0.6),
        )
        line(String.format(Locale.ROOT, "%-28s %-20s %8s %8s %7s", "condition", "model", "mean", "median", ">0"))
        for (model in highModels) for ((label, curve, factor) in conds) {
            val d = parallelList(seeds) { s -> pairedDifference(model.make(), base, curve, factor, 12, 1_000L + s).first }
            line(String.format(Locale.ROOT, "%-28s %-20s %+8.2f %+8.2f %4d/%d", label, model.name, d.average(), median(d), d.count { it > 0 }, seeds))
        }
        line("-- adaptation factor of ADAPTED_HIGH at start and after 15 plain rounds (mean of $seeds seeds); delivered = 0.530 x user curve")
        val speeds = listOf(3.0, 10.0, 20.0, 30.0, 50.0)
        val pairs = parallelList(seeds) { s ->
            val player = highBase()
            val adaptation = checkNotNull(player.adaptation)
            val start = speeds.map { adaptation.factor(it) }
            playPlainRounds(player, 60_000L + s, 15)
            start to speeds.map { adaptation.factor(it) }
        }
        line(String.format(Locale.ROOT, "%-14s %s", "v in/s", speeds.joinToString("  ") { String.format(Locale.ROOT, "%7.0f", it) }))
        line(String.format(Locale.ROOT, "%-14s %s", "delivered", speeds.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", highDelivered(it)) }))
        line(String.format(Locale.ROOT, "%-14s %s", "factor start", speeds.indices.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", pairs.map { p -> p.first[it] }.average()) }))
        line(String.format(Locale.ROOT, "%-14s %s", "factor after15", speeds.indices.joinToString("  ") { String.format(Locale.ROOT, "%7.3f", pairs.map { p -> p.second[it] }.average()) }))
        line("")
    }

    private fun sectionThree() {
        line("== 3. REAL LEVEL MISMATCH: hand adapted to 0.530 x user curve (curve rounds were played at 34.4 cm/360, plain tests at 48.7 -> relative gain 0.75 x 34.4/48.7); tested at base $base ==")
        sensitivitySection(highModels)
        levelHigh()
        errorSection(highModels)
        strengthSection(highModels)
        stage("section 3")
    }

    // ------------------------------------------------------------------ driver

    @Test
    fun curveAudit() {
        line("== CURVE AUDIT @ git ${gitShortHash()} | n = $n seeds per row (level check ${maxOf(4, n / 3)}) | user curve: Raw Accel classic gain, m 2, offset 10 in/s, cap in_out 62.5 in/s / 1.6, factor 0.75 ==")
        line(
            String.format(
                Locale.ROOT, "USER_CURVE check (lookup / exact): mult(20) %.4f/%.4f, (30) %.4f/%.4f, (50) %.4f/%.4f, (62.5) %.4f/%.4f, (100) %.4f/%.4f (expected 1.0286 1.0762 1.1829 1.2520 1.3825)",
                USER_CURVE.multiplier(20.0), rawMult(20.0), USER_CURVE.multiplier(30.0), rawMult(30.0), USER_CURVE.multiplier(50.0), rawMult(50.0),
                USER_CURVE.multiplier(62.5), rawMult(62.5), USER_CURVE.multiplier(100.0), rawMult(100.0),
            ),
        )
        line("models: ADAPTED = flat preference, hand adapted to 0.75 x user curve, tested at plain; _RANGE = rangeBias 0.05; FLAT = no preference; RISING/FALLING = gainPlayer(+/-0.30)")
        line("")
        sensitivitySection()
        levelSection()
        errorSection()
        strengthSection()
        sectionThree()
        line("== NOTES ==")
        line("- Strength search plays click rounds only (flash rounds are not simulated), range 0.70-1.75 is the app range with flash but without the flash rounds.")
        line("- A/B uses alpha $alpha ungrouped (app default), min effect ${DEFAULT_MIN_EFFECT_POINTS} pts, max 12 pairs, looked at after every pair; benefit = independent paired difference (12 pairs) of the same curve on the same continuing person.")
        line("- Search, A/B and benefit rounds of one seed are played by the same simulated person (adaptation state carries on); the error analysis replays the calibration for every distinct curve so each curve meets the same hand.")
        line("- Slope = regression of the binned wanted log gain on ln(speed) (unweighted), median over seeds.")
        line("- O2e is applied as a post-filter: a falling best fit needs bicGain >= 2 x $MIN_BIC_GAIN (equivalent to a second fitAccel run with the doubled threshold for falling fits). O1e drops fits whose unbounded curve falls between 5 and 30 in/s and re-picks the best remaining family (same thresholds).")
        line("- Falling/rising of an offered curve: multiplier(30) < multiplier(5) of the offered (bounded) curve; for strengths: best < 1.")
        line("- O3 is a re-judging of the same A/B rounds with minEffectPoints 4 for falling curves. O4 benefit is vs plain at the found cm/360, not at base. O5 (mandatory A/B) equals the expor column; the Last search no-A/B rate is the 'last' column.")
        line("- In the strength 'right' column RISING needs a rising export, FALLING a falling export; ADAPTED/FLAT rows have no right direction.")
        line("- Level check conditions with 'const' play a NoAccel curve at base/factor.")
        stage("all")
    }
}
