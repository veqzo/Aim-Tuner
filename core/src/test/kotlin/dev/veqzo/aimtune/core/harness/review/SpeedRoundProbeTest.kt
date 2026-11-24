package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AccelProcessor
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.CalibrationReach
import dev.veqzo.aimtune.core.accel.SpeedRoundFit
import dev.veqzo.aimtune.core.accel.analyseSpeedRounds
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.allFirstReaches
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.FlickConfig
import dev.veqzo.aimtune.core.scenario.FlickScenario
import dev.veqzo.aimtune.core.scenario.TargetEvent
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.NoiseModel
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class SpeedRoundProbeTest {
    private val cm360 = 55.0
    private val seconds = 45

    private class SeedRun(val reaches: List<CalibrationReach>, val reaches8: List<CalibrationReach>, val fit8: SpeedRoundFit, val fit16: SpeedRoundFit, val hits: Int, val targets: Int)

    private fun gain(c: Double, v: Double) = 1.0 + c * (1.0 - exp(-v / 6.0))

    private fun makePlayer(model: String, pref: Double): SimulatedPlayer {
        val base = if (pref == 0.0) SimulatedPlayer() else SimulatedPlayer(gainPreference = { v -> gain(pref, v) })
        return when (model) {
            "DEFAULT" -> base
            "DECOUPLED" -> base.copy(noiseModel = NoiseModel.DECOUPLED)
            "RANGE" -> base.copy(rangeBias = 0.05)
            "ADAPTIVE" -> base.copy(adaptation = HandAdaptation.adaptedTo(base.gainPreference))
            "PRESSURE" -> base.copy(pressureUndershoot = 0.1)
            else -> error(model)
        }
    }

    private fun play(model: String, pref: Double, f: Double, seed: Int): SeedRun {
        val player = makePlayer(model, pref)
        val all = mutableListOf<CalibrationReach>()
        val normalTimes = mutableListOf<Long>()
        var fit8: SpeedRoundFit? = null
        var reaches8: List<CalibrationReach> = emptyList()
        var hits = 0
        var targets = 0
        for (r in 0 until 16) {
            val speed = r % 2 == 1
            val roundSeed = 90_000L + seed * 100L + r
            val lifetime = if (speed) (f * median(normalTimes.map { it.toDouble() })).toLong().coerceAtLeast(1L) else null
            val scenario = FlickScenario(FlickConfig(seconds * NANOS_PER_SECOND, targetLifetimeNanos = lifetime), roundSeed)
            val rec = HeadlessRound.run(player, TEST_DPI, cm360, roundSeed, seconds, r.toLong() * seconds * NANOS_PER_SECOND, scenario)
            val metrics = computeRoundMetrics(rec)
            if (!speed) normalTimes += metrics.movements.map { it.timeToTargetNanos }
            if (speed) {
                hits += metrics.hits
                targets += rec.targetEvents.count { it is TargetEvent.Spawned }
            }
            for (m in allFirstReaches(rec)) {
                val peak = m.firstPeakSpeedCountsPerMs ?: continue
                val err = m.firstErrorRatio ?: continue
                all += CalibrationReach(m.initialDistanceDegrees, AccelProcessor.speedInPerSecond(peak, TEST_DPI), err, speed)
            }
            if (r == 7) { reaches8 = all.toList(); fit8 = analyseSpeedRounds(reaches8) }
        }
        return SeedRun(all, reaches8, checkNotNull(fit8), analyseSpeedRounds(all), hits, targets)
    }

    private fun used(rs: List<CalibrationReach>) = rs.filter {
        it.speed >= AccelAnalysis.MIN_SPEED && (1.0 + it.errorRatio) in AccelAnalysis.MIN_RATIO..AccelAnalysis.MAX_RATIO && it.distance > 0.0
    }

    private fun corr(rs: List<CalibrationReach>): Double {
        val x = rs.map { ln(it.distance) }
        val y = rs.map { ln(it.speed) }
        val mx = x.average()
        val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        return sxy / sqrt(x.sumOf { (it - mx) * (it - mx) } * y.sumOf { (it - my) * (it - my) })
    }

    private fun sd(v: List<Double>): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun trueSlope(pref: Double, reaches: List<CalibrationReach>): Double {
        val x = used(reaches).map { ln(it.speed) }
        val y = used(reaches).map { ln(gain(pref, it.speed)) }
        val mx = x.average()
        val my = y.average()
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.sumOf { (it - mx) * (it - mx) }
    }

    @Test
    fun probe() {
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        val prefs = listOf(0.0, -0.30, -0.15, 0.15, 0.30)
        val models = listOf("DEFAULT", "DECOUPLED", "RANGE", "ADAPTIVE", "PRESSURE")
        val seedsN = (System.getenv("AIMTUNE_SEEDS") ?: "20").toInt()
        val flatSeeds = if (seedsN >= 20) 60 else seedsN
        out.appendLine("seeds per cell $seedsN (flat $flatSeeds); R=8 is the 8-round prefix of the same 16-round runs")
        out.appendLine("true slope (seed 0, DEFAULT, f=1.0, R=16 used reaches): " + prefs.filter { it != 0.0 }.joinToString { p ->
            "%+.2f -> %.4f".format(p, trueSlope(p, play("DEFAULT", p, 1.0, 0).reaches))
        })
        out.appendLine("cols: f R model pref | corrN corrAll contrast hitShare | medSlope meanSlope sdSlope meanSE offered/valid right/offered | p95|z| (flat)")
        for (f in listOf(0.75, 0.85, 1.0)) for (model in models) for (pref in prefs) {
            val n = if (pref == 0.0) flatSeeds else seedsN
            val runs = parallelList(n) { s -> play(model, pref, f, s) }
            for (rounds in listOf(8, 16)) {
                val fits = runs.map { if (rounds == 8) it.fit8 else it.fit16 }
                val valid = fits.filter { it.standardError > 0.0 }
                val slopes = valid.map { it.slope }
                val offered = valid.filter { it.recommended != null }
                val right = offered.count { pref != 0.0 && sign(it.slope) == sign(pref) }
                // correlations over the reaches of the rounds played so far (rounds 0..R-1 are not tagged by index, so use the fit's full set for R=16 only)
                val reachesForCorr = runs.map { r -> if (rounds == 8) r.reaches8 else r.reaches }
                val normalCorr = corr(used(reachesForCorr.flatten()).filter { !it.rushed })
                val allCorr = corr(used(reachesForCorr.flatten()))
                val z95 = if (pref == 0.0) "%.2f".format(percentileOf(valid.map { abs(it.slope) / it.standardError }, 0.95)) else "-"
                out.appendLine(
                    "f=%.2f R=%2d %-9s %+.2f | %.3f %.3f %.3f %.3f | %+.4f %+.4f %.4f %.4f %d/%d %d/%d | %s".format(
                        f, rounds, model, pref, normalCorr, allCorr, valid.map { it.speedContrast }.average(),
                        runs.sumOf { it.hits }.toDouble() / runs.sumOf { it.targets },
                        median(slopes), slopes.average(), if (slopes.size > 1) sd(slopes) else Double.NaN, valid.map { it.standardError }.average(),
                        offered.size, valid.size, right, offered.size, z95,
                    ),
                )
            }
            out.appendLine("  [elapsed %d s]".format((System.currentTimeMillis() - started) / 1000))
        }
        write("speed-round-probe.txt", out.toString())
    }
}
