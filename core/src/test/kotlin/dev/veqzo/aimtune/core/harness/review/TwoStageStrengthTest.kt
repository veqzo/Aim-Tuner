package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.CurveStrength
import dev.veqzo.aimtune.core.accel.DEFAULT_MIN_EFFECT_POINTS
import dev.veqzo.aimtune.core.accel.LookupCurve
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.strengthAccel
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
import kotlin.math.sqrt

/**
 * M19 gate: does a level stage before the strength search keep the detection of a true rising or falling preference while it
 * removes the falling results of hands that only want another overall level (docs/history/CURVE-FINDER-AUDIT.md, option O4)?
 * The hands are those of the audit: ADAPTED (adapted to 0.75 x the user's Raw Accel curve), ADAPTED_HIGH (0.53 x, the user's real
 * level), FLAT, RISING and FALLING (gainPlayer +-0.30).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class TwoStageStrengthTest {
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

    private enum class Level { NONE, FULL, WHEN_CLEAR, HALF }

    private class Run(val best: Double, val lo: Double, val hi: Double, val offered: Boolean, val exported: Boolean, val pd: Double, val level: Double)

    private fun run(model: Model, s: Int, level: Level, levelRounds: Int): Run {
        val p = model.make()
        var played = base
        if (level != Level.NONE) {
            val found = checkNotNull(playBlindTest(p, SearchRange(20.0, 140.0), levelRounds, 5_000L + s).result)
            val clear = found.intervalLowCm360 > base || found.intervalHighCm360 < base
            played = when (level) {
                Level.FULL -> found.optimumCm360
                Level.WHEN_CLEAR -> if (clear) found.optimumCm360 else base
                Level.HALF -> sqrt(found.optimumCm360 * base)
                Level.NONE -> base
            }
        }
        val res = checkNotNull(
            playBlindTest(
                p, SearchRange(70.0, 175.0), 24, 600L + s, scenarioFor = { CLICK_KINDS[it % 3] },
                accelOf = { CurveStrength.curve(it / 100.0) }, playedCm360 = played,
            ).result,
        )
        val best = res.optimumCm360 / 100.0
        val lo = res.intervalLowCm360 / 100.0
        val hi = res.intervalHighCm360 / 100.0
        val offered = CurveStrength.excludesPlain(best, lo, hi)
        if (!offered) return Run(best, lo, hi, false, false, Double.NaN, played)
        val curve = strengthAccel(best).curve
        val outcomes = playAbRounds(p, played, curve, 1.0, 800L + s)
        var status = AbStatus.NO_DIFFERENCE
        for (pair in 1..outcomes.size / 2) {
            val v = evaluateAb(outcomes.take(2 * pair), MetricWeights.DEFAULT, alphaPerLook = 0.01, minEffectPoints = DEFAULT_MIN_EFFECT_POINTS, groupByScenario = false)
            if (v.status != AbStatus.NEED_MORE_ROUNDS) { status = v.status; break }
        }
        return Run(best, lo, hi, true, status == AbStatus.ACCEL_BETTER, pairedDifference(p, played, curve, 1.0, 12, 700L + s).first, played)
    }

    private fun pct(c: Int, total: Int) = String.format(Locale.ROOT, "%3.0f%%", 100.0 * c / total)

    @Test
    fun twoStage() {
        val header = String.format(Locale.ROOT, "%-18s %-13s %3s | offer fall | export fall right | harmOff | best med | level med", "variant", "model", "n")
        out.appendLine("== M19 two-stage strength search @ git ${gitShortHash()} | $n seeds per row | strength 0.70-1.75, 24 click rounds, A/B 12 pairs ==")
        out.appendLine("fall = best < 1; right = exported in the true direction; harmOff = offered with A/B worse or paired benefit < 0; level = cm/360 the strength search was played at (base $base)")
        out.appendLine(header)
        val variants = listOf(Triple("current", Level.NONE, 0), Triple("O4 full 20", Level.FULL, 20), Triple("level when clear", Level.WHEN_CLEAR, 20), Triple("level half-way", Level.HALF, 20))
        for ((label, level, rounds) in variants) {
            for (model in models) {
                val runs = parallelList(n) { run(model, it, level, rounds) }
                val exports = runs.filter { it.exported }
                val right = when (model.dir) {
                    1 -> pct(exports.count { it.best > 1.0 }, n)
                    -1 -> pct(exports.count { it.best < 1.0 }, n)
                    else -> "   -"
                }
                out.appendLine(
                    String.format(
                        Locale.ROOT, "%-18s %-13s %3d | %s %s | %s %s %s | %s | %8.2f | %8.1f", label, model.name, n,
                        pct(runs.count { it.offered }, n), pct(runs.count { it.offered && it.best < 1.0 }, n),
                        pct(exports.size, n), pct(exports.count { it.best < 1.0 }, n), right,
                        pct(runs.count { it.offered && (it.pd < 0 || !it.exported && it.pd.isNaN().not() && it.pd < 0) }, n),
                        median(runs.map { it.best }), median(runs.map { it.level }),
                    ),
                )
            }
            out.appendLine("")
            write("two-stage-strength.txt", out.toString())
        }
    }
}
